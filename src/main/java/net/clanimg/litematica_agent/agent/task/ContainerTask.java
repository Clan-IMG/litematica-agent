package net.clanimg.litematica_agent.agent.task;

import net.clanimg.litematica_agent.agent.BuildAgent;
import net.clanimg.litematica_agent.inventory.InventoryHelper;
import net.clanimg.litematica_agent.movement.RotationController;
import net.clanimg.litematica_agent.placement.Aiming;
import net.clanimg.litematica_agent.storage.ContainerRecord;
import net.clanimg.litematica_agent.ui.Chat;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Visits containers one after another, opens them like a player and moves items with shift-clicks.
 */
public final class ContainerTask implements AgentTask {
    public enum Mode {
        WITHDRAW,
        DEPOSIT
    }

    /**
     * @param items item id to amount to take (withdraw) or to put in (deposit)
     */
    public record Visit(ContainerRecord container, Map<String, Integer> items) {
    }

    private enum Phase {
        TRAVEL,
        HOME,
        APPROACH,
        AIM,
        WAIT_OPEN,
        TRANSFER,
        CLOSE
    }

    private static final int OPEN_TIMEOUT = 40;
    private static final int MAX_VISIT_TICKS = 20 * 90;

    private final Mode mode;
    private final List<Visit> visits;
    private int visitIndex;
    private Phase phase = Phase.TRAVEL;
    private @Nullable Approach approach;
    private @Nullable HomeStep home;
    private boolean homeUsed;
    private Map<String, Integer> remaining = new LinkedHashMap<>();
    private @Nullable ScreenHandler handler;
    private int timer;
    private int clickCooldown;
    private int openAttempts;
    private String failure = "";
    private int unreachable;
    private int visitTicks;
    /** Ticks spent on journeys to far-off chests; they extend the task's time limit by exactly that. */
    private int travelTicks;
    private int approachAttempts;
    private int aimAttempts;
    private int transferFailures;
    private @Nullable ContainerRecord homeRequired;
    private @Nullable String pendingItem;
    private int pendingInventoryCount;
    private int pendingTicks;
    private boolean transferredAny;

    public ContainerTask(Mode mode, List<Visit> visits) {
        this.mode = mode;
        this.visits = visits;
    }

    public Mode mode() {
        return this.mode;
    }

    /** The caller pauses once with concrete home setup instructions after this task finishes. */
    public @Nullable ContainerRecord homeRequired() {
        // A failed optional lookahead chest must not stop work supplied by another successful visit.
        return this.transferredAny ? null : this.homeRequired;
    }

    public boolean transferredAny() {
        return this.transferredAny;
    }

    @Override
    public Result tick(BuildAgent agent) {
        if (this.visitIndex >= this.visits.size()) {
            return this.unreachable == this.visits.size() && !this.visits.isEmpty() ? Result.FAILED : Result.SUCCESS;
        }
        Visit visit = this.visits.get(this.visitIndex);
        ContainerRecord container = visit.container();
        BlockPos pos = new BlockPos(container.x, container.y, container.z);
        ClientPlayerEntity player = agent.player();
        agent.setHeldSneak(false);
        this.timer++;
        // The journey to a far-off chest (a plot of hundreds of blocks) has its own limit in the approach; the visit's
        // and the route's budgets are for the last stretch, the search for a stand spot and the chest itself.
        boolean travelling = this.phase == Phase.APPROACH && this.approach != null && this.approach.travelling();
        if (travelling) {
            this.travelTicks++;
            this.timer = 0;
        } else if (++this.visitTicks > MAX_VISIT_TICKS) {
            this.unreachable++;
            this.failure = "container_visit_timeout:" + this.phase;
            if (this.phase == Phase.APPROACH || this.phase == Phase.HOME) {
                this.homeRequired = container;
            }
            agent.markContainerUnreachable(container.key());
            this.nextVisit(agent, true);
            return Result.RUNNING;
        }

        switch (this.phase) {
            case TRAVEL -> {
                if (!container.dimension.equals(agent.dimensionId())) {
                    String command = agent.storageHomeCommand(container);
                    if (!this.homeUsed && !command.isEmpty()) {
                        this.remaining = new LinkedHashMap<>(visit.items());
                        this.home = new HomeStep(command, container.dimension, pos);
                        this.next(Phase.HOME);
                    } else {
                        this.unreachable++;
                        this.failure = "container_wrong_dimension";
                        this.homeRequired = container;
                        this.nextVisit(agent, true);
                    }
                    return Result.RUNNING;
                }
                this.remaining = new LinkedHashMap<>(visit.items());
                // Always try the ordinary route before using the saved fallback.
                this.approach = new Approach(pos);
                this.next(Phase.APPROACH);
            }
            case HOME -> {
                HomeStep.State state = this.home.tick(agent);
                if (state != HomeStep.State.RUNNING) {
                    this.homeUsed = true;
                    if (state == HomeStep.State.FAILED) {
                        this.failure = "storage_home_failed";
                    }
                    this.approach = new Approach(pos);
                    this.next(Phase.APPROACH);
                }
            }
            case APPROACH -> {
                if (this.timer > 20 * 35) {
                    String command = agent.storageHomeCommand(container);
                    agent.movement().stop();
                    if (!this.homeUsed && !command.isEmpty()) {
                        this.home = new HomeStep(command, container.dimension, pos);
                        this.next(Phase.HOME);
                    } else {
                        this.unreachable++;
                        this.failure = "container_route_timeout";
                        this.homeRequired = container;
                        agent.markContainerUnreachable(container.key());
                        this.nextVisit(agent, true);
                    }
                    return Result.RUNNING;
                }
                Approach.State state = this.approach.tick(agent);
                if (state == Approach.State.READY) {
                    this.next(Phase.AIM);
                } else if (state == Approach.State.FAILED) {
                    String homeCommand = agent.storageHomeCommand(container);
                    if (!this.homeUsed && ++this.approachAttempts < 2) {
                        this.approach = new Approach(pos);
                        return Result.RUNNING;
                    }
                    if (!this.homeUsed && !homeCommand.isEmpty()) {
                        this.home = new HomeStep(homeCommand, container.dimension, pos);
                        this.next(Phase.HOME);
                    } else {
                        this.unreachable++;
                        this.failure = "container_unreachable:" + this.approach.failure();
                        this.homeRequired = container;
                        // So the next restock does not just plan a visit to the same unreachable chest again.
                        agent.markContainerUnreachable(container.key());
                        this.nextVisit(agent, true);
                    }
                }
            }
            case AIM -> {
                InventoryHelper.selectNeutralSlot(player);
                Aiming.Aim aim = Aiming.aimAt(player, pos, player.getEyePos(), agent.reach());
                if (aim == null) {
                    if (++this.aimAttempts > 3) {
                        this.unreachable++;
                        this.failure = "container_no_line_of_sight";
                        agent.markContainerUnreachable(container.key());
                        this.nextVisit(agent, true);
                        return Result.RUNNING;
                    }
                    this.approach = new Approach(pos);
                    this.next(Phase.APPROACH);
                    return Result.RUNNING;
                }
                BlockHitResult hit = Aiming.crosshair(player, agent.reach() + 0.5);
                if (hit != null && hit.getBlockPos().equals(pos) && Aiming.isClearOfEdges(hit) && !player.isSneaking()) {
                    // On the chest: stop turning and click once the server knows this view direction.
                    agent.rotation().hold(player);
                    if (RotationController.isKnownToServer(player)) {
                        agent.setOperatingContainer(true);
                        agent.clickBlock(hit);
                        this.next(Phase.WAIT_OPEN);
                    }
                } else {
                    agent.rotation().setTarget(aim.yaw(), aim.pitch());
                }
                if (this.phase == Phase.AIM && this.timer > 40) {
                    if (++this.aimAttempts > 3) {
                        this.unreachable++;
                        this.failure = "container_aim_failed";
                        agent.markContainerUnreachable(container.key());
                        this.nextVisit(agent, true);
                        return Result.RUNNING;
                    }
                    this.next(Phase.APPROACH);
                    this.approach = new Approach(pos);
                }
            }
            case WAIT_OPEN -> {
                if (agent.client().currentScreen instanceof HandledScreen<?> screen
                        && screen.getScreenHandler() != player.playerScreenHandler) {
                    this.handler = screen.getScreenHandler();
                    this.clickCooldown = agent.config().containerClickDelayTicks();
                    this.next(Phase.TRANSFER);
                    return Result.RUNNING;
                }
                if (this.timer > OPEN_TIMEOUT) {
                    agent.setOperatingContainer(false);
                    this.openAttempts++;
                    if (this.openAttempts > 2) {
                        this.unreachable++;
                        this.failure = "container_not_opened";
                        agent.markContainerUnreachable(container.key());
                        this.nextVisit(agent, true);
                    } else {
                        this.next(Phase.AIM);
                    }
                }
            }
            case TRANSFER -> {
                if (!(agent.client().currentScreen instanceof HandledScreen<?> screen) || screen.getScreenHandler() != this.handler) {
                    agent.setOperatingContainer(false);
                    this.pendingItem = null;
                    if (++this.openAttempts > 2) {
                        this.unreachable++;
                        this.failure = "container_closed_during_transfer";
                        this.nextVisit(agent, true);
                        return Result.RUNNING;
                    }
                    this.next(Phase.AIM);
                    return Result.RUNNING;
                }
                if (this.timer == 1) {
                    agent.syncContainer(container, this.handler);
                }
                if (this.pendingItem != null) {
                    this.pendingTicks++;
                    if (this.pendingTicks < Math.max(4, agent.config().containerClickDelayTicks())) {
                        return Result.RUNNING;
                    }
                    int current = inventoryCount(player, this.pendingItem);
                    int moved = this.mode == Mode.WITHDRAW ? current - this.pendingInventoryCount
                            : this.pendingInventoryCount - current;
                    if (moved > 0) {
                        this.transferredAny = true;
                        this.remaining.merge(this.pendingItem, -moved, Integer::sum);
                        this.pendingItem = null;
                        this.transferFailures = 0;
                    } else if (this.pendingTicks > OPEN_TIMEOUT) {
                        this.pendingItem = null;
                        if (++this.transferFailures >= 3) {
                            this.failure = "container_transfer_rejected";
                            this.unreachable++;
                            agent.markContainerUnreachable(container.key());
                            this.next(Phase.CLOSE);
                        }
                    }
                    return Result.RUNNING;
                }
                if (--this.clickCooldown > 0) {
                    return Result.RUNNING;
                }
                this.clickCooldown = agent.config().containerClickDelayTicks();
                boolean clicked = this.mode == Mode.WITHDRAW
                        ? this.withdrawOne(agent, player, container)
                        : this.depositOne(agent, player);
                if (!clicked) {
                    this.next(Phase.CLOSE);
                }
            }
            case CLOSE -> {
                if (this.handler != null) {
                    if (this.mode == Mode.DEPOSIT) {
                        this.includeDepositedSlots(container, visit, player);
                    }
                    agent.syncContainer(container, this.handler);
                }
                player.closeHandledScreen();
                agent.setOperatingContainer(false);
                this.nextVisit(agent, false);
            }
        }
        return Result.RUNNING;
    }

    private boolean withdrawOne(BuildAgent agent, ClientPlayerEntity player, ContainerRecord container) {
        for (Map.Entry<String, Integer> entry : this.remaining.entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            Slot best = null;
            for (Slot slot : this.handler.slots) {
                if (slot.inventory == player.getInventory() || !slot.hasStack() || !container.allows(slot.getIndex())) {
                    continue;
                }
                ItemStack stack = slot.getStack();
                if (!itemId(stack).equals(entry.getKey())) {
                    continue;
                }
                if (!canAccept(player, stack)) {
                    continue;
                }
                if (stack.isDamageable() && InventoryHelper.remainingDurability(stack) <= agent.config().toolDurabilityReserve) {
                    continue;
                }
                if (best == null || stack.getCount() > best.getStack().getCount()) {
                    best = slot;
                }
            }
            if (best == null) {
                entry.setValue(0);
                continue;
            }
            this.beginTransfer(player, entry.getKey());
            agent.interactionManager().clickSlot(this.handler.syncId, best.id, 0, SlotActionType.QUICK_MOVE, player);
            return true;
        }
        return false;
    }

    private boolean depositOne(BuildAgent agent, ClientPlayerEntity player) {
        for (Slot slot : this.handler.slots) {
            if (slot.inventory != player.getInventory() || !slot.hasStack() || slot.getIndex() >= InventoryHelper.MAIN_SIZE) {
                continue;
            }
            ItemStack stack = slot.getStack();
            String id = itemId(stack);
            Integer amount = this.remaining.get(id);
            if (amount == null || amount <= 0) {
                continue;
            }
            this.beginTransfer(player, id);
            agent.interactionManager().clickSlot(this.handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, player);
            return true;
        }
        return false;
    }

    private void beginTransfer(ClientPlayerEntity player, String item) {
        this.pendingItem = item;
        this.pendingInventoryCount = inventoryCount(player, item);
        this.pendingTicks = 0;
    }

    private static int inventoryCount(ClientPlayerEntity player, String item) {
        int count = 0;
        for (int i = 0; i < InventoryHelper.MAIN_SIZE; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && itemId(stack).equals(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static boolean canAccept(ClientPlayerEntity player, ItemStack incoming) {
        for (int i = 0; i < InventoryHelper.MAIN_SIZE; i++) {
            ItemStack current = player.getInventory().getStack(i);
            if (current.isEmpty() || ItemStack.areItemsAndComponentsEqual(current, incoming)
                    && current.getCount() < current.getMaxCount()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Deposited items may land in slots outside the player's selection; those slots become part of it so the stored
     * leftovers stay usable.
     */
    private void includeDepositedSlots(ContainerRecord container, Visit visit, ClientPlayerEntity player) {
        if (container.slots == null) {
            return;
        }
        for (Slot slot : this.handler.slots) {
            if (slot.inventory != player.getInventory() && slot.hasStack() && visit.items().containsKey(itemId(slot.getStack()))
                    && !container.slots.contains(slot.getIndex())) {
                container.slots.add(slot.getIndex());
            }
        }
    }

    private void nextVisit(BuildAgent agent, boolean skipped) {
        agent.movement().stop();
        if (this.handler != null && agent.client().currentScreen instanceof HandledScreen<?>) {
            agent.player().closeHandledScreen();
        }
        agent.setOperatingContainer(false);
        this.visitIndex++;
        this.handler = null;
        this.openAttempts = 0;
        this.visitTicks = 0;
        this.approachAttempts = 0;
        this.aimAttempts = 0;
        this.transferFailures = 0;
        this.pendingItem = null;
        this.homeUsed = false;
        this.next(Phase.TRAVEL);
    }

    private void next(Phase phase) {
        this.phase = phase;
        this.timer = 0;
    }

    static String itemId(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).toString();
    }

    @Override
    public void cancel(BuildAgent agent) {
        agent.movement().stop();
        if (this.handler != null && agent.client().currentScreen instanceof HandledScreen<?>) {
            agent.player().closeHandledScreen();
        }
        agent.setOperatingContainer(false);
    }

    @Override
    public Text describe() {
        if (this.visitIndex < this.visits.size()) {
            ContainerRecord record = this.visits.get(this.visitIndex).container();
            return Chat.tr(this.mode == Mode.WITHDRAW ? "action.withdraw" : "action.deposit",
                    Text.literal(record.x + " " + record.y + " " + record.z));
        }
        return Chat.tr("action.storage_done");
    }

    @Override
    public String failureReason() {
        return this.failure;
    }

    @Override
    public int timeoutTicks() {
        return MAX_VISIT_TICKS * Math.max(1, this.visits.size()) + 20 * 10 + this.travelTicks;
    }
}

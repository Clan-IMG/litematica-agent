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
import net.minecraft.util.math.Vec3d;
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

    public ContainerTask(Mode mode, List<Visit> visits) {
        this.mode = mode;
        this.visits = visits;
    }

    public Mode mode() {
        return this.mode;
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

        switch (this.phase) {
            case TRAVEL -> {
                if (!container.dimension.equals(agent.dimensionId())) {
                    this.nextVisit(agent, true);
                    return Result.RUNNING;
                }
                this.remaining = new LinkedHashMap<>(visit.items());
                String homeCommand = agent.storageHomeCommand();
                double distance = player.getEntityPos().distanceTo(Vec3d.ofCenter(pos));
                if (!this.homeUsed && !homeCommand.isEmpty() && distance > agent.config().homeDistance) {
                    this.home = new HomeStep(homeCommand);
                    this.next(Phase.HOME);
                } else {
                    this.approach = new Approach(pos);
                    this.next(Phase.APPROACH);
                }
            }
            case HOME -> {
                HomeStep.State state = this.home.tick(agent);
                if (state != HomeStep.State.RUNNING) {
                    this.homeUsed = true;
                    this.approach = new Approach(pos);
                    this.next(Phase.APPROACH);
                }
            }
            case APPROACH -> {
                Approach.State state = this.approach.tick(agent);
                if (state == Approach.State.READY) {
                    this.next(Phase.AIM);
                } else if (state == Approach.State.FAILED) {
                    String homeCommand = agent.storageHomeCommand();
                    if (!this.homeUsed && !homeCommand.isEmpty()) {
                        this.home = new HomeStep(homeCommand);
                        this.next(Phase.HOME);
                    } else {
                        this.unreachable++;
                        this.failure = "container_unreachable:" + this.approach.failure();
                        this.nextVisit(agent, true);
                    }
                }
            }
            case AIM -> {
                InventoryHelper.selectNeutralSlot(player);
                Aiming.Aim aim = Aiming.aimAt(player, pos, player.getEyePos(), agent.reach());
                if (aim == null) {
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
                        this.nextVisit(agent, true);
                    } else {
                        this.next(Phase.AIM);
                    }
                }
            }
            case TRANSFER -> {
                if (!(agent.client().currentScreen instanceof HandledScreen<?> screen) || screen.getScreenHandler() != this.handler) {
                    agent.setOperatingContainer(false);
                    this.next(Phase.AIM);
                    return Result.RUNNING;
                }
                if (this.timer == 1) {
                    agent.syncContainer(container, this.handler);
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
        if (InventoryHelper.freeSlots(player.getInventory()) == 0) {
            return false;
        }
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
            int count = best.getStack().getCount();
            agent.interactionManager().clickSlot(this.handler.syncId, best.id, 0, SlotActionType.QUICK_MOVE, player);
            entry.setValue(entry.getValue() - count);
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
            ItemStack before = stack.copy();
            agent.interactionManager().clickSlot(this.handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, player);
            if (ItemStack.areEqual(before, slot.getStack())) {
                // Container is full: nothing moved.
                this.remaining.put(id, 0);
                continue;
            }
            this.remaining.put(id, amount - before.getCount());
            return true;
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
        agent.setOperatingContainer(false);
        this.visitIndex++;
        this.handler = null;
        this.openAttempts = 0;
        this.next(Phase.TRAVEL);
    }

    private void next(Phase phase) {
        this.phase = phase;
        this.timer = 0;
    }

    private Result fail(String reason) {
        this.failure = reason;
        return Result.FAILED;
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
    public @Nullable BlockPos focus() {
        if (this.visitIndex >= this.visits.size()) {
            return null;
        }
        ContainerRecord container = this.visits.get(this.visitIndex).container();
        return new BlockPos(container.x, container.y, container.z);
    }

    @Override
    public int timeoutTicks() {
        return 20 * 60 * Math.max(1, this.visits.size()) + 20 * 60;
    }
}

package net.clanimg.litematica_agent.agent;

import net.clanimg.litematica_agent.LitematicaAgentClient;
import net.clanimg.litematica_agent.config.AgentConfig;
import net.clanimg.litematica_agent.inventory.InventoryHelper;
import net.clanimg.litematica_agent.movement.InputController;
import net.clanimg.litematica_agent.movement.MovementController;
import net.clanimg.litematica_agent.movement.RotationController;
import net.clanimg.litematica_agent.movement.WorldNavAdapter;
import net.clanimg.litematica_agent.movement.pathing.Goal;
import net.clanimg.litematica_agent.movement.pathing.NavWorld;
import net.clanimg.litematica_agent.movement.pathing.PathOptions;
import net.clanimg.litematica_agent.placement.Aiming;
import net.clanimg.litematica_agent.placement.PlacementSolver;
import net.clanimg.litematica_agent.placement.StandSpots;
import net.clanimg.litematica_agent.schematic.BuildTarget;
import net.clanimg.litematica_agent.ui.Chat;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Creative-mode test helper: builds a tower of double chests in front of the player and fills each one with the
 * exact materials the loaded schematic needs, using the same simulated placement/click machinery as the real build
 * agent. Meant to stock up a base before switching to survival and running the actual {@link BuildAgent}.
 */
public final class StockAgent {
    private static final int SINGLE_CHEST_SLOTS = 27;
    private static final int DOUBLE_CHEST_SLOTS = SINGLE_CHEST_SLOTS * 2;
    private static final int STAND_SPOT_CANDIDATES = 160;

    private static final MovementController.HelperBlocks NOOP_HELPERS = new MovementController.HelperBlocks() {
        @Override
        public int available() {
            return 0;
        }

        @Override
        public boolean placeOnTop(BlockPos below) {
            return false;
        }

        @Override
        public boolean mine(BlockPos pos) {
            return false;
        }
    };

    public record Level(BlockPos posA, BlockPos posB, Map<Item, Integer> contents) {
    }

    private interface Step {
        enum Result {
            RUNNING, SUCCESS, FAILED
        }

        Result tick();

        default void cancel() {
        }

        Text describe();

        default String failureReason() {
            return "";
        }

        default int timeoutTicks() {
            return 20 * 30;
        }

        /** Starts the step from the beginning, e.g. when the job is resumed after a pause. */
        void reset();
    }

    private final AgentManager manager;
    private final MinecraftClient client;
    private final RotationController rotation = new RotationController();
    private final MovementController movement = new MovementController(this.rotation);
    private final PlacementSolver solver = new PlacementSolver();
    private final List<Level> levels;
    private final List<Step> steps = new ArrayList<>();
    private int stepIndex;
    private int totalChestBlocks;
    private int totalItemsNeeded;
    private @Nullable Step current;
    private int stepTicks;
    private long tickCount;
    private long clickedThisTick = -1L;
    private int placeCooldown;
    private boolean heldSneak;
    private boolean operatingContainer;
    private boolean finished;
    private boolean failed;
    private String failureReason = "";
    /** Paused by the player or because a step did not work out; the job continues with that step on resume. */
    private boolean paused;
    private String pauseReason = "";
    private Text action = Text.empty();

    private StockAgent(AgentManager manager, MinecraftClient client, List<Level> levels, Direction chestFacing, Direction towardsB) {
        this.manager = manager;
        this.client = client;
        this.levels = levels;
        // Both chests are planned as the two halves of one double chest, so the second one has to join the first.
        BlockState chest = Blocks.CHEST.getDefaultState().with(Properties.HORIZONTAL_FACING, chestFacing);
        ChestType typeA = ChestBlock.getFacing(chest.with(Properties.CHEST_TYPE, ChestType.LEFT)) == towardsB
                ? ChestType.LEFT : ChestType.RIGHT;
        BlockState chestA = chest.with(Properties.CHEST_TYPE, typeA);
        BlockState chestB = chest.with(Properties.CHEST_TYPE, typeA == ChestType.LEFT ? ChestType.RIGHT : ChestType.LEFT);
        for (Level level : levels) {
            this.steps.add(new PlaceChestStep(level.posA(), chestA, false));
            this.steps.add(new PlaceChestStep(level.posB(), chestB, true));
            this.steps.add(new FillLevelStep(level));
            this.totalChestBlocks += 2;
            for (int amount : level.contents().values()) {
                this.totalItemsNeeded += amount;
            }
        }
    }

    /**
     * Bin-packs {@code materials} into as few double chests as possible and lays them out as a tower two blocks in
     * front of the player, growing upward.
     */
    public static StockAgent create(AgentManager manager, MinecraftClient client, Map<Item, Integer> materials) {
        ClientPlayerEntity player = client.player;
        Direction forward = player.getHorizontalFacing();
        Direction right = forward.rotateYClockwise();
        Direction chestFacing = forward.getOpposite();
        BlockPos originA = player.getBlockPos().offset(forward, 2);
        BlockPos originB = originA.offset(right);

        List<Level> levels = new ArrayList<>();
        Map<Item, Integer> currentContents = null;
        int slotsLeft = 0;
        int level = 0;
        for (Map.Entry<Item, Integer> entry : materials.entrySet()) {
            Item item = entry.getKey();
            int amount = entry.getValue();
            int stackSize = Math.max(1, item.getMaxCount());
            while (amount > 0) {
                if (slotsLeft <= 0) {
                    if (currentContents != null) {
                        levels.add(new Level(originA.up(level), originB.up(level), currentContents));
                        level++;
                    }
                    currentContents = new LinkedHashMap<>();
                    slotsLeft = DOUBLE_CHEST_SLOTS;
                }
                int take = Math.min(amount, stackSize);
                currentContents.merge(item, take, Integer::sum);
                amount -= take;
                slotsLeft--;
            }
        }
        if (currentContents != null && !currentContents.isEmpty()) {
            levels.add(new Level(originA.up(level), originB.up(level), currentContents));
        }
        return new StockAgent(manager, client, levels, chestFacing, right);
    }

    /**
     * The first position of the planned tower that is not free, e.g. because of chests left from an earlier run.
     */
    public @Nullable BlockPos firstBlocked(World world) {
        for (Level level : this.levels) {
            for (BlockPos pos : List.of(level.posA(), level.posB())) {
                if (!world.getBlockState(pos).isReplaceable()) {
                    return pos;
                }
            }
        }
        return null;
    }

    public int totalLevels() {
        return this.levels.size();
    }

    public int totalChestBlocks() {
        return this.totalChestBlocks;
    }

    public int totalItemsNeeded() {
        return this.totalItemsNeeded;
    }

    public int currentLevel() {
        return this.levels.isEmpty() ? 0 : Math.min(this.levels.size(), this.stepIndex / 3 + 1);
    }

    public double progress() {
        return this.steps.isEmpty() ? 1.0 : (double) this.stepIndex / this.steps.size();
    }

    public Text action() {
        return this.current != null ? this.current.describe() : this.action;
    }

    public boolean isFinished() {
        return this.finished;
    }

    public boolean isFailed() {
        return this.failed;
    }

    public String failureReason() {
        return this.failureReason;
    }

    public boolean isOperatingContainer() {
        return this.operatingContainer;
    }

    public boolean isPaused() {
        return this.paused;
    }

    /** Why the job is paused; empty when the player paused it. */
    public String pauseReason() {
        return this.pauseReason;
    }

    void activate() {
        InputController.take();
    }

    void pause(String reason) {
        this.suspend();
        this.paused = true;
        this.pauseReason = reason;
    }

    void resume() {
        this.paused = false;
        this.pauseReason = "";
        this.stepTicks = 0;
        InputController.take();
    }

    void cancel(String reason) {
        this.suspend();
        this.failed = true;
        this.finished = true;
        this.failureReason = reason;
    }

    void suspend() {
        if (this.current != null) {
            this.current.cancel();
            this.current = null;
        }
        this.movement.stop();
        this.rotation.clear();
        this.heldSneak = false;
        this.operatingContainer = false;
        InputController.release();
    }

    void tick() {
        ClientPlayerEntity player = this.client.player;
        if (player == null || this.client.world == null || this.client.interactionManager == null || this.paused) {
            return;
        }
        InputController.take();
        InputController.clear();
        this.heldSneak = false;
        this.tickCount++;
        if (this.placeCooldown > 0) {
            this.placeCooldown--;
        }
        AgentConfig config = this.manager.config();
        this.rotation.setSpeed(config.rotationSpeed(), config.rotationSettle(), config.rotationMinStep());
        // A chest can always be placed from in front of it, so no click has to look away from where it goes.
        this.solver.setAllowLookTricks(false);

        if (this.current == null) {
            if (this.stepIndex >= this.steps.size()) {
                this.finished = true;
                return;
            }
            this.current = this.steps.get(this.stepIndex);
            this.current.reset();
            this.stepTicks = 0;
            this.action = this.current.describe();
        }
        this.stepTicks++;
        Step.Result result = this.current.tick();
        if (result == Step.Result.RUNNING && this.stepTicks > this.current.timeoutTicks()) {
            result = Step.Result.FAILED;
        }
        if (result != Step.Result.RUNNING) {
            Step justFinished = this.current;
            if (result == Step.Result.FAILED) {
                // Pause instead of giving up: the player can look at it and continue with this very step.
                String reason = justFinished.failureReason().isEmpty() ? "timeout" : justFinished.failureReason();
                LitematicaAgentClient.LOGGER.info("Chest stocking paused ({}): {} | player={}", reason,
                        justFinished.describe().getString(), player.getEntityPos());
                this.pause(reason);
                return;
            }
            this.current = null;
            this.stepIndex++;
        }

        if (this.heldSneak) {
            InputController.setSneak(true);
            if (player.getAbilities().flying) {
                InputController.setJump(true);
            }
        }
        // Not in a tick with a click: the movement packet sent after it has to carry the view direction of the click.
        if (this.manager.config().agentFps <= AgentConfig.MIN_AGENT_FPS && this.clickedThisTick != this.tickCount) {
            this.rotation.tick(player);
        }
    }

    RotationController rotation() {
        return this.rotation;
    }

    // ---------------------------------------------------------------- shared helpers

    private double reach(ClientPlayerEntity player) {
        return Math.max(3.0, player.getBlockInteractionRange() - 0.4);
    }

    private boolean canFly(ClientPlayerEntity player) {
        return player.getAbilities().allowFlying;
    }

    private Predicate<BlockPos> standable(ClientPlayerEntity player) {
        WorldNavAdapter nav = new WorldNavAdapter(this.client.world, PathOptions.NONE);
        boolean fly = this.canFly(player);
        return feet -> {
            int feetFlags = nav.flags(feet.getX(), feet.getY(), feet.getZ());
            int headFlags = nav.flags(feet.getX(), feet.getY() + 1, feet.getZ());
            if ((feetFlags & NavWorld.PASSABLE) == 0 || (headFlags & NavWorld.PASSABLE) == 0
                    || ((feetFlags | headFlags) & (NavWorld.DANGER | NavWorld.UNLOADED)) != 0) {
                return false;
            }
            if (fly && !this.movement.isNoFly(feet.getX(), feet.getZ())) {
                return true;
            }
            if ((feetFlags & (NavWorld.WATER | NavWorld.CLIMBABLE)) != 0
                    || (feetFlags & (NavWorld.PASSABLE | NavWorld.SOLID_TOP)) == (NavWorld.PASSABLE | NavWorld.SOLID_TOP)) {
                return true;
            }
            int below = nav.flags(feet.getX(), feet.getY() - 1, feet.getZ());
            return (below & NavWorld.SOLID_TOP) != 0 && (below & NavWorld.DANGER) == 0;
        };
    }

    private boolean moveTo(ClientPlayerEntity player, Goal goal) {
        return this.movement.moveTo(player, goal, this.movement.defaultOptions(player, 0), null);
    }

    private MovementController.Status tickMovement(ClientPlayerEntity player) {
        return this.movement.tick(player, NOOP_HELPERS);
    }

    private void clickBlock(BlockHitResult hit, ClientPlayerEntity player) {
        ActionResult result = this.client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        if (result instanceof ActionResult.Success success && success.swingSource() == ActionResult.SwingSource.CLIENT) {
            player.swingHand(Hand.MAIN_HAND);
        }
        this.placeCooldown = this.manager.config().placeDelayTicks();
        this.clickedThisTick = this.tickCount;
    }

    private boolean placeCooldownReady() {
        return this.placeCooldown <= 0;
    }

    private void setOperatingContainer(boolean operating) {
        this.operatingContainer = operating;
    }

    private static int findFreeStorageSlot(PlayerInventory inventory) {
        for (int i = PlayerInventory.HOTBAR_SIZE; i < InventoryHelper.MAIN_SIZE; i++) {
            if (inventory.getStack(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private static @Nullable Map.Entry<Item, Integer> firstPositive(Map<Item, Integer> map) {
        for (Map.Entry<Item, Integer> entry : map.entrySet()) {
            if (entry.getValue() > 0) {
                return entry;
            }
        }
        return null;
    }

    private static Text posText(BlockPos pos) {
        return Text.literal(pos.getX() + " " + pos.getY() + " " + pos.getZ());
    }

    // ---------------------------------------------------------------- placing a chest block

    private final class PlaceChestStep implements Step {
        private enum P {
            SEARCH, MOVE, PREPARE, SNEAK, AIM, CLICK, VERIFY
        }

        /** Tries with a freshly searched stand position before the job pauses. */
        private static final int MAX_ATTEMPTS = 3;

        private final BlockPos pos;
        private final BuildTarget target;
        /** The second half, which has to join the first one into a double chest. */
        private final boolean joinsPartner;
        private P phase = P.SEARCH;
        private int timer;
        private int reaims;
        private int attempts;
        private boolean moving;
        private @Nullable BlockPos standSpot;
        private @Nullable PlacementSolver.Option option;
        private @Nullable BlockState before;
        private String failure = "";

        PlaceChestStep(BlockPos pos, BlockState state, boolean joinsPartner) {
            this.pos = pos;
            this.target = new BuildTarget(pos, state, Items.CHEST, 1);
            this.joinsPartner = joinsPartner;
        }

        @Override
        public void reset() {
            this.phase = P.SEARCH;
            this.timer = 0;
            this.reaims = 0;
            this.attempts = 0;
            this.moving = false;
            this.standSpot = null;
            this.option = null;
            this.before = null;
            this.failure = "";
        }

        @Override
        public Result tick() {
            ClientPlayerEntity player = StockAgent.this.client.player;
            this.timer++;
            return switch (this.phase) {
                case SEARCH -> this.tickSearch(player);
                case MOVE -> this.tickMove(player);
                case PREPARE -> this.tickPrepare(player);
                case SNEAK -> this.tickSneak(player);
                case AIM -> this.tickAim(player);
                case CLICK -> this.tickClick(player);
                case VERIFY -> this.tickVerify();
            };
        }

        private Result tickSearch(ClientPlayerEntity player) {
            BlockState state = StockAgent.this.client.world.getBlockState(this.pos);
            if (state.isOf(Blocks.CHEST)) {
                return this.joinsPartner && state.get(Properties.CHEST_TYPE) == ChestType.SINGLE
                        ? this.fail("chest_not_connected") : Result.SUCCESS;
            }
            if (StockAgent.this.solver.findFromPlayer(player, this.target, StockAgent.this.reach(player), false) != null) {
                return this.next(P.PREPARE);
            }
            PlacementSolver.StandSpot spot = StockAgent.this.solver.findStandSpot(player, this.target, StockAgent.this.reach(player),
                    StockAgent.this.canFly(player), StockAgent.this.standable(player), STAND_SPOT_CANDIDATES);
            if (spot == null) {
                return this.fail("no_stand_spot");
            }
            this.standSpot = spot.feet();
            return this.next(P.MOVE);
        }

        private Result tickMove(ClientPlayerEntity player) {
            if (!this.moving) {
                this.moving = true;
                if (!StockAgent.this.moveTo(player, Goal.block(this.standSpot.getX(), this.standSpot.getY(), this.standSpot.getZ()))) {
                    return this.retrySearch("path_not_found");
                }
            }
            MovementController.Status status = StockAgent.this.tickMovement(player);
            if (status == MovementController.Status.ARRIVED) {
                return this.next(P.PREPARE);
            }
            if (status == MovementController.Status.FAILED) {
                return this.retrySearch("path_" + StockAgent.this.movement.getFailure());
            }
            if (status == MovementController.Status.IDLE) {
                return this.retrySearch("path_interrupted");
            }
            return Result.RUNNING;
        }

        private Result tickPrepare(ClientPlayerEntity player) {
            BlockState state = StockAgent.this.client.world.getBlockState(this.pos);
            if (state.isOf(Blocks.CHEST)) {
                return Result.SUCCESS;
            }
            if (!InventoryHelper.select(StockAgent.this.client, Items.CHEST)) {
                return this.fail("missing_item");
            }
            this.option = StockAgent.this.solver.findFromEye(player, this.target, player.getEyePos(), StockAgent.this.reach(player));
            if (this.option == null) {
                return this.timer < 8 ? Result.RUNNING : this.retrySearch("no_placement_option");
            }
            StockAgent.this.heldSneak = this.option.sneak();
            return this.next(this.option.sneak() && !player.isInSneakingPose() && !player.getAbilities().flying ? P.SNEAK : P.AIM);
        }

        private Result tickSneak(ClientPlayerEntity player) {
            StockAgent.this.heldSneak = true;
            if (player.isInSneakingPose() || this.timer > 10) {
                this.option = StockAgent.this.solver.findFromEye(player, this.target, player.getEyePos(), StockAgent.this.reach(player));
                if (this.option == null) {
                    // Crouching lowered the eye out of reach: look for a better position.
                    return this.retrySearch("no_placement_option");
                }
                return this.next(P.AIM);
            }
            return Result.RUNNING;
        }

        private Result tickAim(ClientPlayerEntity player) {
            StockAgent.this.heldSneak = this.option.sneak();
            StockAgent.this.rotation.setTarget(this.option.yaw(), this.option.pitch());
            if (StockAgent.this.rotation.isAligned(player, 0.4F)) {
                StockAgent.this.rotation.hold(player);
                return this.next(P.CLICK);
            }
            if (this.timer > 30) {
                return this.retrySearch("aim_timeout");
            }
            return Result.RUNNING;
        }

        private Result tickClick(ClientPlayerEntity player) {
            StockAgent.this.heldSneak = this.option.sneak();
            // Only with a view direction the server already knows, otherwise anti-cheats reject the placement.
            if (!StockAgent.this.placeCooldownReady() || !RotationController.isKnownToServer(player)) {
                return Result.RUNNING;
            }
            if (!player.getMainHandStack().isOf(Items.CHEST)) {
                return this.retry(P.PREPARE);
            }
            BlockHitResult hit = this.option.lookTrick() ? this.option.hit() : Aiming.crosshair(player, StockAgent.this.reach(player) + 0.5);
            if (hit == null || !Aiming.isClearOfEdges(hit) || !StockAgent.this.solver.check(player, this.target, hit, this.option.sneak())) {
                return this.retry(P.PREPARE);
            }
            this.before = StockAgent.this.client.world.getBlockState(this.pos);
            StockAgent.this.clickBlock(hit, player);
            return this.next(P.VERIFY);
        }

        private Result tickVerify() {
            StockAgent.this.heldSneak = this.option != null && this.option.sneak() && this.timer < 3;
            BlockState state = StockAgent.this.client.world.getBlockState(this.pos);
            if (state != this.before && state.isOf(Blocks.CHEST)) {
                if (this.joinsPartner && state.get(Properties.CHEST_TYPE) == ChestType.SINGLE) {
                    return this.fail("chest_not_connected");
                }
                return Result.SUCCESS;
            }
            if (this.timer > 12) {
                return this.retrySearch("placement_not_confirmed");
            }
            return Result.RUNNING;
        }

        private Result retry(P phase) {
            this.reaims++;
            if (this.reaims > 3) {
                return this.retrySearch("crosshair_mismatch");
            }
            return this.next(phase);
        }

        /** Starts over with a new stand position; after a few tries the job pauses with {@code reason}. */
        private Result retrySearch(String reason) {
            if (++this.attempts >= MAX_ATTEMPTS) {
                return this.fail(reason);
            }
            LitematicaAgentClient.LOGGER.info("Chest placement at {} failed ({}), trying again", this.pos.toShortString(), reason);
            StockAgent.this.movement.stop();
            StockAgent.this.heldSneak = false;
            this.moving = false;
            this.standSpot = null;
            this.option = null;
            this.reaims = 0;
            return this.next(P.SEARCH);
        }

        private Result next(P phase) {
            this.phase = phase;
            this.timer = 0;
            return Result.RUNNING;
        }

        private Result fail(String reason) {
            this.failure = reason;
            return Result.FAILED;
        }

        @Override
        public void cancel() {
            StockAgent.this.heldSneak = false;
            StockAgent.this.movement.stop();
        }

        @Override
        public Text describe() {
            return Chat.tr("stock.action_place", posText(this.pos));
        }

        @Override
        public String failureReason() {
            return this.failure;
        }
    }

    // ---------------------------------------------------------------- filling a double chest

    /**
     * Fills a double chest in rounds: hand out stacks into free inventory slots, shift-click them into the chest, wait
     * until the server's answer has arrived and count what really is in the chest. The next round only brings what is
     * still missing, so a click the server did not carry out is repeated instead of being counted as done.
     */
    private final class FillLevelStep implements Step {
        private enum P {
            APPROACH, AIM, WAIT_OPEN, GIVE, DEPOSIT, SETTLE, CLOSE
        }

        /** Ticks after a round of clicks until the view of chest and inventory matches the server again. */
        private static final int SETTLE_TICKS = 10;
        private static final int MAX_ROUNDS = 12;

        private final Level level;
        private final BlockPos openPos;
        /** Items still to hand out in this round. */
        private final Map<Item, Integer> toGive;
        /** Inventory slots filled in this round whose stacks still have to go into the chest. */
        private final List<Integer> pendingSlots = new ArrayList<>();
        /** Every inventory slot this step has put stacks into, to find leftovers without touching the player's items. */
        private final Set<Integer> ownSlots = new HashSet<>();
        private P phase = P.APPROACH;
        private int timer;
        private int clickCooldown;
        private int rounds;
        private boolean moving;
        private @Nullable ScreenHandler handler;
        private String failure = "";

        FillLevelStep(Level level) {
            this.level = level;
            this.openPos = level.posA();
            this.toGive = new LinkedHashMap<>(level.contents());
        }

        /** Stacks left in the inventory from before a pause are still recognised by {@link #ownSlots}. */
        @Override
        public void reset() {
            this.phase = P.APPROACH;
            this.timer = 0;
            this.clickCooldown = 0;
            this.rounds = 0;
            this.moving = false;
            this.handler = null;
            this.pendingSlots.clear();
            this.failure = "";
        }

        @Override
        public Result tick() {
            ClientPlayerEntity player = StockAgent.this.client.player;
            this.timer++;
            return switch (this.phase) {
                case APPROACH -> this.tickApproach(player);
                case AIM -> this.tickAim(player);
                case WAIT_OPEN -> this.tickWaitOpen(player);
                case GIVE -> this.tickGive(player);
                case DEPOSIT -> this.tickDeposit(player);
                case SETTLE -> this.tickSettle(player);
                case CLOSE -> this.tickClose(player);
            };
        }

        private Result tickApproach(ClientPlayerEntity player) {
            if (Aiming.aimAt(player, this.openPos, player.getEyePos(), StockAgent.this.reach(player)) != null) {
                return this.next(P.AIM);
            }
            if (!this.moving) {
                float eyeHeight = player.getEyeHeight(EntityPose.STANDING);
                double planningReach = StockAgent.this.reach(player) - StandSpots.ARRIVAL_MARGIN;
                StandSpots.Result<Aiming.Aim> spot = StandSpots.search(player, this.openPos, StockAgent.this.reach(player),
                        StockAgent.this.canFly(player), StockAgent.this.standable(player), null, 150,
                        feet -> Aiming.aimAt(player, this.openPos, StandSpots.eyeAt(feet, eyeHeight), planningReach));
                if (spot == null) {
                    return this.fail("unreachable");
                }
                if (!StockAgent.this.moveTo(player, Goal.block(spot.feet().getX(), spot.feet().getY(), spot.feet().getZ()))) {
                    return this.fail("path_not_found");
                }
                this.moving = true;
            }
            MovementController.Status status = StockAgent.this.tickMovement(player);
            if (status == MovementController.Status.ARRIVED) {
                this.moving = false;
                if (Aiming.aimAt(player, this.openPos, player.getEyePos(), StockAgent.this.reach(player)) != null) {
                    return this.next(P.AIM);
                }
                return this.fail("unreachable");
            }
            if (status == MovementController.Status.FAILED) {
                return this.fail("path_" + StockAgent.this.movement.getFailure());
            }
            return Result.RUNNING;
        }

        private Result tickAim(ClientPlayerEntity player) {
            InventoryHelper.selectNeutralSlot(player);
            Aiming.Aim aim = Aiming.aimAt(player, this.openPos, player.getEyePos(), StockAgent.this.reach(player));
            if (aim == null) {
                this.moving = false;
                return this.next(P.APPROACH);
            }
            BlockHitResult hit = Aiming.crosshair(player, StockAgent.this.reach(player) + 0.5);
            if (hit != null && hit.getBlockPos().equals(this.openPos) && Aiming.isClearOfEdges(hit) && !player.isSneaking()) {
                // On the chest: stop turning and click once the server knows this view direction.
                StockAgent.this.rotation.hold(player);
                if (RotationController.isKnownToServer(player)) {
                    StockAgent.this.setOperatingContainer(true);
                    StockAgent.this.clickBlock(hit, player);
                    return this.next(P.WAIT_OPEN);
                }
            } else {
                StockAgent.this.rotation.setTarget(aim.yaw(), aim.pitch());
            }
            if (this.timer > 40) {
                this.moving = false;
                return this.next(P.APPROACH);
            }
            return Result.RUNNING;
        }

        private Result tickWaitOpen(ClientPlayerEntity player) {
            if (StockAgent.this.client.currentScreen instanceof HandledScreen<?> screen
                    && screen.getScreenHandler() != player.playerScreenHandler) {
                this.handler = screen.getScreenHandler();
                int chestSlots = 0;
                for (Slot slot : this.handler.slots) {
                    if (slot.inventory != player.getInventory()) {
                        chestSlots++;
                    }
                }
                if (chestSlots < DOUBLE_CHEST_SLOTS) {
                    // Only half a chest: filling it would stop halfway with a full chest.
                    player.closeHandledScreen();
                    StockAgent.this.setOperatingContainer(false);
                    return this.fail("chest_not_connected");
                }
                this.clickCooldown = StockAgent.this.manager.config().containerClickDelayTicks();
                // Count first: after a pause the chest may already hold part of its contents.
                return this.next(P.SETTLE);
            }
            if (this.timer > 40) {
                return this.fail("container_not_opened");
            }
            return Result.RUNNING;
        }

        /** Hands out stacks into free inventory slots until everything of this round is out or the inventory is full. */
        private Result tickGive(ClientPlayerEntity player) {
            if (!this.isOpen()) {
                return this.fail("container_closed");
            }
            if (--this.clickCooldown > 0) {
                return Result.RUNNING;
            }
            this.clickCooldown = StockAgent.this.manager.config().containerClickDelayTicks();

            Map.Entry<Item, Integer> next = firstPositive(this.toGive);
            int slot = next == null ? -1 : findFreeStorageSlot(player.getInventory());
            if (slot < 0) {
                if (this.pendingSlots.isEmpty()) {
                    return next == null ? this.next(P.SETTLE) : this.fail("inventory_full");
                }
                return this.next(P.DEPOSIT);
            }
            Item item = next.getKey();
            int amount = Math.min(next.getValue(), Math.max(1, item.getMaxCount()));
            ItemStack stack = new ItemStack(item, amount);
            player.getInventory().setStack(slot, stack);
            // In singleplayer the packet reaches the server without being serialised: sending the stack the client
            // keeps would let the client's next click change the server's copy too.
            StockAgent.this.client.interactionManager.clickCreativeStack(stack.copy(), slot);
            this.toGive.merge(item, -amount, Integer::sum);
            this.pendingSlots.add(slot);
            this.ownSlots.add(slot);
            return Result.RUNNING;
        }

        /** Shift-clicks the handed out stacks into the chest, one per click. */
        private Result tickDeposit(ClientPlayerEntity player) {
            if (!this.isOpen()) {
                return this.fail("container_closed");
            }
            if (--this.clickCooldown > 0) {
                return Result.RUNNING;
            }
            this.clickCooldown = StockAgent.this.manager.config().containerClickDelayTicks();
            if (this.pendingSlots.isEmpty()) {
                return this.next(P.SETTLE);
            }
            Slot target = this.inventorySlot(player, this.pendingSlots.remove(0));
            if (target != null && target.hasStack()) {
                StockAgent.this.client.interactionManager.clickSlot(this.handler.syncId, target.id, 0, SlotActionType.QUICK_MOVE, player);
            }
            return Result.RUNNING;
        }

        /**
         * Waits for the server's answer, then counts what really is in the chest and what is still lying in the
         * inventory, and plans the next round from that.
         */
        private Result tickSettle(ClientPlayerEntity player) {
            if (!this.isOpen()) {
                return this.fail("container_closed");
            }
            if (this.timer < SETTLE_TICKS) {
                return Result.RUNNING;
            }
            Map<Item, Integer> present = new LinkedHashMap<>();
            int freeChestSlots = 0;
            for (Slot slot : this.handler.slots) {
                if (slot.inventory == player.getInventory()) {
                    continue;
                }
                if (slot.hasStack()) {
                    present.merge(slot.getStack().getItem(), slot.getStack().getCount(), Integer::sum);
                } else {
                    freeChestSlots++;
                }
            }
            this.pendingSlots.clear();
            for (int index : this.ownSlots) {
                ItemStack stack = player.getInventory().getStack(index);
                if (!stack.isEmpty() && this.level.contents().containsKey(stack.getItem())) {
                    // The server did not carry out this shift-click: the stack is still in the inventory.
                    this.pendingSlots.add(index);
                    present.merge(stack.getItem(), stack.getCount(), Integer::sum);
                }
            }
            this.toGive.clear();
            for (Map.Entry<Item, Integer> entry : this.level.contents().entrySet()) {
                int missing = entry.getValue() - present.getOrDefault(entry.getKey(), 0);
                if (missing > 0) {
                    this.toGive.put(entry.getKey(), missing);
                }
            }
            if (this.toGive.isEmpty() && this.pendingSlots.isEmpty()) {
                return this.next(P.CLOSE);
            }
            if (freeChestSlots == 0) {
                return this.fail("chest_full");
            }
            if (++this.rounds > MAX_ROUNDS) {
                return this.fail("fill_failed");
            }
            return this.next(P.GIVE);
        }

        private boolean isOpen() {
            if (StockAgent.this.client.currentScreen instanceof HandledScreen<?> screen && screen.getScreenHandler() == this.handler) {
                return true;
            }
            StockAgent.this.setOperatingContainer(false);
            return false;
        }

        private @Nullable Slot inventorySlot(ClientPlayerEntity player, int index) {
            for (Slot slot : this.handler.slots) {
                if (slot.inventory == player.getInventory() && slot.getIndex() == index) {
                    return slot;
                }
            }
            return null;
        }

        private Result tickClose(ClientPlayerEntity player) {
            player.closeHandledScreen();
            StockAgent.this.setOperatingContainer(false);
            return Result.SUCCESS;
        }

        private Result next(P phase) {
            this.phase = phase;
            this.timer = 0;
            return Result.RUNNING;
        }

        private Result fail(String reason) {
            this.failure = reason;
            return Result.FAILED;
        }

        @Override
        public void cancel() {
            StockAgent.this.movement.stop();
            if (this.handler != null && StockAgent.this.client.currentScreen instanceof HandledScreen<?>) {
                StockAgent.this.client.player.closeHandledScreen();
            }
            StockAgent.this.setOperatingContainer(false);
        }

        @Override
        public Text describe() {
            return Chat.tr("stock.action_fill", posText(this.openPos));
        }

        @Override
        public String failureReason() {
            return this.failure;
        }

        @Override
        public int timeoutTicks() {
            int stacks = 0;
            for (int amount : this.level.contents().values()) {
                stacks += 1 + amount / 16;
            }
            return 20 * 20 + stacks * 40;
        }
    }
}

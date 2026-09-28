package net.clanimg.litematica_agent.agent;

import net.clanimg.litematica_agent.LitematicaAgentClient;
import net.clanimg.litematica_agent.agent.task.AgentTask;
import net.clanimg.litematica_agent.agent.task.BreakTask;
import net.clanimg.litematica_agent.agent.task.ContainerTask;
import net.clanimg.litematica_agent.agent.task.EatTask;
import net.clanimg.litematica_agent.agent.task.HomeTask;
import net.clanimg.litematica_agent.agent.task.InteractTask;
import net.clanimg.litematica_agent.agent.task.PlaceTask;
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
import net.clanimg.litematica_agent.placement.StateMatcher;
import net.clanimg.litematica_agent.planning.BuildCategory;
import net.clanimg.litematica_agent.planning.BuildPlan;
import net.clanimg.litematica_agent.schematic.BuildTarget;
import net.clanimg.litematica_agent.schematic.SchematicAccess;
import net.clanimg.litematica_agent.storage.ContainerRecord;
import net.clanimg.litematica_agent.storage.StorageDatabase;
import net.clanimg.litematica_agent.storage.WithdrawalPlanner;
import net.clanimg.litematica_agent.ui.Chat;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.CropBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.block.PlantBlock;
import net.minecraft.block.SnowBlock;
import net.minecraft.block.StemBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.block.VineBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Decides tick by tick what the player does next while a session is building.
 */
public final class BuildAgent {
    private static final int MAX_ATTEMPTS = 6;
    private static final int NEAR_CANDIDATES = 24;
    private static final int STAND_SEARCHES_PER_TICK = 2;
    private static final int STAND_SPOT_CANDIDATES = 160;
    private static final int LOOKAHEAD_TARGETS = 4096;
    private static final int HELPER_STOCK = 32;
    private static final Direction[] HELPER_DIRECTIONS = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};

    private enum Kind {
        PLACE,
        INTERACT,
        BREAK
    }

    private record Candidate(int index, Kind kind, double distanceSq) {
    }

    private final AgentManager manager;
    private final @Nullable SessionRuntime runtime;
    private final MinecraftClient client;
    private final RotationController rotation = new RotationController();
    private final MovementController movement = new MovementController(this.rotation);
    private final PlacementSolver solver = new PlacementSolver();
    private final EtaEstimator eta = new EtaEstimator();
    private final Set<Long> placedByAgent = new HashSet<>();
    private final LinkedHashSet<Long> helpers = new LinkedHashSet<>();
    private final Map<Long, String> failures = new LinkedHashMap<>();
    private final Set<Item> missing = new LinkedHashSet<>();
    private final Set<String> warnings = new HashSet<>();
    private final Set<String> replacementTools = new LinkedHashSet<>();
    /** Target index to the helper block that supports it. */
    private final Map<Integer, List<Long>> supportHelpers = new LinkedHashMap<>();
    private final LinkedHashSet<Long> pendingHelperRemovals = new LinkedHashSet<>();
    private final MovementController.HelperBlocks helperBlocks = new PillarHelper();
    private @Nullable AgentTask task;
    private long tick;
    private int taskTicks;
    private long lastHomeTick = -100_000L;
    private int placeCooldown;
    private boolean heldSneak;
    private boolean operatingContainer;
    private boolean finalCheckDone;
    private boolean foodRestockPending;
    private long foodRestockFailedTick = -100_000L;
    private long lastTickMillis;
    private int completedWhileActive;
    private Text action = Text.empty();

    BuildAgent(AgentManager manager, @Nullable SessionRuntime runtime, MinecraftClient client) {
        this.manager = manager;
        this.runtime = runtime;
        this.client = client;
    }

    /**
     * An agent without a build session that only puts leftover materials back into the storage.
     */
    static BuildAgent depositOnly(AgentManager manager, MinecraftClient client) {
        return new BuildAgent(manager, null, client);
    }

    boolean isDepositOnly() {
        return this.runtime == null;
    }

    public @Nullable AgentSession session() {
        return this.runtime == null ? null : this.runtime.session();
    }

    boolean startDepositNow() {
        InputController.take();
        return this.startDeposit();
    }

    /**
     * Teleports back to the build site with the configured server command before building.
     */
    void travelHome(String command) {
        if (!command.isEmpty()) {
            this.start(new HomeTask(command));
        }
    }

    // ---------------------------------------------------------------- lifecycle

    void activate() {
        this.lastTickMillis = 0L;
        this.finalCheckDone = false;
        this.missing.clear();
        if (this.runtime != null) {
            this.runtime.plan().retryFailed();
        }
        this.failures.clear();
        InputController.take();
    }

    void suspend() {
        if (this.task != null) {
            this.task.cancel(this);
            this.task = null;
        }
        this.movement.stop();
        this.rotation.clear();
        this.heldSneak = false;
        this.operatingContainer = false;
        InputController.release();
        if (this.client.interactionManager != null && this.client.interactionManager.isBreakingBlock()) {
            this.client.interactionManager.cancelBlockBreaking();
        }
    }

    void tick() {
        ClientPlayerEntity player = this.client.player;
        if (player == null || this.client.world == null || this.client.interactionManager == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (this.lastTickMillis > 0L && this.runtime != null) {
            this.runtime.session().activeMillis += Math.min(1000L, now - this.lastTickMillis);
        }
        this.lastTickMillis = now;
        this.tick++;
        if (this.placeCooldown > 0) {
            this.placeCooldown--;
        }
        this.rotation.setSpeed(this.config().rotationSpeed);
        this.solver.setAllowLookTricks(this.config().allowLookTricks);
        this.movement.setSprintAllowed(this.config().sprint);
        InputController.take();
        InputController.clear();
        this.heldSneak = false;

        if (this.task != null) {
            AgentTask.Result result = this.task.tick(this);
            if (result == AgentTask.Result.RUNNING && ++this.taskTicks > this.task.timeoutTicks()) {
                LitematicaAgentClient.LOGGER.warn("Aborting {} after {} ticks: {}", this.task.getClass().getSimpleName(),
                        this.taskTicks, this.task.describe().getString());
                this.task.cancel(this);
                result = AgentTask.Result.FAILED;
            }
            if (result != AgentTask.Result.RUNNING) {
                this.finishTask(result);
            }
        } else if (this.runtime == null) {
            this.manager.finishDeposit(this);
            return;
        } else if (this.manager.isBuilding(this)) {
            this.decide();
        }

        if (this.heldSneak) {
            InputController.setSneak(true);
            if (player.getAbilities().flying) {
                // In flight shift means "descend"; holding space as well keeps the height while clicking.
                InputController.setJump(true);
            }
        }
        this.rotation.tick(player);

        if (this.tick % 20 == 0 && this.runtime != null) {
            this.eta.sample(this.runtime.session().activeMillis, this.completedWhileActive);
            this.runtime.updateCounters();
        }
    }

    // ---------------------------------------------------------------- decisions

    private void decide() {
        ClientPlayerEntity player = this.player();
        ClientWorld world = this.world();
        BuildPlan<BuildTarget> plan = this.runtime.plan();

        if (!player.isInCreativeMode() && player.getHungerManager().getFoodLevel() <= this.config().eatAtFoodLevel) {
            if (InventoryHelper.findFood(player.getInventory()) != null) {
                this.start(new EatTask());
                return;
            }
            if (this.tick - this.foodRestockFailedTick > 20 * 120 && this.startFoodRestock()) {
                this.foodRestockPending = true;
                return;
            }
            this.manager.pause(this, "pause.no_food", List.of());
            return;
        }

        if (!this.replacementTools.isEmpty() && this.startToolRestock()) {
            return;
        }

        while (!this.pendingHelperRemovals.isEmpty()) {
            Long helper = this.pendingHelperRemovals.iterator().next();
            this.pendingHelperRemovals.remove(helper);
            BlockPos helperPos = BlockPos.fromLong(helper);
            if (!this.isAirLike(helperPos)) {
                this.start(new BreakTask(-1, helperPos));
                return;
            }
            this.helpers.remove(helper);
        }

        if (plan.isFinished()) {
            this.finishOrVerify();
            return;
        }

        String buildHome = this.manager.worldData().buildHomeCommand;
        if (!buildHome.isEmpty() && this.tick - this.lastHomeTick > 20 * 10
                && SchematicAccess.distanceTo(this.runtime.placement(), player.getEntityPos()) > this.config().homeDistance) {
            this.lastHomeTick = this.tick;
            this.start(new HomeTask(buildHome));
            return;
        }

        List<Integer> indices = plan.candidates(this.tick);
        if (indices.isEmpty()) {
            this.action = Chat.tr("action.waiting");
            return;
        }

        Vec3d eye = player.getEyePos();
        List<Candidate> actionable = new ArrayList<>();
        Set<Item> needed = new LinkedHashSet<>();
        BuildTarget farTarget = null;

        for (int index : indices) {
            BuildTarget target = plan.get(index);
            BlockPos pos = target.pos();
            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                if (farTarget == null) {
                    farTarget = target;
                }
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if (StateMatcher.isComplete(state, target.state())) {
                this.markDone(index);
                continue;
            }
            double distance = eye.squaredDistanceTo(Vec3d.ofCenter(pos));
            if (StateMatcher.needsInteraction(state, target.state())) {
                actionable.add(new Candidate(index, Kind.INTERACT, distance));
                continue;
            }
            boolean placeable = state.isAir() || state.isReplaceable() || StateMatcher.needsIncrement(state, target.state());
            if (!placeable) {
                if (this.mayBreak(pos, state)) {
                    actionable.add(new Candidate(index, Kind.BREAK, distance));
                    continue;
                }
                this.pauseForWrongBlock(pos, state, target);
                return;
            }
            if (BuildCategory.needsBlockBelow(target.state()) && this.isAirLike(pos.down())) {
                if (plan.indexOf(pos.down().asLong()) < 0) {
                    this.markFailed(index, "falling_without_support");
                } else {
                    this.defer(index, "waiting_for_support");
                }
                continue;
            }
            if (!player.isInCreativeMode() && InventoryHelper.count(player.getInventory(), target.item()) == 0) {
                needed.add(target.item());
                continue;
            }
            actionable.add(new Candidate(index, Kind.PLACE, distance));
        }

        actionable.sort(Comparator.comparingDouble(Candidate::distanceSq));

        // Mid-jump or while falling the eye height is not where the click will happen; wait until stable.
        if (!this.isStable()) {
            return;
        }
        for (int i = 0; i < Math.min(NEAR_CANDIDATES, actionable.size()); i++) {
            Candidate candidate = actionable.get(i);
            BuildTarget target = plan.get(candidate.index());
            switch (candidate.kind()) {
                case PLACE -> {
                    if (!this.isPlayerInTheWay(target) && this.solver.findFromEye(player, target, eye, this.reach()) != null) {
                        this.start(new PlaceTask(candidate.index(), target, null));
                        return;
                    }
                }
                case INTERACT -> {
                    if (Aiming.aimAt(player, target.pos(), eye, this.reach()) != null) {
                        this.start(new InteractTask(candidate.index(), target));
                        return;
                    }
                }
                case BREAK -> {
                    if (Aiming.aimAt(player, target.pos(), eye, this.reach()) != null) {
                        this.start(new BreakTask(candidate.index(), target.pos()));
                        return;
                    }
                }
            }
        }

        int searches = 0;
        for (Candidate candidate : actionable) {
            BuildTarget target = plan.get(candidate.index());
            switch (candidate.kind()) {
                case INTERACT -> {
                    this.start(new InteractTask(candidate.index(), target));
                    return;
                }
                case BREAK -> {
                    this.start(new BreakTask(candidate.index(), target.pos()));
                    return;
                }
                case PLACE -> {
                    if (searches >= STAND_SEARCHES_PER_TICK) {
                        return;
                    }
                    searches++;
                    if (!this.solver.isFeasible(player, target)) {
                        if (this.trySupportHelper(candidate.index(), target)) {
                            return;
                        }
                        this.defer(candidate.index(), "no_support");
                        continue;
                    }
                    PlacementSolver.StandSpot spot = this.findPlaceSpot(target);
                    if (spot != null) {
                        this.start(new PlaceTask(candidate.index(), target, spot.feet()));
                        return;
                    }
                    this.defer(candidate.index(), "no_stand_spot");
                }
            }
        }

        if (!needed.isEmpty()) {
            if (!this.startRestock(needed)) {
                this.missing.addAll(needed);
                this.manager.pause(this, "pause.missing_materials", List.of(itemList(needed)));
            }
            return;
        }

        if (farTarget != null) {
            BlockPos pos = farTarget.pos();
            this.action = Chat.tr("action.travel");
            if (!this.moveTo(Goal.near(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 12.0))) {
                this.manager.pause(this, "pause.unreachable_area", List.of(pos.toShortString()));
                return;
            }
            this.start(new MoveOnlyTask());
            return;
        }
        this.action = Chat.tr("action.waiting");
    }

    private void finishOrVerify() {
        BuildPlan<BuildTarget> plan = this.runtime.plan();
        ClientWorld world = this.world();
        if (!this.finalCheckDone) {
            int reopened = 0;
            for (int i = 0; i < plan.size(); i++) {
                if (plan.status(i) != BuildPlan.Status.DONE) {
                    continue;
                }
                BuildTarget target = plan.get(i);
                BlockPos pos = target.pos();
                if (world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)
                        && !StateMatcher.isComplete(world.getBlockState(pos), target.state())) {
                    plan.markPending(i);
                    reopened++;
                }
            }
            if (reopened > 0) {
                return;
            }
            this.finalCheckDone = true;
        }

        List<Long> remaining = new ArrayList<>(this.helpers);
        // Top first: the agent may be standing on its own pillar and digs down like a player would.
        remaining.sort(Comparator.comparingInt((Long key) -> BlockPos.fromLong(key).getY()).reversed());
        for (Long helper : remaining) {
            BlockPos pos = BlockPos.fromLong(helper);
            if (plan.indexOf(helper) >= 0) {
                this.helpers.remove(helper);
                continue;
            }
            if (this.isAirLike(pos)) {
                this.helpers.remove(helper);
                continue;
            }
            this.action = Chat.tr("action.cleanup");
            this.start(new BreakTask(-1, pos));
            return;
        }

        this.manager.complete(this);
    }

    private void finishTask(AgentTask.Result result) {
        AgentTask finished = this.task;
        this.task = null;
        this.heldSneak = false;
        if (result == AgentTask.Result.FAILED && this.config().verboseLogging) {
            LitematicaAgentClient.LOGGER.info("{} failed ({}): {} | player={}", finished.getClass().getSimpleName(),
                    finished.failureReason(), finished.describe().getString(), this.player().getBlockPos().toShortString());
        }
        if (this.runtime == null) {
            this.manager.markDirty();
            return;
        }
        BuildPlan<BuildTarget> plan = this.runtime.plan();

        if (finished instanceof PlaceTask place) {
            if (place.index() < 0) {
                return;
            }
            if (result == AgentTask.Result.SUCCESS) {
                plan.resetAttempts(place.index());
                BuildTarget target = plan.get(place.index());
                if (StateMatcher.isComplete(this.world().getBlockState(target.pos()), target.state())) {
                    this.markDone(place.index());
                }
                List<Long> used = this.supportHelpers.remove(place.index());
                if (used != null) {
                    for (int i = used.size() - 1; i >= 0; i--) {
                        this.pendingHelperRemovals.add(used.get(i));
                    }
                }
            } else if (!place.isMissingItem()) {
                this.defer(place.index(), place.failureReason());
            }
        } else if (finished instanceof InteractTask interact) {
            if (result == AgentTask.Result.FAILED) {
                this.defer(interact.index(), interact.failureReason());
            }
        } else if (finished instanceof BreakTask breakTask) {
            if (result == AgentTask.Result.FAILED) {
                if (breakTask.index() >= 0) {
                    this.defer(breakTask.index(), breakTask.failureReason());
                } else {
                    this.helpers.remove(breakTask.pos().asLong());
                    this.failures.put(breakTask.pos().asLong(), "helper_not_removed");
                }
            }
        } else if (finished instanceof ContainerTask container) {
            if (this.foodRestockPending) {
                this.foodRestockPending = false;
                if (InventoryHelper.findFood(this.player().getInventory()) == null) {
                    this.foodRestockFailedTick = this.tick;
                }
            }
            if (result == AgentTask.Result.FAILED && container.mode() == ContainerTask.Mode.WITHDRAW) {
                this.warnOnce("storage_unreachable");
            }
            this.manager.markDirty();
        } else if (finished instanceof EatTask && result == AgentTask.Result.FAILED) {
            this.warnOnce("eat_failed");
        }
    }

    private void start(AgentTask task) {
        this.task = task;
        this.taskTicks = 0;
        this.action = task.describe();
    }

    /**
     * True if the player's own body occupies the space the block needs.
     */
    public boolean isPlayerInTheWay(BuildTarget target) {
        net.minecraft.util.math.Box box = PlacementSolver.collisionBox(this.world(), target);
        return box != null && this.player().getBoundingBox().intersects(box);
    }

    private void markDone(int index) {
        BuildPlan<BuildTarget> plan = this.runtime.plan();
        if (plan.status(index) != BuildPlan.Status.DONE) {
            plan.markDone(index);
            this.completedWhileActive++;
        }
    }

    private void markFailed(int index, String reason) {
        this.runtime.plan().markFailed(index);
        this.failures.put(this.runtime.plan().position(index), reason);
        BuildTarget target = this.runtime.plan().get(index);
        LitematicaAgentClient.LOGGER.info("Giving up on {} at {}: {}", target.state(), target.pos().toShortString(), reason);
    }

    private void defer(int index, String reason) {
        BuildPlan<BuildTarget> plan = this.runtime.plan();
        if (this.config().verboseLogging) {
            LitematicaAgentClient.LOGGER.info("Deferring {} ({}), attempt {}", plan.get(index).pos().toShortString(), reason,
                    plan.attempts(index) + 1);
        }
        if (plan.attempts(index) + 1 >= MAX_ATTEMPTS) {
            this.markFailed(index, reason);
            return;
        }
        plan.defer(index, this.tick, 40L * (plan.attempts(index) + 1));
    }

    private boolean mayBreak(BlockPos pos, BlockState state) {
        long key = pos.asLong();
        if (this.placedByAgent.contains(key) || this.helpers.contains(key)) {
            return true;
        }
        if (this.config().breakWrongBlocks) {
            return true;
        }
        Block block = state.getBlock();
        boolean vegetation = block instanceof PlantBlock && !(block instanceof CropBlock) && !(block instanceof StemBlock)
                && !(block instanceof SweetBerryBushBlock) && !(block instanceof NetherWartBlock);
        return vegetation || block instanceof SnowBlock || block instanceof VineBlock;
    }

    private void pauseForWrongBlock(BlockPos pos, BlockState found, BuildTarget target) {
        this.manager.pause(this, "pause.wrong_block", List.of(
                pos.toShortString(),
                found.getBlock().getName().getString(),
                target.state().getBlock().getName().getString()));
    }

    private boolean isAirLike(BlockPos pos) {
        BlockState state = this.world().getBlockState(pos);
        return state.isAir() || (state.isReplaceable() && state.getCollisionShape(this.world(), pos).isEmpty());
    }

    // ---------------------------------------------------------------- helper blocks

    /**
     * Places temporary blocks next to a target that has nothing suitable to be placed against. Only used for blocks
     * that stay in place without support (e.g. a floating trapdoor), and only after real neighbours had a chance.
     * A chain of up to two helpers is tried; every step is simulated first. The helpers are removed right after the
     * target is placed.
     */
    private boolean trySupportHelper(int index, BuildTarget target) {
        if (this.runtime == null || !this.config().useHelperBlocks || BuildCategory.needsBlockBelow(target.state())) {
            return false;
        }
        ClientWorld world = this.world();
        BlockPos pos = target.pos();
        if (!target.state().canPlaceAt(world, pos) || this.hasPendingNeighbour(pos)) {
            return false;
        }
        Item helper = this.helperItem();
        if (!(helper instanceof BlockItem blockItem)) {
            return false;
        }
        BlockState helperState = blockItem.getBlock().getDefaultState();
        ClientPlayerEntity player = this.player();

        for (Direction first : HELPER_DIRECTIONS) {
            BlockPos near = pos.offset(first);
            if (!this.isHelperSpot(near)) {
                continue;
            }
            BuildTarget nearTarget = new BuildTarget(near, helperState, helper, 1);
            if (this.solver.isFeasible(player, nearTarget)) {
                if (this.solver.isFeasibleWith(player, target, List.of(near), helperState)
                        && this.placeHelper(index, nearTarget)) {
                    return true;
                }
                continue;
            }
            for (Direction second : HELPER_DIRECTIONS) {
                BlockPos far = near.offset(second);
                if (far.equals(pos) || !this.isHelperSpot(far)) {
                    continue;
                }
                BuildTarget farTarget = new BuildTarget(far, helperState, helper, 1);
                if (this.solver.isFeasible(player, farTarget)
                        && this.solver.isFeasibleWith(player, nearTarget, List.of(far), helperState)
                        && this.solver.isFeasibleWith(player, target, List.of(far, near), helperState)
                        && this.placeHelper(index, farTarget)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A neighbour that is still going to be built may give the block its natural support, so wait for it first.
     */
    private boolean hasPendingNeighbour(BlockPos pos) {
        BuildPlan<BuildTarget> plan = this.runtime.plan();
        for (Direction direction : Direction.values()) {
            int index = plan.indexOf(pos.offset(direction).asLong());
            if (index >= 0 && plan.status(index) == BuildPlan.Status.PENDING) {
                return true;
            }
        }
        return false;
    }

    private boolean isHelperSpot(BlockPos pos) {
        return this.runtime != null && this.runtime.plan().indexOf(pos.asLong()) < 0
                && this.world().getBlockState(pos).isReplaceable() && !this.helpers.contains(pos.asLong());
    }

    private boolean placeHelper(int targetIndex, BuildTarget helperTarget) {
        BlockPos spot = null;
        if (this.solver.findFromEye(this.player(), helperTarget, this.player().getEyePos(), this.reach()) == null) {
            PlacementSolver.StandSpot standSpot = this.solver.findStandSpot(this.player(), helperTarget, this.reach(),
                    this.canFly(), this.standable(), STAND_SPOT_CANDIDATES);
            if (standSpot == null) {
                return false;
            }
            spot = standSpot.feet();
        }
        long key = helperTarget.pos().asLong();
        this.helpers.add(key);
        this.supportHelpers.computeIfAbsent(targetIndex, ignored -> new ArrayList<>()).add(key);
        this.start(new PlaceTask(-1, helperTarget, spot));
        return true;
    }

    private @Nullable Item helperItem() {
        ClientPlayerEntity player = this.player();
        for (String id : this.config().helperBlocks) {
            Item item = Registries.ITEM.get(Identifier.tryParse(id));
            if (item == null || !(item instanceof BlockItem)) {
                continue;
            }
            if (player.isInCreativeMode() || InventoryHelper.count(player.getInventory(), item) > 0) {
                return item;
            }
        }
        return null;
    }

    private int helperCount() {
        if (!this.config().useHelperBlocks) {
            return 0;
        }
        ClientPlayerEntity player = this.player();
        if (player.isInCreativeMode()) {
            return 64;
        }
        int total = 0;
        for (String id : this.config().helperBlocks) {
            Item item = Registries.ITEM.get(Identifier.tryParse(id));
            if (item instanceof BlockItem) {
                total += InventoryHelper.count(player.getInventory(), item);
            }
        }
        return total;
    }

    private final class PillarHelper implements MovementController.HelperBlocks {
        @Override
        public int available() {
            return BuildAgent.this.helperCount();
        }

        @Override
        public boolean placeOnTop(BlockPos below) {
            Item item = BuildAgent.this.helperItem();
            if (item == null || !InventoryHelper.select(BuildAgent.this.client, item) || !BuildAgent.this.placeCooldownReady()) {
                return false;
            }
            BlockHitResult hit = Aiming.crosshair(BuildAgent.this.player(), BuildAgent.this.reach());
            if (hit == null || !hit.getBlockPos().equals(below) || hit.getSide() != Direction.UP) {
                return false;
            }
            BuildAgent.this.clickBlock(hit);
            BuildAgent.this.helpers.add(below.up().asLong());
            BuildAgent.this.placeCooldown = BuildAgent.this.config().placeDelayTicks;
            return true;
        }

        @Override
        public boolean mine(BlockPos pos) {
            ClientWorld world = BuildAgent.this.world();
            BlockState state = world.getBlockState(pos);
            if (state.isAir() || state.getOutlineShape(world, pos).isEmpty()) {
                BuildAgent.this.onBlockRemoved(pos);
                return true;
            }
            ClientPlayerEntity player = BuildAgent.this.player();
            if (!player.isInCreativeMode()) {
                int slot = InventoryHelper.findBestTool(player.getInventory(), state, BuildAgent.this.config().toolDurabilityReserve);
                InventoryHelper.selectSlot(BuildAgent.this.client, slot);
            }
            ClientPlayerInteractionManager manager = BuildAgent.this.interactionManager();
            if (!manager.isBreakingBlock()) {
                manager.attackBlock(pos, Direction.UP);
            } else {
                manager.updateBlockBreakingProgress(pos, Direction.UP);
            }
            player.swingHand(Hand.MAIN_HAND);
            return false;
        }
    }

    // ---------------------------------------------------------------- restocking

    private boolean startRestock(Set<Item> required) {
        ClientPlayerEntity player = this.player();
        PlayerInventory inventory = player.getInventory();
        StorageDatabase storage = this.manager.storage();
        LinkedHashMap<String, Integer> needs = new LinkedHashMap<>();

        for (Item item : required) {
            needs.put(itemId(item), 0);
        }
        BuildPlan<BuildTarget> plan = this.runtime.plan();
        Map<Item, Integer> upcoming = new LinkedHashMap<>();
        for (int index : plan.upcoming(LOOKAHEAD_TARGETS)) {
            BuildTarget target = plan.get(index);
            upcoming.merge(target.item(), target.count(), Integer::sum);
        }
        for (Map.Entry<Item, Integer> entry : upcoming.entrySet()) {
            int missingAmount = entry.getValue() - InventoryHelper.count(inventory, entry.getKey());
            if (missingAmount > 0) {
                needs.merge(itemId(entry.getKey()), missingAmount, Integer::sum);
            }
        }
        needs.entrySet().removeIf(entry -> entry.getValue() <= 0);

        if (this.config().useHelperBlocks && !player.getAbilities().allowFlying && this.helperCount() < HELPER_STOCK / 2) {
            for (String id : this.config().helperBlocks) {
                if (storage.total(id) > 0 && !needs.containsKey(id)) {
                    needs.put(id, HELPER_STOCK);
                    break;
                }
            }
        }

        Vec3d pos = player.getEntityPos();
        WithdrawalPlanner.Plan withdrawal = WithdrawalPlanner.plan(storage, this.dimensionId(), pos.x, pos.y, pos.z, needs,
                InventoryHelper.freeSlots(inventory), BuildAgent::maxStackSize);
        boolean anyRequired = false;
        for (WithdrawalPlanner.Visit visit : withdrawal.visits()) {
            for (Item item : required) {
                if (visit.take().getOrDefault(itemId(item), 0) > 0) {
                    anyRequired = true;
                }
            }
        }
        if (!anyRequired) {
            return false;
        }
        List<ContainerTask.Visit> visits = new ArrayList<>();
        for (WithdrawalPlanner.Visit visit : withdrawal.visits()) {
            visits.add(new ContainerTask.Visit(visit.container(), visit.take()));
        }
        this.start(new ContainerTask(ContainerTask.Mode.WITHDRAW, visits));
        return true;
    }

    private boolean startFoodRestock() {
        StorageDatabase storage = this.manager.storage();
        Vec3d pos = this.player().getEntityPos();
        for (Map.Entry<String, Integer> entry : storage.totals().entrySet()) {
            Item item = Registries.ITEM.get(Identifier.tryParse(entry.getKey()));
            if (item == null || !InventoryHelper.isFood(new ItemStack(item))) {
                continue;
            }
            LinkedHashMap<String, Integer> needs = new LinkedHashMap<>();
            needs.put(entry.getKey(), Math.min(entry.getValue(), 16));
            WithdrawalPlanner.Plan plan = WithdrawalPlanner.plan(storage, this.dimensionId(), pos.x, pos.y, pos.z, needs,
                    Math.max(1, InventoryHelper.freeSlots(this.player().getInventory())), BuildAgent::maxStackSize);
            if (plan.isEmpty()) {
                continue;
            }
            List<ContainerTask.Visit> visits = new ArrayList<>();
            for (WithdrawalPlanner.Visit visit : plan.visits()) {
                visits.add(new ContainerTask.Visit(visit.container(), visit.take()));
            }
            this.start(new ContainerTask(ContainerTask.Mode.WITHDRAW, visits));
            return true;
        }
        return false;
    }

    /**
     * Puts all building materials left in the inventory back into known containers.
     */
    boolean startDeposit() {
        ClientPlayerEntity player = this.player();
        PlayerInventory inventory = player.getInventory();
        StorageDatabase storage = this.manager.storage();
        Map<String, Integer> leftovers = new LinkedHashMap<>();
        for (int i = 0; i < InventoryHelper.MAIN_SIZE; i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && !InventoryHelper.isTool(stack) && !InventoryHelper.isFood(stack)) {
                leftovers.merge(itemId(stack.getItem()), stack.getCount(), Integer::sum);
            }
        }
        if (leftovers.isEmpty() || storage.isEmpty()) {
            return false;
        }
        Vec3d pos = player.getEntityPos();
        Map<String, Map<String, Integer>> perContainer = new LinkedHashMap<>();
        Map<String, ContainerRecord> records = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : leftovers.entrySet()) {
            List<ContainerRecord> holders = storage.containersWith(entry.getKey(), this.dimensionId(), pos.x, pos.y, pos.z);
            ContainerRecord target = holders.isEmpty() ? this.nearestContainer(storage, pos) : holders.get(0);
            if (target == null) {
                continue;
            }
            perContainer.computeIfAbsent(target.key(), key -> new LinkedHashMap<>()).put(entry.getKey(), entry.getValue());
            records.put(target.key(), target);
        }
        List<ContainerTask.Visit> visits = new ArrayList<>();
        perContainer.forEach((key, items) -> visits.add(new ContainerTask.Visit(records.get(key), items)));
        if (visits.isEmpty()) {
            return false;
        }
        this.start(new ContainerTask(ContainerTask.Mode.DEPOSIT, visits));
        return true;
    }

    boolean isDepositing() {
        return this.task instanceof ContainerTask container && container.mode() == ContainerTask.Mode.DEPOSIT;
    }

    private @Nullable ContainerRecord nearestContainer(StorageDatabase storage, Vec3d pos) {
        ContainerRecord best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ContainerRecord record : storage.containers()) {
            if (!record.dimension.equals(this.dimensionId())) {
                continue;
            }
            double distance = record.distanceSq(pos.x, pos.y, pos.z);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = record;
            }
        }
        return best;
    }

    public void syncContainer(ContainerRecord record, ScreenHandler handler) {
        ClientPlayerEntity player = this.player();
        record.items.clear();
        for (Slot slot : handler.slots) {
            if (slot.inventory != player.getInventory() && slot.hasStack()) {
                record.add(itemId(slot.getStack().getItem()), slot.getStack().getCount());
            }
        }
        record.scannedAt = System.currentTimeMillis();
        this.manager.markDirty();
    }

    // ---------------------------------------------------------------- services for tasks

    public MinecraftClient client() {
        return this.client;
    }

    public ClientPlayerEntity player() {
        return this.client.player;
    }

    public ClientWorld world() {
        return this.client.world;
    }

    public ClientPlayerInteractionManager interactionManager() {
        return this.client.interactionManager;
    }

    public AgentConfig config() {
        return this.manager.config();
    }

    public PlacementSolver solver() {
        return this.solver;
    }

    public RotationController rotation() {
        return this.rotation;
    }

    public MovementController movement() {
        return this.movement;
    }

    public double reach() {
        return Math.max(3.0, this.player().getBlockInteractionRange() - 0.4);
    }

    public boolean canFly() {
        return this.player().getAbilities().allowFlying;
    }

    public String dimensionId() {
        return this.world().getRegistryKey().getValue().toString();
    }

    public String storageHomeCommand() {
        return this.manager.worldData().storageHomeCommand;
    }

    public boolean moveTo(Goal goal) {
        PathOptions options = this.movement.defaultOptions(this.player(), this.helperCount());
        return this.movement.moveTo(this.player(), goal, options, null, this.helpers::contains);
    }

    public MovementController.Status tickMovement() {
        return this.movement.tick(this.player(), this.helperBlocks);
    }

    /**
     * Stand position for placing a block. Without flight, positions in the air are accepted as a second choice when
     * they can be reached by building a pillar of helper blocks below them.
     */
    private @Nullable PlacementSolver.StandSpot findPlaceSpot(BuildTarget target) {
        ClientPlayerEntity player = this.player();
        PlacementSolver.StandSpot spot = this.solver.findStandSpot(player, target, this.reach(), this.canFly(),
                this.standable(), STAND_SPOT_CANDIDATES);
        if (spot != null || this.canFly()) {
            return spot;
        }
        int helpers = this.helperCount();
        if (helpers <= 0) {
            return null;
        }
        return this.solver.findStandSpot(player, target, this.reach(), false, this.pillarStandable(helpers), STAND_SPOT_CANDIDATES);
    }

    /**
     * Air positions above a column of air that ends on solid ground, low enough to pillar up with the helpers.
     */
    private Predicate<BlockPos> pillarStandable(int helpers) {
        WorldNavAdapter nav = new WorldNavAdapter(this.world(), PathOptions.NONE);
        Predicate<BlockPos> standable = this.standable();
        int maxHeight = Math.min(helpers, 12);
        return feet -> {
            if (standable.test(feet)) {
                return true;
            }
            int feetFlags = nav.flags(feet.getX(), feet.getY(), feet.getZ());
            int headFlags = nav.flags(feet.getX(), feet.getY() + 1, feet.getZ());
            if ((feetFlags & NavWorld.PASSABLE) == 0 || (headFlags & NavWorld.PASSABLE) == 0
                    || ((feetFlags | headFlags) & (NavWorld.DANGER | NavWorld.UNLOADED | NavWorld.WATER)) != 0) {
                return false;
            }
            for (int depth = 1; depth <= maxHeight; depth++) {
                int flags = nav.flags(feet.getX(), feet.getY() - depth, feet.getZ());
                if ((flags & NavWorld.SOLID_TOP) != 0) {
                    return (flags & (NavWorld.DANGER | NavWorld.PASSABLE)) == 0;
                }
                if ((flags & NavWorld.PASSABLE) == 0 || (flags & (NavWorld.WATER | NavWorld.DANGER)) != 0) {
                    return false;
                }
            }
            return false;
        };
    }

    /**
     * Feet positions the player can stand at (walking) or hover at (flying).
     */
    public Predicate<BlockPos> standable() {
        WorldNavAdapter nav = new WorldNavAdapter(this.world(), PathOptions.NONE);
        boolean fly = this.canFly();
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

    public boolean selectItem(Item item) {
        return InventoryHelper.select(this.client, item);
    }

    public void debug(String message, Object... args) {
        if (this.config().verboseLogging) {
            LitematicaAgentClient.LOGGER.info(message, args);
        }
    }

    /**
     * The eye is where it will be when clicking: standing on the ground, hovering, swimming or on a ladder.
     */
    public boolean isStable() {
        ClientPlayerEntity player = this.player();
        return player.isOnGround() || player.getAbilities().flying || player.isTouchingWater() || player.isClimbing();
    }

    public void setHeldSneak(boolean sneak) {
        this.heldSneak = sneak;
    }

    public boolean placeCooldownReady() {
        return this.placeCooldown <= 0;
    }

    public void clickBlock(BlockHitResult hit) {
        ClientPlayerEntity player = this.player();
        ActionResult result = this.interactionManager().interactBlock(player, Hand.MAIN_HAND, hit);
        if (result instanceof ActionResult.Success success && success.swingSource() == ActionResult.SwingSource.CLIENT) {
            player.swingHand(Hand.MAIN_HAND);
        }
        this.placeCooldown = this.config().placeDelayTicks;
    }

    public void onBlockPlaced(BlockPos pos) {
        this.placedByAgent.add(pos.asLong());
        if (this.runtime == null) {
            return;
        }
        this.runtime.session().placedBlocks++;
        // Blocks waiting for something to attach to get a fresh chance as soon as a neighbour exists.
        BuildPlan<BuildTarget> plan = this.runtime.plan();
        for (Direction direction : Direction.values()) {
            int neighbour = plan.indexOf(pos.offset(direction).asLong());
            if (neighbour >= 0 && plan.status(neighbour) == BuildPlan.Status.PENDING) {
                plan.resetAttempts(neighbour);
            }
        }
    }

    public void onBlockRemoved(BlockPos pos) {
        this.placedByAgent.remove(pos.asLong());
        this.helpers.remove(pos.asLong());
    }

    public void protectTool(ItemStack stack) {
        String id = itemId(stack.getItem());
        this.warnOnce("tool_protected:" + id, Chat.tr("warn.tool_protected", stack.getName()));
        if (!this.player().isInCreativeMode()) {
            this.replacementTools.add(id);
        }
    }

    /**
     * Fetches a fresh copy of a worn-out tool from the storage, if one is known.
     */
    private boolean startToolRestock() {
        StorageDatabase storage = this.manager.storage();
        Vec3d pos = this.player().getEntityPos();
        for (String id : new ArrayList<>(this.replacementTools)) {
            this.replacementTools.remove(id);
            if (storage.total(id) <= 0) {
                continue;
            }
            LinkedHashMap<String, Integer> needs = new LinkedHashMap<>();
            needs.put(id, 1);
            WithdrawalPlanner.Plan plan = WithdrawalPlanner.plan(storage, this.dimensionId(), pos.x, pos.y, pos.z, needs,
                    Math.max(1, InventoryHelper.freeSlots(this.player().getInventory())), BuildAgent::maxStackSize);
            if (plan.isEmpty()) {
                continue;
            }
            List<ContainerTask.Visit> visits = new ArrayList<>();
            for (WithdrawalPlanner.Visit visit : plan.visits()) {
                visits.add(new ContainerTask.Visit(visit.container(), visit.take()));
            }
            this.start(new ContainerTask(ContainerTask.Mode.WITHDRAW, visits));
            return true;
        }
        return false;
    }

    public void sendServerCommand(String command) {
        String stripped = command.startsWith("/") ? command.substring(1) : command;
        this.player().networkHandler.sendChatCommand(stripped);
    }

    public void setOperatingContainer(boolean operating) {
        this.operatingContainer = operating;
    }

    public boolean isOperatingContainer() {
        return this.operatingContainer;
    }

    // ---------------------------------------------------------------- status

    public @Nullable SessionRuntime runtime() {
        return this.runtime;
    }

    public Text action() {
        return this.task != null ? this.task.describe() : this.action;
    }

    public long remainingMinutes() {
        return this.runtime == null ? 0 : this.eta.remainingMinutes(this.runtime.plan().pendingCount());
    }

    public boolean hasEtaMeasurement() {
        return this.eta.hasMeasurement();
    }

    /**
     * Human readable state for logs and bug reports.
     */
    public String debugSummary() {
        StringBuilder builder = new StringBuilder();
        ClientPlayerEntity player = this.player();
        builder.append("task=").append(this.task == null ? "none" : this.task.getClass().getSimpleName())
                .append(" action=").append(this.action().getString())
                .append(" movement=").append(this.movement.getStatus()).append('/').append(this.movement.getFailure());
        if (player != null) {
            builder.append(" player=").append(player.getBlockPos().toShortString())
                    .append(" flying=").append(player.getAbilities().flying);
        }
        if (this.runtime != null) {
            BuildPlan<BuildTarget> plan = this.runtime.plan();
            builder.append(" done=").append(plan.doneCount()).append(" failed=").append(plan.failedCount())
                    .append(" pending=").append(plan.pendingCount()).append(" helpers=").append(this.helpers.size());
            int shown = 0;
            for (int index : plan.upcoming(Integer.MAX_VALUE)) {
                BuildTarget target = plan.get(index);
                builder.append(System.lineSeparator()).append("  pending ").append(target.pos().toShortString()).append(' ').append(target.state())
                        .append(" attempts=").append(plan.attempts(index))
                        .append(" world=").append(this.world().getBlockState(target.pos()));
                if (++shown >= 12) {
                    break;
                }
            }
        }
        return builder.toString();
    }

    public Map<Long, String> failures() {
        return this.failures;
    }

    public Set<Item> missingItems() {
        return this.missing;
    }

    private void warnOnce(String key) {
        this.warnOnce(key, Chat.tr("warn." + key));
    }

    private void warnOnce(String key, Text message) {
        if (this.warnings.add(key)) {
            Chat.warn(message);
        }
    }

    static String itemId(Item item) {
        return Registries.ITEM.getId(item).toString();
    }

    private static int maxStackSize(String id) {
        Item item = Registries.ITEM.get(Identifier.tryParse(id));
        return item == null ? 64 : item.getMaxCount();
    }

    private static String itemList(Set<Item> items) {
        List<String> names = new ArrayList<>();
        for (Item item : items) {
            names.add(item.getName().getString());
            if (names.size() >= 6) {
                names.add("...");
                break;
            }
        }
        return String.join(", ", names);
    }

    /**
     * Keeps walking towards a far part of the schematic until the movement ends.
     */
    private final class MoveOnlyTask implements AgentTask {
        @Override
        public Result tick(BuildAgent agent) {
            MovementController.Status status = agent.tickMovement();
            if (status == MovementController.Status.MOVING) {
                return Result.RUNNING;
            }
            return status == MovementController.Status.FAILED ? Result.FAILED : Result.SUCCESS;
        }

        @Override
        public void cancel(BuildAgent agent) {
            agent.movement().stop();
        }

        @Override
        public Text describe() {
            return Chat.tr("action.travel");
        }
    }
}

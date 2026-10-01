package net.clanimg.litematica_agent.agent;

import net.clanimg.litematica_agent.LitematicaAgentClient;
import net.clanimg.litematica_agent.agent.task.AgentTask;
import net.clanimg.litematica_agent.agent.task.BreakTask;
import net.clanimg.litematica_agent.agent.task.ContainerTask;
import net.clanimg.litematica_agent.agent.task.EatTask;
import net.clanimg.litematica_agent.agent.task.FluidTask;
import net.clanimg.litematica_agent.agent.task.HomeTask;
import net.clanimg.litematica_agent.agent.task.InteractTask;
import net.clanimg.litematica_agent.agent.task.PlaceTask;
import net.clanimg.litematica_agent.agent.task.SurfaceTask;
import net.clanimg.litematica_agent.config.AgentConfig;
import net.clanimg.litematica_agent.inventory.InventoryHelper;
import net.clanimg.litematica_agent.movement.InputController;
import net.clanimg.litematica_agent.movement.MovementController;
import net.clanimg.litematica_agent.movement.RotationController;
import net.clanimg.litematica_agent.movement.WorldNavAdapter;
import net.clanimg.litematica_agent.movement.pathing.Goal;
import net.clanimg.litematica_agent.movement.pathing.NavWorld;
import net.clanimg.litematica_agent.movement.pathing.PathOptions;
import net.clanimg.litematica_agent.movement.pathing.PosUtil;
import net.clanimg.litematica_agent.placement.Aiming;
import net.clanimg.litematica_agent.placement.PlacementSolver;
import net.clanimg.litematica_agent.placement.StateMatcher;
import net.clanimg.litematica_agent.placement.WaterPlacement;
import net.clanimg.litematica_agent.planning.BuildCategory;
import net.clanimg.litematica_agent.planning.BuildPlan;
import net.clanimg.litematica_agent.schematic.BuildTarget;
import net.clanimg.litematica_agent.schematic.SchematicAccess;
import net.clanimg.litematica_agent.storage.ContainerRecord;
import net.clanimg.litematica_agent.storage.StorageDatabase;
import net.clanimg.litematica_agent.storage.StorageHomes;
import net.clanimg.litematica_agent.storage.WithdrawalPlanner;
import net.clanimg.litematica_agent.ui.Chat;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.block.PlantBlock;
import net.minecraft.block.SnowBlock;
import net.minecraft.block.SpreadableBlock;
import net.minecraft.block.StemBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.block.VineBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectUtil;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/**
 * Decides tick by tick what the player does next while a session is building.
 */
public final class BuildAgent {
    private static final int MAX_ATTEMPTS = 6;
    /** {@link #consecutiveFailures} threshold that pauses the agent. */
    private static final int STALL_FAILURE_COUNT = 10;
    private static final int MAX_RECOVERY_ROUNDS = 2;
    private static final int MAX_HELPER_REMOVAL_ATTEMPTS = 3;
    /** Most recent failures shown in the pause message. */
    private static final int STALL_SUMMARY_COUNT = 4;
    private static final int NEAR_CANDIDATES = 24;
    private static final int STAND_SEARCHES_PER_TICK = 2;
    private static final int STAND_SPOT_CANDIDATES = 160;
    private static final int LOOKAHEAD_TARGETS = 4096;
    private static final int HELPER_STOCK = 32;
    /**
     * How long empty buckets wait for water that was just poured to spread and turn into renewable sources (water
     * moves every five ticks) before the agent fetches full buckets or reports them missing.
     */
    private static final int WATER_SETTLE_TICKS = 200;
    /** Least time between two recovery rounds, see {@link #recoverFailedTargets}. */
    private static final int RECOVERY_SPACING_TICKS = 20 * 60;
    /**
     * Longest scaffold of helper blocks for a block with nothing to build against, see {@link #nextChainHelper}.
     * Controlled by {@link AgentConfig#maxSupportChain}; {@link #effectiveSupportChain()} resolves "unlimited" to
     * this value (the world's full build height) so the search still terminates.
     */
    private static final int UNLIMITED_SUPPORT_CHAIN = 384;
    /** Cells looked at per scaffold search, which keeps a decision fast even without any solution. Scales with the
     *  configured chain length so a long scaffold still has enough search budget to actually reach the ground. */
    private static final int MIN_CHAIN_SEARCH = 200;
    private static final int MAX_CHAIN_SEARCH_CAP = 20_000;
    /** Blocks around the build's footprint that still count as its site, see {@link #buildSite}. */
    private static final int SITE_MARGIN = 3;
    /** Helper blocks this far below the current layer are removed while building. */
    private static final int HELPER_KEEP_BELOW = 3;
    /** How long a container stays excluded after a failed visit, see {@link #unreachableContainers}. */
    private static final long CONTAINER_UNREACHABLE_MILLIS = 90_000L;
    /** Health kept in reserve above the emergency threshold when deciding a risky fall is still acceptable. */
    private static final float SAFE_FALL_HEALTH_BUFFER = 2.0F;
    /** Absolute cap on a deliberately accepted fall, however much health is spare. */
    private static final int SAFE_FALL_MAX = 11;
    /** Finished targets checked against the world per tick: the whole build about every five seconds, within bounds. */
    private static final int MIN_RESCAN_PER_TICK = 256;
    private static final int MAX_RESCAN_PER_TICK = 2000;
    /** Tasks started per tick at most: the one that just finished and the next one. */
    private static final int TASKS_PER_TICK = 3;
    /** Layers with more candidates than this are looked at around the player first, see {@link #nearFirst}. */
    private static final int NEAR_FIRST_THRESHOLD = 2000;
    private static final int NEAR_FIRST_RADIUS = 24;
    /** How long parking stays off once only parked targets were left, see {@link #nothingLeftToTry}. */
    private static final int PARKING_PAUSE_TICKS = 20 * 60;
    /** How often top-down building checks whether the top can be built from, see {@link #topDownCandidates}. */
    private static final int TOP_DOWN_RECHECK_TICKS = 40;
    /** Targets of the top layer near the player looked at for that check. */
    private static final int TOP_DOWN_SAMPLE = 256;
    /** Chunk rings searched around the player when building around the player (32 chunks = 512 blocks). */
    private static final int NEAR_CHUNK_RINGS = 32;
    /** Chunk rings a layer is probed in around the player (3 chunks = 48 blocks), see {@link #layerCandidatesNearby}. */
    private static final int NEAR_LAYER_RINGS = 3;
    /** Layers probed around the player before the whole plan decides where to go next. */
    private static final int NEARBY_LAYERS_LOOKED_AT = 64;
    /** Targets farther away than this (horizontally, in blocks) are travelled to before any placement is planned. */
    private static final double FAR_TARGET_DISTANCE = 48.0;
    /** Nearest targets handed to a decision when building around the player. */
    private static final int PROXIMITY_CANDIDATES = 512;
    /** "Skip all": runs of skipped failures in a row, without anything built in between, before it still pauses. */
    private static final int MAX_SKIPPED_STALLS = 5;
    /** A decision taking longer than this is logged (verbose logging): a fifth of a tick, for the whole decision. */
    private static final long SLOW_DECISION_MICROS = 10_000L;
    /** Targets compared with the world per tick in the final check. */
    private static final int VERIFY_PER_TICK = 20_000;
    private static final Direction[] HELPER_DIRECTIONS = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};

    private enum Kind {
        PLACE,
        INTERACT,
        BREAK,
        /** Work with a bucket or on the water surface, see {@link FluidTask}. */
        FLUID
    }

    private record Candidate(int index, Kind kind, double distanceSq) {
    }

    /** What the player is asked in "ask for approval" mode; answered with the two buttons of the lock screen. */
    public enum DecisionKind {
        /** A block that does not belong to the schematic: approving lets the agent break it. */
        WRONG_BLOCK,
        /** A block the agent cannot build by itself: the player may do it by hand and retry, or skip it. */
        PROBLEM
    }

    private record PendingDecision(DecisionKind kind, int index, long pos, @Nullable StateMatcher.Tool tool) {
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
    /** Wrong blocks the player allowed to break in "ask for approval" mode. */
    private final Set<Long> approvedBreaks = new HashSet<>();
    /** Targets the player chose to skip, and tools the player chose to do without. */
    private final Set<Long> skipped = new HashSet<>();
    private final Set<StateMatcher.Tool> skippedTools = EnumSet.noneOf(StateMatcher.Tool.class);
    /** Tools already fetched from the storage once since the last resume, so a failed fetch is not repeated forever. */
    private final Set<StateMatcher.Tool> toolFetches = EnumSet.noneOf(StateMatcher.Tool.class);
    private @Nullable PendingDecision pendingDecision;
    /** With a block queue: targets that cannot be built yet because other block types are missing around them. */
    private final Set<Long> notYet = new HashSet<>();
    private int helperCleanupLayer = Integer.MIN_VALUE;
    private int rescanCursor;
    private int verifyCursor;
    /** Final verification survives chunk travel, but observed changes invalidate the affected target. */
    private final BitSet finalVerified = new BitSet();
    /** Materials neither in the inventory nor in the known storage; their blocks wait until the next resume. */
    private final Set<Item> missing = new LinkedHashSet<>();
    /**
     * Containers a visit could not physically reach recently (see {@link ContainerRecord#key()}), with the time until
     * which restock planning leaves them out entirely - so a single unreachable chest (behind a gap, on top of a wall
     * with no way up...) does not get proposed again on every subsequent restock attempt.
     */
    private final Map<String, Long> unreachableContainers = new HashMap<>();
    private final Set<String> warnings = new HashSet<>();
    private final Set<String> replacementTools = new LinkedHashSet<>();
    /** Target index to the helper block that supports it. */
    private final Map<Integer, List<Long>> supportHelpers = new LinkedHashMap<>();
    /** Target index to the feet position a placement of it last failed from, see {@link #isPositionalFailure}. */
    private final Map<Integer, Long> failedPlacementFrom = new HashMap<>();
    /** Tick of the last renewable-water search in {@link #canUseWater} and its answer. */
    private long renewableCheckTick = -1L;
    private boolean renewableNearby;
    private boolean waterNearby;
    /** Tick since which the agent waits for poured water to settle into renewable sources, or -1. */
    private long waterWaitSince = -1L;
    /** Until this tick empty buckets do not count as water, see the FluidTask handling in {@link #finishTask}. */
    private long ignoreRenewableUntil;
    /** The task that just ended was aborted for running longer than {@link AgentTask#timeoutTicks}. */
    private boolean taskTimedOut;
    private @Nullable BlockBox buildSite;
    /** Tick of the last recovery round, see {@link #recoverFailedTargets}; -1 before the first. */
    private long lastRecoveryTick = -1L;
    private final LinkedHashSet<Long> pendingHelperRemovals = new LinkedHashSet<>();
    private final Map<Long, Integer> helperRemovalAttempts = new HashMap<>();
    private final Map<Long, Long> helperRemovalRetryAt = new HashMap<>();
    private final MovementController.HelperBlocks helperBlocks = new PillarHelper();
    private @Nullable AgentTask task;
    private long tick;
    private long lastClickTick = -1L;
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
    /**
     * Blocks given up on in a row with no success in between. Guards against silently grinding through a whole area
     * that cannot be built (behind an unreachable wall, missing a tool...): once too many pile up, the agent pauses
     * and shows the player what went wrong instead of failing its way through hundreds more the same way.
     */
    private int consecutiveFailures;
    /** In "skip all" mode: runs of failures skipped without a single block done since, see {@link #checkStalled}. */
    private int skippedStalls;
    /** Decision cost statistics, see {@link #recordDecision}. */
    private long decisions;
    private long decisionMicrosTotal;
    private long decisionMicrosMax;
    /**
     * Targets with nothing to be placed against yet whose support is still going to be built (a block on a wall that
     * is not there yet, a roof above empty space): left out of the candidates, without counting an attempt, until a
     * neighbour is placed. Otherwise a huge layer full of them is searched, put off and failed over and over while
     * everything that could be built right now waits.
     */
    private final BitSet parked = new BitSet();
    private int parkedCount;
    /** After everything else was done, parking stays off for a while so the full search can try, see {@link #decide}. */
    private long parkingPausedUntil;
    /** The session's block selection as items, re-read only when it changes, see {@link #selection}. */
    private List<String> selectionSource = List.of();
    private @Nullable Set<Item> selection;
    /** Whether top-down building starts at the top right now, see {@link #topDownCandidates}. */
    private boolean topDownFromTop = true;
    private long topDownCheckAt;
    private boolean topDownFallbackAnnounced;
    private final RecoveryBudget recoveryBudget = new RecoveryBudget(MAX_RECOVERY_ROUNDS);
    private int inventoryRecoveryAttempts;
    private int emptyRestockAttempts;
    private int travelFailures;
    private long travelRetryAt;
    private Text action = Text.empty();

    BuildAgent(AgentManager manager, @Nullable SessionRuntime runtime, MinecraftClient client) {
        this.manager = manager;
        this.runtime = runtime;
        this.client = client;
        if (runtime != null) {
            this.helpers.addAll(runtime.session().helperBlocks);
        }
    }

    /** Copies the helper blocks into the session, so they are cleaned up even after a restart. */
    void storeHelpers() {
        if (this.runtime != null) {
            this.runtime.session().helperBlocks = new ArrayList<>(this.helpers);
        }
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
        this.finalVerified.clear();
        this.pendingDecision = null;
        this.toolFetches.clear();
        this.notYet.clear();
        this.movement.forgetFailures();
        this.missing.clear();
        this.unreachableContainers.clear();
        this.helperRemovalAttempts.clear();
        this.helperRemovalRetryAt.clear();
        this.failedPlacementFrom.clear();
        this.recoveryBudget.reset();
        this.inventoryRecoveryAttempts = 0;
        this.emptyRestockAttempts = 0;
        this.travelFailures = 0;
        this.travelRetryAt = 0L;
        if (this.runtime != null) {
            this.runtime.plan().retryFailed();
        }
        this.failures.clear();
        this.consecutiveFailures = 0;
        this.skippedStalls = 0;
        // A fresh start looks at everything again: the world may have changed while the agent was paused.
        this.unparkAll();
        this.parkingPausedUntil = 0L;
        this.topDownFromTop = true;
        this.topDownCheckAt = 0L;
        // topDownFallbackAnnounced stays: the hint is meant once per session, not after every (automatic) resume.
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
        // The agent plays: without this the game counts the player as away after a minute without real input and
        // throttles to 30, after ten minutes to 10 frames per second, which slows every turn of the camera and, in
        // practice, the whole agent - over a night that halves the work done.
        this.client.getInactivityFpsLimiter().onInput();
        long now = System.currentTimeMillis();
        if (this.lastTickMillis > 0L && this.runtime != null) {
            this.runtime.session().activeMillis += Math.min(1000L, now - this.lastTickMillis);
        }
        this.lastTickMillis = now;
        this.tick++;
        if (this.placeCooldown > 0) {
            this.placeCooldown--;
        }
        this.rotation.setSpeed(this.config().rotationSpeed(), this.config().rotationSettle(), this.config().rotationMinStep());
        this.solver.setAllowLookTricks(this.config().allowLookTricks);
        this.solver.setAllowAirPlacement(this.config().airPlacement);
        if (this.runtime != null && this.manager.isBuilding(this)) {
            this.rescanSome();
        }
        this.movement.setSprintAllowed(this.config().sprint);
        InputController.take();
        InputController.clear();
        this.heldSneak = false;
        if (this.runtime != null && this.needsAir(player) && !(this.task instanceof SurfaceTask)) {
            // Whatever it was doing stays pending and is picked up again after breathing.
            if (this.task != null) {
                this.task.cancel(this);
            }
            this.start(new SurfaceTask());
        }

        // A finished block is followed by the next one within the same tick, so no tick passes without doing anything.
        for (int step = 0; step < TASKS_PER_TICK; step++) {
            if (this.task == null) {
                if (this.runtime == null) {
                    this.manager.finishDeposit(this);
                    return;
                }
                if (!this.manager.isBuilding(this)) {
                    break;
                }
                long decideStart = System.nanoTime();
                this.decide();
                this.recordDecision(System.nanoTime() - decideStart);
                if (this.task == null) {
                    break;
                }
            }
            AgentTask.Result result = this.task.tick(this);
            this.taskTimedOut = false;
            if (result == AgentTask.Result.RUNNING && ++this.taskTicks > this.task.timeoutTicks()) {
                LitematicaAgentClient.LOGGER.warn("Aborting {} after {} ticks: {}", this.task.getClass().getSimpleName(),
                        this.taskTicks, this.task.describe().getString());
                this.task.cancel(this);
                this.taskTimedOut = true;
                result = AgentTask.Result.FAILED;
            }
            if (result == AgentTask.Result.RUNNING) {
                break;
            }
            this.finishTask(result);
            if (result != AgentTask.Result.SUCCESS) {
                break;
            }
        }

        if (player.getAbilities().allowFlying && !player.getAbilities().flying && !player.isOnGround()
                && !player.isTouchingWater() && !player.isClimbing() && player.getVelocity().y < -0.4) {
            // Survival flight ends the moment the feet touch a block; a step off the edge of the layer just built is
            // then a fall of a hundred blocks. Whatever the task is doing, get the flight back first.
            this.movement.recoverFlight(player);
        } else if (this.heldSneak) {
            InputController.setSneak(true);
            if (player.getAbilities().flying) {
                // In flight shift means "descend"; holding space as well keeps the height while clicking.
                InputController.setJump(true);
            }
        } else if (!this.movement.isMoving() && player.isTouchingWater()
                && player.getFluidHeight(FluidTags.WATER) > player.getSwimHeight()) {
            // Working in water: stay afloat at the surface like a player holding space, instead of slowly sinking
            // while aiming - the head stays above water and the eye does not drift away from the planned click.
            InputController.setJump(true);
        }
        // Not in a tick with a click: the movement packet sent after it has to carry the view direction of the click.
        if (this.config().agentFps <= AgentConfig.MIN_AGENT_FPS && this.lastClickTick != this.tick) {
            this.rotation.tick(player);
        }

        if (this.tick % 20 == 0 && this.runtime != null) {
            this.eta.sample(this.runtime.session().activeMillis, this.completedWhileActive);
            this.runtime.updateCounters();
        }
    }

    // ---------------------------------------------------------------- decisions

    /** Keeps the cost of the decisions in view: with millions of targets a slow decision is what freezes the game. */
    private void recordDecision(long nanos) {
        long micros = nanos / 1000L;
        this.decisions++;
        this.decisionMicrosTotal += micros;
        this.decisionMicrosMax = Math.max(this.decisionMicrosMax, micros);
        if (micros > SLOW_DECISION_MICROS && this.config().verboseLogging) {
            LitematicaAgentClient.LOGGER.warn("Slow decision: {} ms ({})", micros / 1000L, this.action.getString());
        }
    }

    private void decide() {
        ClientPlayerEntity player = this.player();
        ClientWorld world = this.world();
        BuildPlan<BuildTarget> plan = this.runtime.plan();

        if (this.checkStalled()) {
            return;
        }

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
            // Closest one first, not insertion order: two clusters of helpers far apart must not be interleaved into
            // a back-and-forth walk when finishing one before moving to the other is a straight line either way.
            long helper = this.nearestPendingHelperRemoval(player.getEntityPos());
            this.pendingHelperRemovals.remove(helper);
            if (this.helperRemovalAttempts.getOrDefault(helper, 0) >= MAX_HELPER_REMOVAL_ATTEMPTS
                    || this.helperRemovalRetryAt.getOrDefault(helper, 0L) > this.tick) {
                // Keep it in helpers for the bounded final cleanup, while useful building can continue.
                continue;
            }
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

        int layerY = plan.currentLayerY();
        if (this.runtime.session().strategy == BuildStrategy.LAYERS && layerY != this.helperCleanupLayer) {
            this.helperCleanupLayer = layerY;
            this.removeHelpersBelow(layerY);
            if (!this.pendingHelperRemovals.isEmpty()) {
                return;
            }
        }

        String buildHome = this.manager.worldData().buildHomeCommand;
        if (!buildHome.isEmpty() && this.tick - this.lastHomeTick > 20 * 10
                && SchematicAccess.distanceTo(this.runtime.placement(), player.getEntityPos()) > this.config().homeDistance) {
            this.lastHomeTick = this.tick;
            this.start(new HomeTask(buildHome));
            return;
        }

        List<Integer> indices;
        BuildStrategy strategy = this.runtime.session().strategy;
        IntPredicate ready = index -> true;
        if (strategy == BuildStrategy.BLOCKS) {
            indices = this.blockCandidates(plan);
            if (indices == null || this.task != null) {
                return;
            }
        } else {
            Set<Item> selection = this.selection();
            IntPredicate eligible = this.eligibleFilter(plan, selection);
            // Checked live: a target parked earlier in this very decision no longer counts as open for its neighbours.
            ready = index -> !this.parked.get(index) && eligible.test(index);
            // Layers with nothing buildable left (only blocks outside the selection, or of a material the agent has
            // run out of) are skipped by their counters: with only stone in the storage, the first fifty layers of a
            // terrain cut-out would otherwise be scanned in full, twice, on every single decision.
            IntPredicate layers = layer -> this.runtime.layerHasBuildable(layer, selection, this.missing);
            if (strategy == BuildStrategy.LAYERS_TOP_DOWN) {
                indices = this.topDownCandidates(plan, eligible, ready, layers, player.getBlockPos());
            } else if (strategy == BuildStrategy.PROXIMITY) {
                indices = plan.candidatesNear(this.tick, ready, player.getBlockX() >> 4, player.getBlockZ() >> 4, NEAR_CHUNK_RINGS);
                if (indices.isEmpty()) {
                    // Nothing within 512 blocks (or only selected blocks that wait for others of their layer, which
                    // the selection leaves out): whatever is left, in normal order.
                    indices = plan.candidates(this.tick, eligible, ready, layers);
                }
            } else {
                indices = this.layerCandidatesNearby(plan, eligible, ready, layers, player);
            }
            if (indices.isEmpty() && this.nothingLeftToTry(selection)) {
                return;
            }
        }
        if (indices.isEmpty()) {
            this.action = Chat.tr("action.waiting");
            return;
        }
        indices = strategy == BuildStrategy.PROXIMITY
                ? plan.nearest(indices, player.getBlockX(), player.getBlockY(), player.getBlockZ(), PROXIMITY_CANDIDATES)
                : this.nearFirst(indices, plan, player.getBlockPos());

        Vec3d eye = player.getEyePos();
        List<Candidate> actionable = new ArrayList<>();
        Set<Item> needed = new LinkedHashSet<>();
        Map<Item, Boolean> inInventory = new HashMap<>();
        BuildTarget farTarget = null;

        for (int index : indices) {
            BuildTarget target = plan.get(index);
            BlockPos pos = target.pos();
            // Far off (unloaded chunks, or hundreds of blocks away on a plot this size): travel there first, as a
            // journey of its own. A placement task walking that far would run into its own time limit again and
            // again, restarting from wherever it got to.
            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4) || isFarAway(pos, player)) {
                if (farTarget == null) {
                    farTarget = target;
                    // Fetch the material before the journey, not after arriving hundreds of blocks from the storage.
                    if (!player.isInCreativeMode() && !inInventory.computeIfAbsent(target.item(),
                            item -> InventoryHelper.count(player.getInventory(), item) > 0)) {
                        needed.add(target.item());
                    }
                }
                continue;
            }
            if (this.skipped.contains(pos.asLong())) {
                this.markFailed(index, "skipped");
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if (StateMatcher.isComplete(state, target.state())) {
                this.markDone(index);
                continue;
            }
            double distance = eye.squaredDistanceTo(Vec3d.ofCenter(pos));
            WaterPlacement.Action water = WaterPlacement.actionFor(world, pos, target.state());
            if (water != WaterPlacement.Action.NONE) {
                WaterSupply supply = this.waterSupply(water, pos);
                if (supply == WaterSupply.WAIT) {
                    this.action = Chat.tr("action.water_settle");
                    continue;
                }
                if (supply == WaterSupply.NONE) {
                    Item bucket = water == WaterPlacement.Action.DRAIN ? Items.BUCKET : Items.WATER_BUCKET;
                    if (this.missing.contains(bucket)) {
                        this.markFailed(index, "missing_material");
                    } else {
                        needed.add(bucket);
                    }
                    continue;
                }
                actionable.add(new Candidate(index, Kind.FLUID, distance));
                continue;
            }
            if (StateMatcher.needsInteraction(state, target.state())) {
                StateMatcher.Tool tool = StateMatcher.toolFor(state, target.state());
                if (tool != null && !this.hasTool(tool)) {
                    if (this.skippedTools.contains(tool)) {
                        this.markFailed(index, "missing_tool");
                        continue;
                    }
                    if (this.toolFetches.add(tool) && this.startToolFetch(tool)) {
                        return;
                    }
                    if (this.problem(index, "missing_tool", tool, "pause.missing_tool",
                            List.of(blockName(target), pos.toShortString(), toolName(tool)))) {
                        return;
                    }
                    continue;
                }
                actionable.add(new Candidate(index, Kind.INTERACT, distance));
                continue;
            }
            boolean placeable = state.isAir() || state.isReplaceable() || StateMatcher.needsIncrement(state, target.state());
            if (!placeable) {
                if (this.mayBreak(pos, state)) {
                    actionable.add(new Candidate(index, Kind.BREAK, distance));
                    continue;
                }
                if (this.config().wrongBlockMode == AgentConfig.WrongBlockMode.SKIP) {
                    this.markFailed(index, "wrong_block");
                    continue;
                }
                this.pendingDecision = new PendingDecision(DecisionKind.WRONG_BLOCK, index, pos.asLong(), null);
                this.pauseForWrongBlock(pos, state, target);
                return;
            }
            if (BuildCategory.needsBlockBelow(target.state()) && this.isAirLike(pos.down())) {
                boolean paused = plan.indexOf(pos.down().asLong()) < 0
                        ? this.problem(index, "falling_without_support", null, "pause.cannot_place",
                                this.problemArgs(target, "falling_without_support"))
                        : this.defer(index, "waiting_for_support");
                if (paused) {
                    return;
                }
                continue;
            }
            boolean onWater = WaterPlacement.placedOnWater(target.state());
            if (onWater && !WaterPlacement.hasSource(world, pos.down())) {
                // The water below is a lower layer's target; it comes first, so this only waits for it.
                if (this.defer(index, "waiting_for_water")) {
                    return;
                }
                continue;
            }
            if (!onWater && strategy != BuildStrategy.BLOCKS && this.tick >= this.parkingPausedUntil && !this.config().airPlacement
                    && !hasPlacementSupport(world, pos) && this.hasOpenNeighbour(plan, pos, ready)) {
                // Nothing to click against yet, but a neighbour that is still going to be built gives it that: leave
                // it until then (see unparkAround) - six block lookups instead of a placement search that fails,
                // over and over, while the blocks that could be built right now wait.
                this.park(index);
                continue;
            }
            if (!player.isInCreativeMode() && !inInventory.computeIfAbsent(target.item(),
                    item -> InventoryHelper.count(player.getInventory(), item) > 0)) {
                if (this.missing.contains(target.item())) {
                    this.markFailed(index, "missing_material");
                } else {
                    needed.add(target.item());
                }
                continue;
            }
            actionable.add(new Candidate(index, onWater ? Kind.FLUID : Kind.PLACE, distance));
        }

        actionable.sort(Comparator.comparingDouble(Candidate::distanceSq));

        // Mid-jump or while falling the eye height is not where the click will happen; wait until stable.
        if (!this.isStable()) {
            return;
        }
        // Water goes row by row in build order, not by camera angle: sources side by side form renewable water at
        // once (so empty buckets refill on site), and from the second row on the game fills most of a row by itself.
        Candidate firstWater = null;
        for (Candidate candidate : actionable) {
            if (candidate.kind() == Kind.FLUID && WaterPlacement.isWaterTarget(plan.get(candidate.index()).state())
                    && (firstWater == null || candidate.index() < firstWater.index())) {
                firstWater = candidate;
            }
        }
        if (firstWater != null) {
            BuildTarget target = plan.get(firstWater.index());
            this.start(new FluidTask(firstWater.index(), target, this.fluidMode(target)));
            return;
        }
        // Of the blocks close by, the one that needs the smallest turn of the camera comes first: small turns are
        // quick, and everything in reach gets done before walking on.
        List<Candidate> near = new ArrayList<>(actionable.subList(0, Math.min(NEAR_CANDIDATES, actionable.size())));
        Vec3d look = player.getRotationVec(1.0F);
        near.sort(Comparator.comparingDouble(candidate ->
                -look.dotProduct(Vec3d.ofCenter(plan.get(candidate.index()).pos()).subtract(eye).normalize())));
        for (Candidate candidate : near) {
            BuildTarget target = plan.get(candidate.index());
            switch (candidate.kind()) {
                case PLACE -> {
                    BuildTarget placement = placement(target);
                    // Clicks without a look trick only: a trick is left to the stand position search, which tries
                    // every other position first. Not from where this very target already failed (see findPlaceSpot).
                    if (!this.failedFromHere(candidate.index(), player.getBlockPos())
                            && !this.isPlayerInTheWay(placement) && this.solver.findFromPlayer(player, placement, this.reach(), false) != null) {
                        this.start(new PlaceTask(candidate.index(), placement, null, false));
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
                case FLUID -> {
                    FluidTask.Mode mode = this.fluidMode(target);
                    if (FluidTask.aimFor(mode, player, target.pos(), eye, this.reach()) != null) {
                        this.start(new FluidTask(candidate.index(), target, mode));
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
                case FLUID -> {
                    // The task walks to a position from which its click works by itself.
                    this.start(new FluidTask(candidate.index(), target, this.fluidMode(target)));
                    return;
                }
                case PLACE -> {
                    if (searches >= STAND_SEARCHES_PER_TICK) {
                        return;
                    }
                    searches++;
                    BuildTarget placement = placement(target);
                    if (!this.solver.isFeasible(player, placement)) {
                        if (this.trySupportHelper(candidate.index(), placement)) {
                            return;
                        }
                        // Lacking helper blocks in hand is often exactly why no support could be placed; fetch some
                        // from storage before deferring, instead of only noticing once other materials run out too.
                        if (this.startRestock(Set.of())) {
                            return;
                        }
                        if (this.defer(candidate.index(), "no_support")) {
                            return;
                        }
                        continue;
                    }
                    PlacementSolver.StandSpot spot = this.findPlaceSpot(candidate.index(), placement);
                    if (spot != null) {
                        this.start(new PlaceTask(candidate.index(), placement, spot.feet(), spot.option().lookTrick()));
                        return;
                    }
                    if (this.startRestock(Set.of())) {
                        return;
                    }
                    if (this.defer(candidate.index(), "no_stand_spot")) {
                        return;
                    }
                }
            }
        }

        if (!needed.isEmpty()) {
            if (!this.startRestock(needed)) {
                // Nothing of it can be fetched: build everything else first; these blocks are left for later.
                this.missing.addAll(needed);
            }
            return;
        }

        if (farTarget != null) {
            BlockPos pos = farTarget.pos();
            this.action = Chat.tr("action.travel");
            if (this.tick < this.travelRetryAt) {
                return;
            }
            // The column, not the block's own height: the chunks load by column, and the height itself may be void
            // under the plot or rock inside a hill; what to build there is decided once the chunks are there.
            if (!this.moveTo(Goal.column(pos.getX() + 0.5, pos.getZ() + 0.5, 12.0))) {
                this.travelFailed(pos);
                return;
            }
            this.start(new MoveOnlyTask(pos));
            return;
        }
        this.action = Chat.tr("action.waiting");
    }

    /** What a water candidate needs: its bucket action, or putting it onto the water surface. */
    private FluidTask.Mode fluidMode(BuildTarget target) {
        if (WaterPlacement.placedOnWater(target.state())) {
            return FluidTask.Mode.SURFACE;
        }
        return switch (WaterPlacement.actionFor(this.world(), target.pos(), target.state())) {
            case WATERLOG -> FluidTask.Mode.WATERLOG;
            case DRAIN -> FluidTask.Mode.DRAIN;
            default -> FluidTask.Mode.FILL;
        };
    }

    private enum WaterSupply {
        /** A full bucket, or an empty one and renewable water close by. */
        READY,
        /** Only water that was just poured and is still spreading: it turns into renewable sources in a moment. */
        WAIT,
        NONE
    }

    /**
     * Whether a bucket action can happen now. The searches look at a few thousand blocks, so their answers are kept
     * for the rest of the tick.
     */
    private WaterSupply waterSupply(WaterPlacement.Action action, BlockPos pos) {
        ClientPlayerEntity player = this.player();
        if (player.isInCreativeMode()) {
            return WaterSupply.READY;
        }
        PlayerInventory inventory = player.getInventory();
        if (action == WaterPlacement.Action.DRAIN) {
            return InventoryHelper.count(inventory, Items.BUCKET) > 0 ? WaterSupply.READY : WaterSupply.NONE;
        }
        if (InventoryHelper.count(inventory, Items.WATER_BUCKET) > 0) {
            this.waterWaitSince = -1L;
            return WaterSupply.READY;
        }
        if (InventoryHelper.count(inventory, Items.BUCKET) == 0 || this.tick < this.ignoreRenewableUntil) {
            return WaterSupply.NONE;
        }
        if (this.renewableCheckTick != this.tick) {
            ClientWorld world = this.world();
            int radius = WaterPlacement.REFILL_RADIUS;
            this.renewableCheckTick = this.tick;
            this.renewableNearby = WaterPlacement.findRenewable(world, player.getBlockPos(), radius) != null
                    || WaterPlacement.findRenewable(world, pos, radius) != null;
            this.waterNearby = this.renewableNearby || WaterPlacement.hasWaterNear(world, player.getBlockPos(), radius)
                    || WaterPlacement.hasWaterNear(world, pos, radius);
        }
        if (this.renewableNearby) {
            this.waterWaitSince = -1L;
            return WaterSupply.READY;
        }
        if (this.waterNearby) {
            if (this.waterWaitSince < 0L) {
                this.waterWaitSince = this.tick;
            }
            if (this.tick - this.waterWaitSince < WATER_SETTLE_TICKS) {
                return WaterSupply.WAIT;
            }
        }
        return WaterSupply.NONE;
    }

    /**
     * With a huge layer only the targets around the player are looked at, the circle growing until it holds some. Each
     * decision stays fast even with hundreds of thousands of blocks in a layer. Nothing gets stuck: every target that
     * is looked at is either worked on or put aside for a while, so farther ones follow.
     */
    private List<Integer> nearFirst(List<Integer> indices, BuildPlan<BuildTarget> plan, BlockPos feet) {
        if (indices.size() <= NEAR_FIRST_THRESHOLD) {
            return indices;
        }
        for (long radius = NEAR_FIRST_RADIUS; radius <= 4096; radius *= 2) {
            long maxSq = radius * radius;
            List<Integer> near = new ArrayList<>();
            for (int index : indices) {
                long pos = plan.position(index);
                long dx = PosUtil.x(pos) - feet.getX();
                long dz = PosUtil.z(pos) - feet.getZ();
                if (dx * dx + dz * dz <= maxSq) {
                    near.add(index);
                }
            }
            if (!near.isEmpty()) {
                return near;
            }
        }
        return indices;
    }

    /**
     * Layer by layer, around the player first: the lowest open layer's targets in the chunks around the player, so
     * that a decision costs the same for a 700 x 626 plot as for a hut. Only when nothing of that layer is ready
     * nearby does the whole plan get scanned - which finds what is left further away, and the agent walks there.
     */
    private List<Integer> layerCandidatesNearby(BuildPlan<BuildTarget> plan, IntPredicate eligible, IntPredicate ready,
                                                IntPredicate layers, ClientPlayerEntity player) {
        ClientWorld world = this.world();
        int chunkX = player.getBlockX() >> 4;
        int chunkZ = player.getBlockZ() >> 4;
        // A layer whose targets around here have nothing to be placed against yet is left for later and the next
        // layer looked at: a schematic cut out of a landscape has dozens of underground layers below the plot, and
        // the plot's void underneath holds nothing - the building above it can still be built right now.
        int layer = plan.firstOpenLayer(layers);
        for (int looked = 0; layer >= 0 && looked < NEARBY_LAYERS_LOOKED_AT; looked++) {
            List<Integer> near = plan.candidatesNearInLayer(this.tick, ready, layer, chunkX, chunkZ, NEAR_LAYER_RINGS);
            if (!near.isEmpty()) {
                List<Integer> startable = this.config().airPlacement ? near : this.startableNow(near, plan, world, eligible);
                if (!startable.isEmpty()) {
                    return startable;
                }
            }
            layer = plan.nextOpenLayer(layer + 1, layers);
        }
        return plan.candidates(this.tick, eligible, ready, layers);
    }

    /**
     * Of the targets around the player: those that can be clicked into place now, are in a chunk not loaded yet
     * (unknown, so the agent goes there), or have no neighbour that could ever hold them (those get a scaffold). The
     * rest waits for a neighbour to be placed, see {@link #park} and {@link #unparkAround}.
     */
    private List<Integer> startableNow(List<Integer> near, BuildPlan<BuildTarget> plan, ClientWorld world, IntPredicate eligible) {
        List<Integer> result = new ArrayList<>();
        boolean parking = this.tick >= this.parkingPausedUntil;
        for (int index : near) {
            BuildTarget target = plan.get(index);
            BlockPos pos = target.pos();
            if (!parking || !world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4) || WaterPlacement.involvesWater(target.state())
                    || hasPlacementSupport(world, pos) || !this.hasOpenNeighbour(plan, pos, eligible)) {
                result.add(index);
            } else {
                this.park(index);
            }
        }
        return result;
    }

    /** The session's block selection as items, or null to build everything. */
    private @Nullable Set<Item> selection() {
        List<String> queue = this.runtime.session().blockQueue;
        if (!queue.equals(this.selectionSource)) {
            this.selectionSource = List.copyOf(queue);
            Set<Item> items = new HashSet<>();
            for (String id : queue) {
                Identifier identifier = Identifier.tryParse(id);
                if (identifier != null && Registries.ITEM.containsId(identifier)) {
                    items.add(Registries.ITEM.get(identifier));
                }
            }
            this.selection = items.isEmpty() ? null : items;
        }
        return this.selection;
    }

    /**
     * Which targets are to be built at all right now: part of the selection, and not made of a material that is
     * neither in the inventory nor in the storage. The others are left out entirely instead of being given up on one
     * by one - with millions of blocks of a missing material that alone took minutes of walking and failing.
     */
    private IntPredicate eligibleFilter(BuildPlan<BuildTarget> plan, @Nullable Set<Item> selection) {
        boolean anyMissing = !this.missing.isEmpty();
        if (!anyMissing && selection == null) {
            return index -> true;
        }
        return index -> {
            Item item = plan.get(index).item();
            return (selection == null || selection.contains(item)) && !(anyMissing && this.missing.contains(item));
        };
    }

    /**
     * Top-down building starts at the highest layer only where something up there can hold a block already (terrain,
     * or what is built). A building standing on the ground has nothing up there: its roof can only go on once the
     * walls reach it. Until then the agent builds from the bottom instead of trying the roof over and over, and
     * switches to the top as soon as it can be built from. Looked at again every few seconds.
     */
    private List<Integer> topDownCandidates(BuildPlan<BuildTarget> plan, IntPredicate eligible, IntPredicate ready,
                                            IntPredicate layers, BlockPos feet) {
        List<Integer> top = null;
        if (this.tick >= this.topDownCheckAt) {
            this.topDownCheckAt = this.tick + TOP_DOWN_RECHECK_TICKS;
            top = plan.candidatesTopDown(this.tick, eligible, ready, layers);
            boolean fromTop = top.isEmpty() || this.anyWorkableNow(this.nearFirst(top, plan, feet), plan);
            if (!fromTop && !this.topDownFallbackAnnounced) {
                this.topDownFallbackAnnounced = true;
                Chat.info(Chat.tr("info.top_down_from_below"));
            }
            if (fromTop != this.topDownFromTop) {
                LitematicaAgentClient.LOGGER.info("Top-down building {}", fromTop ? "continues from the top" : "starts from below for now");
            }
            this.topDownFromTop = fromTop;
        }
        if (!this.topDownFromTop) {
            // The top layer is not looked at in between: two passes over huge layers per decision would be wasted.
            return this.layerCandidatesNearby(plan, eligible, ready, layers, this.player());
        }
        // Top-down around here first: the highest open layers of the chunks around the player. Otherwise a plot of
        // hundreds of blocks with several hilltops has the agent fly from one to the next for the last few blocks of
        // each layer, seven hundred blocks for a handful of stone.
        List<Integer> around = this.topDownCandidatesNearby(plan, ready, layers, this.player());
        if (!around.isEmpty()) {
            return around;
        }
        return top != null ? top : plan.candidatesTopDown(this.tick, eligible, ready, layers);
    }

    /** The ready targets of the highest open layer (within a few dozen) that has any in the chunks around the player. */
    private List<Integer> topDownCandidatesNearby(BuildPlan<BuildTarget> plan, IntPredicate ready, IntPredicate layers,
                                                  ClientPlayerEntity player) {
        int chunkX = player.getBlockX() >> 4;
        int chunkZ = player.getBlockZ() >> 4;
        int layer = plan.previousOpenLayer(plan.layerCount() - 1, layers);
        for (int looked = 0; layer >= 0 && looked < NEARBY_LAYERS_LOOKED_AT; looked++) {
            List<Integer> near = plan.candidatesNearInLayer(this.tick, ready, layer, chunkX, chunkZ, NEAR_LAYER_RINGS);
            if (!near.isEmpty()) {
                return near;
            }
            layer = plan.previousOpenLayer(layer - 1, layers);
        }
        return List.of();
    }

    /** Whether one of the (first few) targets can be worked on right now: something is there, or holds a new block. */
    private boolean anyWorkableNow(List<Integer> targets, BuildPlan<BuildTarget> plan) {
        if (this.config().airPlacement) {
            return true;
        }
        ClientWorld world = this.world();
        int checked = 0;
        for (int index : targets) {
            if (checked++ >= TOP_DOWN_SAMPLE) {
                break;
            }
            BlockPos pos = plan.get(index).pos();
            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if ((!state.isAir() && !state.isReplaceable()) || hasPlacementSupport(world, pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * No candidate at all right now. Parked targets get the full search (scaffolds included) next; targets that are
     * only put off for a moment are waited for; and when nothing but blocks without material (or outside the
     * selection) is left, the agent finishes up and says so, instead of waiting forever.
     *
     * @return true if something was done about it (a task started, a pause, parking lifted)
     */
    private boolean nothingLeftToTry(@Nullable Set<Item> selection) {
        if (this.parkedCount > 0) {
            // What is left waits for support that is not coming by itself: a floating part, or support that only
            // blocks outside the selection would give. Now the full placement search, scaffolds included, has a go.
            this.unparkAll();
            this.parkingPausedUntil = this.tick + PARKING_PAUSE_TICKS;
            return true;
        }
        Set<Item> missingHere = new HashSet<>(this.missing);
        if (selection != null) {
            missingHere.retainAll(selection);
        }
        int open = selection == null ? this.runtime.plan().pendingCount() : this.runtime.pendingTargets(selection);
        int withoutMaterial = this.runtime.pendingTargets(missingHere);
        if (open - withoutMaterial > 0) {
            return false;
        }
        if (open == 0 && selection == null) {
            return false;
        }
        BuildPlan<BuildTarget> plan = this.runtime.plan();
        if (plan.failedCount() > 0 && this.recoverFailedTargets()) {
            return true;
        }
        if (this.startHelperCleanup()) {
            return true;
        }
        if (withoutMaterial > 0) {
            // Keep the session so it can be finished after more materials were added to the storage.
            this.manager.pause(this, "pause.materials_incomplete", List.of(itemList(missingHere)));
        } else {
            this.manager.pause(this, "pause.selection_done", List.of());
        }
        return true;
    }

    private void park(int index) {
        if (!this.parked.get(index)) {
            this.parked.set(index);
            this.parkedCount++;
        }
    }

    /** A block was placed at {@code pos}: the targets next to it that waited for it are back in the candidates. */
    private void unparkAround(BlockPos pos) {
        if (this.parkedCount == 0 || this.runtime == null) {
            return;
        }
        BuildPlan<BuildTarget> plan = this.runtime.plan();
        for (Direction direction : Direction.values()) {
            int index = plan.indexOf(pos.offset(direction).asLong());
            if (index >= 0 && this.parked.get(index)) {
                this.parked.clear(index);
                this.parkedCount--;
            }
        }
    }

    private void unparkAll() {
        this.parked.clear();
        this.parkedCount = 0;
    }

    /**
     * A pending neighbour that is going to be built (in the selection, with material, not waiting itself) and will
     * give something to click against once it stands. Water, plants and the like never do: a block beside only those
     * is not waiting for anything, it needs the full search (and maybe a scaffold) like before.
     */
    private boolean hasOpenNeighbour(BuildPlan<BuildTarget> plan, BlockPos pos, IntPredicate ready) {
        for (Direction direction : Direction.values()) {
            int index = plan.indexOf(pos.offset(direction).asLong());
            if (index >= 0 && plan.status(index) == BuildPlan.Status.PENDING && ready.test(index)
                    && !plan.get(index).state().isReplaceable()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a block could be clicked into place at {@code pos} right now: something solid next to it, or something
     * with a shape at the spot itself (a plant or snow layer that gets replaced, a slab that gets its second half).
     * Cheap: six block lookups, no placement simulation.
     */
    private static boolean hasPlacementSupport(ClientWorld world, BlockPos pos) {
        BlockState self = world.getBlockState(pos);
        if (!self.isAir() && !self.getOutlineShape(world, pos).isEmpty()) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = pos.offset(direction);
            BlockState state = world.getBlockState(neighbour);
            if (!state.isReplaceable() && !state.getOutlineShape(world, neighbour).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Candidates of the first queued block type that still has targets to build. Targets of that type that cannot be
     * built yet (nothing to attach to because other block types are still missing) are put aside, not treated as
     * problems.
     *
     * @return null when the whole queue is done; the agent then pauses
     */
    private @Nullable List<Integer> blockCandidates(BuildPlan<BuildTarget> plan) {
        for (String id : this.runtime.session().blockQueue) {
            Item item = Registries.ITEM.get(Identifier.tryParse(id));
            IntPredicate open = index -> plan.get(index).item() == item && !this.notYet.contains(plan.position(index));
            List<Integer> candidates = plan.candidates(this.tick, open);
            // An empty list while targets of the type are only deferred means: wait for them, do not skip ahead.
            if (!candidates.isEmpty() || plan.hasPending(open)) {
                return candidates;
            }
        }
        if (this.startHelperCleanup()) {
            return List.of();
        }
        this.manager.pause(this, "pause.queue_done", List.of(String.valueOf(this.notYet.size())));
        return null;
    }

    private void finishOrVerify() {
        BuildPlan<BuildTarget> plan = this.runtime.plan();
        ClientWorld world = this.world();
        if (plan.failedCount() > 0 && this.recoverFailedTargets()) {
            return;
        }
        if (!this.finalCheckDone) {
            // One last comparison of everything with the world, a slice per tick, so a huge build never stalls the
            // game at the end.
            int reopened = 0;
            int end = Math.min(plan.size(), this.verifyCursor + VERIFY_PER_TICK);
            for (int i = this.verifyCursor; i < end; i++) {
                BuildTarget target = plan.get(i);
                BlockPos pos = target.pos();
                BlockState state = world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4) ? world.getBlockState(pos) : null;
                if (state == null || state.isOf(Blocks.VOID_AIR)) {
                    // An unloaded chunk (or one whose blocks have not arrived) has no trustworthy world state. Reopen
                    // it so normal travel loads it.
                    if (plan.status(i) == BuildPlan.Status.DONE && !this.finalVerified.get(i)) {
                        plan.markPending(i);
                        reopened++;
                    }
                } else if (StateMatcher.isComplete(state, target.state())) {
                    this.markDone(i);
                    this.finalVerified.set(i);
                } else if (plan.status(i) == BuildPlan.Status.DONE) {
                    this.finalVerified.clear(i);
                    plan.markPending(i);
                    reopened++;
                }
            }
            this.verifyCursor = end;
            if (reopened > 0) {
                this.verifyCursor = 0;
                return;
            }
            if (this.verifyCursor < plan.size()) {
                return;
            }
            this.verifyCursor = 0;
            this.finalCheckDone = true;
        }

        if (this.startHelperCleanup()) {
            return;
        }
        if (!this.helpers.isEmpty()) {
            this.action = Chat.tr("action.cleanup");
            boolean skipAll = this.config().wrongBlockMode == AgentConfig.WrongBlockMode.SKIP;
            for (Iterator<Long> iterator = this.helpers.iterator(); iterator.hasNext(); ) {
                long key = iterator.next();
                if (this.helperRemovalAttempts.getOrDefault(key, 0) >= MAX_HELPER_REMOVAL_ATTEMPTS) {
                    BlockPos pos = BlockPos.fromLong(key);
                    if (skipAll) {
                        // "Skip all": a helper block that cannot be reached stays where it is; the player is told
                        // where, instead of the build waiting for them at the very end.
                        iterator.remove();
                        Chat.warn(Chat.tr("warn.helper_left", pos.toShortString()));
                        continue;
                    }
                    this.manager.pause(this, "pause.cannot_place", List.of(this.world().getBlockState(pos).getBlock().getName().getString(),
                            pos.toShortString(), Chat.tr("reason.helper_not_removed").getString()));
                    break;
                }
            }
            return;
        }
        this.missing.removeIf(item -> {
            for (int i = 0; i < plan.size(); i++) {
                if (plan.status(i) != BuildPlan.Status.DONE && plan.get(i).item() == item) {
                    return false;
                }
            }
            return true;
        });
        if (!this.missing.isEmpty()) {
            // Keep the session so it can be finished after more materials were added to the storage.
            this.manager.pause(this, "pause.materials_incomplete", List.of(itemList(this.missing)));
            return;
        }
        if (!plan.isComplete()) {
            this.pauseForFailures("pause.blocks_failed", plan.failedCount());
            return;
        }
        this.manager.complete(this);
    }

    /**
     * Checks a slice of the finished targets against the world every tick, so a block someone removed or changed is
     * built again soon instead of only at the final check.
     */
    private void rescanSome() {
        BuildPlan<BuildTarget> plan = this.runtime.plan();
        ClientWorld world = this.world();
        int count = Math.min(plan.size(), Math.max(MIN_RESCAN_PER_TICK, Math.min(MAX_RESCAN_PER_TICK, plan.size() / 100)));
        int reopened = 0;
        BlockPos firstReopened = null;
        for (int n = 0; n < count; n++) {
            if (this.rescanCursor >= plan.size()) {
                this.rescanCursor = 0;
            }
            int index = this.rescanCursor++;
            BuildPlan.Status status = plan.status(index);
            BuildTarget target = plan.get(index);
            BlockPos pos = target.pos();
            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if (state.isOf(Blocks.VOID_AIR)) {
                // The chunk counts as loaded but its blocks have not arrived (or were dropped on the way to a far-off
                // part of the plot): nothing to compare with, and no reason to reopen finished blocks.
                continue;
            }
            // Both ways: a finished block someone removed is built again, and a block someone placed by hand while
            // the agent was paused counts as done without a full comparison when the agent starts.
            boolean complete = StateMatcher.isComplete(state, target.state());
            if (status == BuildPlan.Status.DONE && !complete) {
                plan.markPending(index);
                this.finalVerified.clear(index);
                this.finalCheckDone = false;
                if (reopened++ == 0) {
                    firstReopened = pos;
                }
            } else if (status != BuildPlan.Status.DONE && complete) {
                this.markDone(index);
            }
        }
        if (reopened > 0 && this.config().verboseLogging) {
            LitematicaAgentClient.LOGGER.info("Rescan reopened {} finished targets, first {} (world {})", reopened,
                    firstReopened.toShortString(), world.getBlockState(firstReopened));
        }
    }

    /** A player, mob or armor stand where the block goes: that clears up by itself, so it is no reason to give up. */
    private boolean isOccupiedByEntity(BlockPos pos) {
        return !this.world().getOtherEntities(this.player(), new Box(pos),
                entity -> entity.isAlive() && entity instanceof LivingEntity).isEmpty();
    }

    /**
     * Breaks the next leftover helper block, top first: the agent may be standing on its own pillar and digs down like
     * a player would.
     *
     * @return true if a break task was started
     */
    private boolean startHelperCleanup() {
        BuildPlan<BuildTarget> plan = this.runtime.plan();
        List<Long> remaining = new ArrayList<>(this.helpers);
        remaining.sort(Comparator.comparingInt((Long key) -> BlockPos.fromLong(key).getY()).reversed());
        for (Long helper : remaining) {
            BlockPos pos = BlockPos.fromLong(helper);
            if (!this.world().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                if (this.tick >= this.travelRetryAt) {
                    if (this.moveTo(Goal.near(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 12.0))) {
                        this.start(new MoveOnlyTask(pos));
                    } else {
                        this.travelFailed(pos);
                    }
                }
                return true;
            }
            if (this.helperRemovalAttempts.getOrDefault(helper, 0) >= MAX_HELPER_REMOVAL_ATTEMPTS
                    || this.helperRemovalRetryAt.getOrDefault(helper, 0L) > this.tick) {
                continue;
            }
            if (plan.indexOf(helper) >= 0 || this.isAirLike(pos)) {
                this.helpers.remove(helper);
                continue;
            }
            this.action = Chat.tr("action.cleanup");
            this.start(new BreakTask(-1, pos));
            return true;
        }
        return false;
    }

    /**
     * Once the build has moved up, pillars well below the current layer are no longer needed to reach anything and are
     * removed right away instead of standing around until the end. The column under the player stays.
     */
    private void removeHelpersBelow(int layerY) {
        BlockPos feet = this.player().getBlockPos();
        BlockBox site = this.buildSite();
        List<Long> below = new ArrayList<>();
        for (Long helper : this.helpers) {
            BlockPos pos = BlockPos.fromLong(helper);
            // Only at the build itself: helpers along the way (a step over a wall on the way to the storage) are
            // needed again on the next trip, and walking back and forth for them costs more than leaving them for
            // the final cleanup.
            if (pos.getY() < layerY - HELPER_KEEP_BELOW && (pos.getX() != feet.getX() || pos.getZ() != feet.getZ())
                    && site.contains(pos.getX(), site.getMinY(), pos.getZ())) {
                below.add(helper);
            }
        }
        below.sort(Comparator.comparingInt((Long key) -> BlockPos.fromLong(key).getY()).reversed());
        this.pendingHelperRemovals.addAll(below);
    }

    /** The ground area of the build plus a few blocks around it, where helpers only serve this build. */
    private BlockBox buildSite() {
        if (this.buildSite == null) {
            BuildPlan<BuildTarget> plan = this.runtime.plan();
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (int i = 0; i < plan.size(); i++) {
                long pos = plan.position(i);
                minX = Math.min(minX, PosUtil.x(pos));
                maxX = Math.max(maxX, PosUtil.x(pos));
                minZ = Math.min(minZ, PosUtil.z(pos));
                maxZ = Math.max(maxZ, PosUtil.z(pos));
            }
            this.buildSite = plan.size() == 0 ? new BlockBox(0, 0, 0, 0, 0, 0)
                    : new BlockBox(minX - SITE_MARGIN, 0, minZ - SITE_MARGIN, maxX + SITE_MARGIN, 0, maxZ + SITE_MARGIN);
        }
        return this.buildSite;
    }

    private long nearestPendingHelperRemoval(Vec3d from) {
        long best = this.pendingHelperRemovals.iterator().next();
        double bestDistance = Double.MAX_VALUE;
        for (long helper : this.pendingHelperRemovals) {
            double distance = from.squaredDistanceTo(Vec3d.ofCenter(BlockPos.fromLong(helper)));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = helper;
            }
        }
        return best;
    }

    private void finishTask(AgentTask.Result result) {
        AgentTask finished = this.task;
        this.task = null;
        this.heldSneak = false;
        // A task aborted for taking too long has no reason of its own.
        String reason = finished.failureReason().isEmpty() && this.taskTimedOut ? "timeout" : finished.failureReason();
        if (result == AgentTask.Result.FAILED && this.config().verboseLogging) {
            LitematicaAgentClient.LOGGER.info("{} failed ({}): {} | player={}", finished.getClass().getSimpleName(),
                    reason, finished.describe().getString(), this.player().getBlockPos().toShortString());
        }
        if (this.runtime == null) {
            this.manager.markDirty();
            return;
        }
        BuildPlan<BuildTarget> plan = this.runtime.plan();

        if (finished instanceof PlaceTask place) {
            if (place.index() < 0) {
                if (result == AgentTask.Result.SUCCESS) {
                    // A helper block gives its neighbours something to be placed against as well.
                    this.unparkAround(place.position());
                }
                if (result == AgentTask.Result.FAILED && place.supportTargetIndex() >= 0) {
                    long key = place.position().asLong();
                    if (this.isAirLike(place.position())) {
                        this.helpers.remove(key);
                        List<Long> support = this.supportHelpers.get(place.supportTargetIndex());
                        if (support != null) {
                            support.remove(key);
                            if (support.isEmpty()) {
                                this.supportHelpers.remove(place.supportTargetIndex());
                            }
                        }
                    }
                    this.defer(place.supportTargetIndex(), reason);
                }
                return;
            }
            if (result == AgentTask.Result.SUCCESS) {
                plan.resetAttempts(place.index());
                this.failedPlacementFrom.remove(place.index());
                BuildTarget target = plan.get(place.index());
                if (StateMatcher.isComplete(this.world().getBlockState(target.pos()), target.state())) {
                    this.markDone(place.index());
                    List<Long> used = this.supportHelpers.remove(place.index());
                    if (used != null) {
                        for (int i = used.size() - 1; i >= 0; i--) {
                            this.pendingHelperRemovals.add(used.get(i));
                        }
                    }
                }
            } else if (!place.isMissingItem()) {
                if (isPositionalFailure(reason)) {
                    this.failedPlacementFrom.put(place.index(), this.player().getBlockPos().asLong());
                }
                this.defer(place.index(), reason);
            }
        } else if (finished instanceof InteractTask interact) {
            if (result == AgentTask.Result.FAILED) {
                this.defer(interact.index(), reason);
            }
        } else if (finished instanceof FluidTask fluid) {
            if (result == AgentTask.Result.SUCCESS) {
                plan.resetAttempts(fluid.index());
                BuildTarget target = plan.get(fluid.index());
                if (StateMatcher.isComplete(this.world().getBlockState(target.pos()), target.state())) {
                    this.markDone(fluid.index());
                }
            } else if (fluid.ranOutOfWater()) {
                // Not the target's fault, so no attempt is used up. The water nearby did not work out for refilling,
                // so for a while only full buckets count: the next decision fetches them from the storage (or reports
                // them missing) instead of trying the same source again and again.
                this.ignoreRenewableUntil = this.tick + 20L * 60;
                this.renewableCheckTick = -1L;
            } else {
                this.defer(fluid.index(), reason);
            }
        } else if (finished instanceof BreakTask breakTask) {
            if (result == AgentTask.Result.FAILED) {
                if (breakTask.index() >= 0) {
                    this.defer(breakTask.index(), reason);
                } else {
                    long key = breakTask.pos().asLong();
                    int attempts = this.helperRemovalAttempts.merge(key, 1, Integer::sum);
                    // Could not even get there: try again much later, while building goes on, instead of walking
                    // back and forth to it every few seconds.
                    boolean noWay = reason.startsWith("path_") || "unreachable".equals(reason);
                    this.helperRemovalRetryAt.put(key, this.tick + (noWay ? 20L * 60 : 40L) * attempts);
                    this.failures.put(key, "helper_not_removed");
                }
            }
        } else if (finished instanceof ContainerTask container) {
            if (container.homeRequired() != null) {
                ContainerRecord record = container.homeRequired();
                StorageHomes.Route home = StorageHomes.request(this.manager.worldData(), record, this.runtime.session().id);
                this.manager.awaitStorageHome();
                this.manager.save();
                this.manager.pause(this, "pause.storage_home_required", List.of(String.valueOf(home.suggestedNumber),
                        record.x + " " + record.y + " " + record.z, container.failureReason()));
                return;
            }
            if (container.mode() == ContainerTask.Mode.WITHDRAW) {
                if (container.transferredAny()) {
                    this.emptyRestockAttempts = 0;
                } else if (++this.emptyRestockAttempts >= 3) {
                    this.manager.pause(this, "pause.storage_access_failed", List.of(container.failureReason().isEmpty()
                            ? Chat.tr("reason.missing_material").getString() : container.failureReason()));
                    return;
                }
            }
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
        } else if (finished instanceof MoveOnlyTask move) {
            if (result == AgentTask.Result.FAILED) {
                this.travelFailed(move.destination);
            } else {
                this.travelFailures = 0;
            }
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
            if (this.parked.get(index)) {
                // Placed by someone else (or noticed by the rescan) while it waited.
                this.parked.clear(index);
                this.parkedCount--;
            }
            this.unparkAround(BlockPos.fromLong(plan.position(index)));
            this.completedWhileActive++;
            this.finalVerified.clear(index);
            this.consecutiveFailures = 0;
            this.skippedStalls = 0;
            this.inventoryRecoveryAttempts = 0;
            this.failures.remove(plan.position(index));
            this.failedPlacementFrom.remove(index);
        }
    }

    private void markFailed(int index, String reason) {
        this.runtime.plan().markFailed(index);
        this.failures.put(this.runtime.plan().position(index), reason);
        // Blocks the player deliberately chose to skip, blocks missing from storage, and wrong blocks in SKIP mode
        // are expected gaps, not signs that something is systematically broken - they must not stall the agent.
        // "Skip all" means it for every single block: only the agent itself getting nowhere (path after path
        // failing) still counts there, a block that cannot be built is simply the next one skipped.
        AgentConfig.WrongBlockMode mode = this.config().wrongBlockMode;
        boolean expected = "skipped".equals(reason) || "missing_material".equals(reason)
                || ("wrong_block".equals(reason) && mode != AgentConfig.WrongBlockMode.ASK)
                || (mode == AgentConfig.WrongBlockMode.SKIP && !isMovementFailure(reason));
        if (!expected) {
            this.consecutiveFailures++;
        }
        BuildTarget target = this.runtime.plan().get(index);
        LitematicaAgentClient.LOGGER.info("Giving up on {} at {}: {}", target.state(), target.pos().toShortString(), reason);
    }

    /**
     * @return true if the agent paused because too many blocks in a row were given up on without a single success
     *         in between (see {@link #consecutiveFailures})
     */
    private boolean checkStalled() {
        if (this.consecutiveFailures < STALL_FAILURE_COUNT) {
            return false;
        }
        if (this.recoverFailedTargets()) {
            return false;
        }
        if (this.config().wrongBlockMode == AgentConfig.WrongBlockMode.SKIP && ++this.skippedStalls < MAX_SKIPPED_STALLS) {
            // "Skip all" means exactly that: the blocks that kept failing stay skipped (a recovery round or the next
            // resume tries them again) and the agent carries on with everything else. Only when run after run fails
            // with nothing built at all in between is something really wrong (stuck, cut off): then it still stops.
            LitematicaAgentClient.LOGGER.info("Skipping {} blocks that failed in a row and carrying on ({}/{})",
                    this.consecutiveFailures, this.skippedStalls, MAX_SKIPPED_STALLS);
            this.consecutiveFailures = 0;
            this.movement.forgetFailures();
            return false;
        }
        this.pauseForFailures("pause.no_progress", this.consecutiveFailures);
        return true;
    }

    /** Two fresh attempts at the remaining failures; another round needs real net progress first. */
    private boolean recoverFailedTargets() {
        BuildPlan<BuildTarget> plan = this.runtime.plan();
        if (!this.recoveryBudget.available(plan.doneCount())) {
            return false;
        }
        if (this.lastRecoveryTick >= 0L && this.tick - this.lastRecoveryTick < RECOVERY_SPACING_TICKS) {
            // Rounds right after each other see the same situation; in between, water settles, helpers come off,
            // other blocks give new support. Meanwhile everything else goes on, or the agent waits.
            this.action = Chat.tr("action.waiting");
            return true;
        }
        int count = plan.retryFailed(index -> {
            long key = plan.position(index);
            String reason = this.failures.getOrDefault(key, "");
            return !this.skipped.contains(key) && !"wrong_block".equals(reason)
                    && !"missing_material".equals(reason) && !"missing_tool".equals(reason);
        });
        if (count == 0) {
            return false;
        }
        int round = this.recoveryBudget.acquire();
        this.lastRecoveryTick = this.tick;
        this.consecutiveFailures = 0;
        this.finalCheckDone = false;
        this.movement.forgetFailures();
        LitematicaAgentClient.LOGGER.info("Retrying {} failed targets, recovery round {}/{} at {}/{} complete", count,
                round, MAX_RECOVERY_ROUNDS, plan.doneCount(), plan.size());
        return true;
    }

    /**
     * @param key   {@code pause.no_progress} while failing in a row, {@code pause.blocks_failed} once nothing else is left
     * @param count the blocks the message is about
     */
    private void pauseForFailures(String key, int count) {
        List<String> recent = new ArrayList<>();
        List<Map.Entry<Long, String>> entries = new ArrayList<>(this.failures.entrySet());
        for (int i = entries.size() - 1; i >= 0 && recent.size() < STALL_SUMMARY_COUNT; i--) {
            Map.Entry<Long, String> entry = entries.get(i);
            recent.add(BlockPos.fromLong(entry.getKey()).toShortString() + " ("
                    + Chat.tr("reason." + AgentManager.reasonKey(entry.getValue())).getString() + ")");
        }
        this.manager.pause(this, key, List.of(String.valueOf(count), String.join(", ", recent)));
    }

    /**
     * Tries the target again later; after too many attempts it becomes a {@link #problem}.
     *
     * @return true if the agent paused so the player can decide
     */
    private boolean defer(int index, String reason) {
        BuildPlan<BuildTarget> plan = this.runtime.plan();
        if (this.config().verboseLogging) {
            LitematicaAgentClient.LOGGER.info("Deferring {} ({}), attempt {}", plan.get(index).pos().toShortString(), reason,
                    plan.attempts(index) + 1);
        }
        if (plan.attempts(index) + 1 >= MAX_ATTEMPTS) {
            if (this.isOccupiedByEntity(plan.get(index).pos()) && plan.attempts(index) < MAX_ATTEMPTS * 3) {
                plan.defer(index, this.tick, 100L);
                return false;
            }
            if (this.runtime.session().strategy == BuildStrategy.BLOCKS) {
                // With a block queue the support may simply be of a block type that is not built yet.
                this.notYet.add(plan.position(index));
                plan.resetAttempts(index);
                return false;
            }
            return this.problem(index, reason, null, "pause.cannot_place", this.problemArgs(plan.get(index), reason));
        }
        plan.defer(index, this.tick, 40L * (plan.attempts(index) + 1));
        return false;
    }

    /**
     * A target the agent cannot finish by itself. In "ask for approval" mode the agent pauses, so the player can do it
     * by hand and retry, or skip it; in the other modes it is skipped right away.
     *
     * @param tool the missing tool, if that is the problem
     * @return true if the agent paused
     */
    private boolean problem(int index, String reason, @Nullable StateMatcher.Tool tool, String pauseKey, List<String> args) {
        if (this.config().wrongBlockMode == AgentConfig.WrongBlockMode.ASK) {
            this.pendingDecision = new PendingDecision(DecisionKind.PROBLEM, index, this.runtime.plan().position(index), tool);
            this.manager.pause(this, pauseKey, args);
            return true;
        }
        this.markFailed(index, reason);
        return false;
    }

    private List<String> problemArgs(BuildTarget target, String reason) {
        return List.of(blockName(target), target.pos().toShortString(),
                Chat.tr("reason." + AgentManager.reasonKey(reason)).getString());
    }

    private static String blockName(BuildTarget target) {
        return target.state().getBlock().getName().getString();
    }

    private static String toolName(StateMatcher.Tool tool) {
        return Chat.tr("tool." + tool.name().toLowerCase(Locale.ROOT)).getString();
    }

    /** What is placed for a target: farmland and dirt paths start as dirt and are converted with a tool later. */
    private static BuildTarget placement(BuildTarget target) {
        BlockState state = StateMatcher.placementState(target.state());
        return state == target.state() ? target : new BuildTarget(target.pos(), state, target.item(), 1);
    }

    private boolean mayBreak(BlockPos pos, BlockState state) {
        long key = pos.asLong();
        if (this.placedByAgent.contains(key) || this.helpers.contains(key) || this.approvedBreaks.contains(key)) {
            return true;
        }
        Block block = state.getBlock();
        boolean vegetation = block instanceof PlantBlock && !(block instanceof CropBlock) && !(block instanceof StemBlock)
                && !(block instanceof SweetBerryBushBlock) && !(block instanceof NetherWartBlock);
        return vegetation || block instanceof SnowBlock || block instanceof VineBlock;
    }

    public @Nullable DecisionKind pendingDecision() {
        return this.pendingDecision == null ? null : this.pendingDecision.kind();
    }

    /**
     * Records the player's answer: approve breaks a wrong block or retries a problem (e.g. after placing it by hand or
     * adding the tool), reject skips the block (or everything that needs the missing tool).
     *
     * @return false if nothing was waiting for an answer
     */
    boolean answerPendingDecision(boolean approved) {
        PendingDecision decision = this.pendingDecision;
        if (decision == null) {
            return false;
        }
        this.pendingDecision = null;
        if (!approved) {
            if (decision.tool() != null) {
                this.skippedTools.add(decision.tool());
            } else {
                this.skipped.add(decision.pos());
            }
        } else if (decision.kind() == DecisionKind.WRONG_BLOCK) {
            this.approvedBreaks.add(decision.pos());
        } else if (this.runtime != null) {
            this.runtime.plan().resetAttempts(decision.index());
        }
        return true;
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
        if (!target.state().canPlaceAt(world, pos)
                || (this.runtime.plan().attempts(index) < 2 && this.hasPendingNeighbour(pos))) {
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
        BlockPos chain = this.nextChainHelper(pos, helper, helperState);
        return chain != null && this.placeHelper(index, new BuildTarget(chain, helperState, helper, 1));
    }

    /**
     * The chain length actually used: the player's setting, or {@link #UNLIMITED_SUPPORT_CHAIN} in place of
     * {@link AgentConfig#SUPPORT_CHAIN_UNLIMITED} so the search still terminates.
     */
    private int effectiveSupportChain() {
        int configured = this.config().maxSupportChain;
        return configured == AgentConfig.SUPPORT_CHAIN_UNLIMITED ? UNLIMITED_SUPPORT_CHAIN : configured;
    }

    /**
     * Scaffolding for blocks with nothing near to build against, like a floating island on a pond, or the upper
     * layers of a {@link BuildStrategy#LAYERS_TOP_DOWN} build with nothing built below them yet: the block of the
     * shortest chain of helpers (at most {@link #effectiveSupportChain()} long) that connects the target to something
     * solid, which can be placed right now. One block per call; the next decision continues from it, so the chain
     * grows from the solid end towards the target. The helpers are removed once the target stands.
     */
    private @Nullable BlockPos nextChainHelper(BlockPos target, Item helper, BlockState helperState) {
        ClientPlayerEntity player = this.player();
        ClientWorld world = this.world();
        int maxChain = this.effectiveSupportChain();
        BlockPos pillar = this.nextPillarHelper(target, maxChain);
        if (pillar != null) {
            return pillar;
        }
        // A long chain is normally a near-straight pillar with few branches; give the search enough budget to
        // actually reach that far instead of giving up early on cell count alone.
        int searchBudget = Math.min(MAX_CHAIN_SEARCH_CAP, Math.max(MIN_CHAIN_SEARCH, maxChain * 30));
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, Integer> depth = new HashMap<>();
        for (Direction direction : HELPER_DIRECTIONS) {
            BlockPos start = target.offset(direction);
            if (this.isHelperSpot(start)) {
                depth.put(start, 1);
                queue.add(start);
            }
        }
        while (!queue.isEmpty() && depth.size() < searchBudget) {
            BlockPos cell = queue.poll();
            // Cheap test first; the placement simulation only for cells that have something to click against.
            if (hasClickableNeighbour(world, cell, target)
                    && this.solver.isFeasible(player, new BuildTarget(cell, helperState, helper, 1))) {
                return cell;
            }
            int length = depth.get(cell);
            if (length >= maxChain) {
                continue;
            }
            for (Direction direction : HELPER_DIRECTIONS) {
                BlockPos next = cell.offset(direction);
                if (!next.equals(target) && !depth.containsKey(next) && this.isHelperSpot(next)) {
                    depth.put(next, length + 1);
                    queue.add(next);
                }
            }
        }
        return null;
    }

    /**
     * A long scaffold is nearly always a straight column standing on the ground, under the target or next to it. The
     * breadth-first search spreads in every direction and runs out of budget after a dozen blocks, so columns are
     * looked for directly: going down from the cell under (or beside) the target through free helper spots to
     * something solid, at most {@code maxChain} cells. Returns the lowest cell still to be built, so the column grows
     * from the ground up, one helper per decision like any chain.
     */
    private @Nullable BlockPos nextPillarHelper(BlockPos target, int maxChain) {
        ClientWorld world = this.world();
        for (Direction side : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos cell = target.offset(side);
            BlockPos lowest = null;
            int length = 0;
            while (length < maxChain && this.isHelperSpot(cell)) {
                lowest = cell;
                length++;
                cell = cell.down();
            }
            if (lowest == null) {
                continue;
            }
            // cell is the first one below the free column: it has to hold the column's lowest helper.
            BlockState base = world.getBlockState(cell);
            if (!base.isReplaceable() && !base.getOutlineShape(world, cell).isEmpty()) {
                return lowest;
            }
        }
        return null;
    }

    private static boolean hasClickableNeighbour(ClientWorld world, BlockPos cell, BlockPos except) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = cell.offset(direction);
            if (!neighbour.equals(except)) {
                BlockState state = world.getBlockState(neighbour);
                if (!state.isReplaceable() && !state.getOutlineShape(world, neighbour).isEmpty()) {
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
        if (this.runtime == null || this.helpers.contains(pos.asLong())) {
            return false;
        }
        ClientWorld world = this.world();
        if (!world.getBlockState(pos).isReplaceable()) {
            return false;
        }
        // Grass under any opaque block turns into dirt, and a lost grass block cannot be made again in survival.
        if (world.getBlockState(pos.down()).getBlock() instanceof SpreadableBlock) {
            return false;
        }
        int index = this.runtime.plan().indexOf(pos.asLong());
        if (index < 0) {
            return true;
        }
        // Water that is not poured yet: a helper may stand there for now. Water comes after the solid blocks of its
        // layer, and a helper still in the way then is broken like any of the agent's own blocks.
        return WaterPlacement.isWaterTarget(this.runtime.plan().get(index).state()) && !WaterPlacement.hasSource(world, pos);
    }

    private boolean placeHelper(int targetIndex, BuildTarget helperTarget) {
        BlockPos spot = null;
        boolean trick = false;
        if (this.solver.findFromPlayer(this.player(), helperTarget, this.reach(), false) == null) {
            PlacementSolver.StandSpot standSpot = this.solver.findStandSpot(this.player(), helperTarget, this.reach(),
                    this.canFly(), this.standable(), STAND_SPOT_CANDIDATES);
            if (standSpot == null) {
                return false;
            }
            spot = standSpot.feet();
            trick = standSpot.option().lookTrick();
        }
        long key = helperTarget.posLong();
        this.helpers.add(key);
        this.supportHelpers.computeIfAbsent(targetIndex, ignored -> new ArrayList<>()).add(key);
        this.start(new PlaceTask(-1, helperTarget, spot, trick, targetIndex));
        return true;
    }

    private @Nullable Item helperItem() {
        ClientPlayerEntity player = this.player();
        Item fallback = null;
        for (String id : this.config().helperBlocks) {
            Item item = Registries.ITEM.get(Identifier.tryParse(id));
            if (item == null || !(item instanceof BlockItem)) {
                continue;
            }
            if (player.isInCreativeMode()) {
                return item;
            }
            int spare = this.spareHelpers(item, InventoryHelper.count(player.getInventory(), item));
            if (spare > 0 && !this.neededForBuild(item)) {
                return item;
            }
            if (spare > 0 && fallback == null) {
                fallback = item;
            }
        }
        return fallback;
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
                total += this.spareHelpers(item, InventoryHelper.count(player.getInventory(), item));
            }
        }
        return total;
    }

    private boolean neededForBuild(Item item) {
        return this.runtime != null && this.runtime.remainingMaterials().containsKey(item);
    }

    /**
     * How many of the carried blocks may serve as helpers: all of a cheap block the build does not use, only the surplus
     * of a building material. Stone, for example, comes back as cobblestone when a helper is mined again.
     */
    private int spareHelpers(Item item, int carried) {
        if (this.runtime == null) {
            return carried;
        }
        return Math.max(0, carried - this.runtime.remainingMaterials().getOrDefault(item, 0));
    }

    private final class PillarHelper implements MovementController.HelperBlocks {
        @Override
        public int available() {
            return BuildAgent.this.helperCount();
        }

        @Override
        public boolean placeOnTop(BlockPos below) {
            Item item = BuildAgent.this.helperItem();
            if (item == null || !InventoryHelper.select(BuildAgent.this.client, item) || !BuildAgent.this.placeCooldownReady()
                    || !RotationController.isKnownToServer(BuildAgent.this.player())) {
                return false;
            }
            BlockHitResult hit = Aiming.crosshair(BuildAgent.this.player(), BuildAgent.this.reach());
            if (hit == null || !hit.getBlockPos().equals(below) || hit.getSide() != Direction.UP || !Aiming.isClearOfEdges(hit)) {
                return false;
            }
            BuildAgent.this.clickBlock(hit);
            BuildAgent.this.helpers.add(below.up().asLong());
            BuildAgent.this.placeCooldown = BuildAgent.this.config().placeDelayTicks();
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

    /**
     * Leaves a container out of restock planning for a while: called when a visit could not reach it, so the very
     * next restock does not just propose the same unreachable chest again.
     */
    public void markContainerUnreachable(String key) {
        this.unreachableContainers.put(key, System.currentTimeMillis() + CONTAINER_UNREACHABLE_MILLIS);
        this.unreachableContainers.values().removeIf(until -> until < System.currentTimeMillis());
    }

    private Set<String> unreachableKeys() {
        if (this.unreachableContainers.isEmpty()) {
            return Set.of();
        }
        long now = System.currentTimeMillis();
        Set<String> keys = new HashSet<>();
        for (Map.Entry<String, Long> entry : this.unreachableContainers.entrySet()) {
            if (entry.getValue() > now) {
                keys.add(entry.getKey());
            }
        }
        return keys;
    }

    private boolean startRestock(Set<Item> required) {
        ClientPlayerEntity player = this.player();
        PlayerInventory inventory = player.getInventory();
        StorageDatabase storage = this.manager.storage();
        LinkedHashMap<String, Integer> needs = new LinkedHashMap<>();

        for (Item item : required) {
            // A required material can be outside the lookahead window. It must still trigger a real withdrawal.
            needs.put(itemId(item), 1);
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
                needs.merge(itemId(entry.getKey()), missingAmount, Math::max);
            }
        }
        needs.entrySet().removeIf(entry -> entry.getValue() <= 0);
        // Buckets are refilled on site, so a few carried along do for any amount of water; never one per source.
        String waterBucket = itemId(Items.WATER_BUCKET);
        if (needs.containsKey(waterBucket) || required.contains(Items.WATER_BUCKET)) {
            int carried = InventoryHelper.count(inventory, Items.WATER_BUCKET);
            needs.put(waterBucket, Math.max(1, WaterPlacement.BUCKETS_CARRIED - carried));
        }

        boolean helperTopUp = false;
        if (this.config().useHelperBlocks && !player.getAbilities().allowFlying && this.helperCount() < HELPER_STOCK / 2) {
            // A cheap block the build does not use first; a building material only from what the storage has beyond
            // the build's own need.
            String chosen = null;
            for (String id : this.config().helperBlocks) {
                Item item = Registries.ITEM.get(Identifier.tryParse(id));
                int need = this.runtime.remainingMaterials().getOrDefault(item, 0);
                if (needs.containsKey(id) || storage.total(id) <= need) {
                    continue;
                }
                if (need == 0) {
                    chosen = id;
                    break;
                }
                if (chosen == null) {
                    chosen = id;
                }
            }
            if (chosen != null) {
                needs.put(chosen, HELPER_STOCK);
                helperTopUp = true;
            }
        }

        Vec3d pos = player.getEntityPos();
        WithdrawalPlanner.Plan withdrawal = WithdrawalPlanner.plan(storage, this.dimensionId(), pos.x, pos.y, pos.z, needs,
                InventoryHelper.freeSlots(inventory), BuildAgent::maxStackSize, this.unreachableKeys());
        // A trip is only worth making for a genuine reason: something from `required`, or an actual helper-block top
        // up (see the call sites in decide()). Upcoming-lookahead materials alone must not justify it - almost every
        // tick has some of those still missing, which would otherwise start a new trip forever without ever fixing
        // whatever `required` being empty was actually trying to address.
        boolean anyRequired = helperTopUp;
        for (WithdrawalPlanner.Visit visit : withdrawal.visits()) {
            for (Item item : required) {
                if (visit.take().getOrDefault(itemId(item), 0) > 0) {
                    anyRequired = true;
                }
            }
        }
        if (!anyRequired || withdrawal.visits().isEmpty()) {
            if (!required.isEmpty() && InventoryHelper.freeSlots(inventory) == 0
                    && required.stream().anyMatch(item -> storage.total(itemId(item)) > 0)) {
                if (this.inventoryRecoveryAttempts++ < 2 && this.startDeposit()) {
                    return true;
                }
                this.manager.pause(this, "pause.inventory_full", List.of());
                return true;
            }
            // Recent navigation failures do not mean the material is absent. Keep targets pending until backoff
            // expires; a failed warehouse route is separately escalated by ContainerTask's home fallback.
            Set<String> excluded = this.unreachableKeys();
            for (ContainerRecord container : storage.containers()) {
                if (excluded.contains(container.key()) && container.dimension.equals(this.dimensionId())
                        && required.stream().anyMatch(item -> container.count(itemId(item)) > 0)) {
                    this.action = Chat.tr("action.waiting");
                    return true;
                }
            }
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
                    Math.max(1, InventoryHelper.freeSlots(this.player().getInventory())), BuildAgent::maxStackSize, this.unreachableKeys());
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
            if (slot.inventory != player.getInventory() && slot.hasStack() && record.allows(slot.getIndex())) {
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

    public String storageHomeCommand(ContainerRecord container) {
        return StorageHomes.command(this.manager.worldData(), container);
    }

    public boolean moveTo(Goal goal) {
        PathOptions options = this.movement.defaultOptions(this.player(), this.helperCount()).withMaxFall(this.safeFallDistance());
        return this.movement.moveTo(this.player(), goal, options, null, this.helpers::contains);
    }

    /**
     * The biggest drop the agent may path through in one go. A* still prefers smaller, cheaper drops or a walk-around
     * when one exists (see {@code COST_DESCEND_PER_BLOCK}); this is only the ceiling for when nothing else is left -
     * e.g. climbing back down a tower with no helper blocks to pillar with. Vanilla fall damage is one point (half a
     * heart) per block beyond 3, so the ceiling grows with spare health, leaving a safety margin above the point where
     * the agent would otherwise pause or disconnect over low health, and never risks more than a large, capped fall.
     */
    private int safeFallDistance() {
        if (this.canFly()) {
            return 3;
        }
        float spare = this.player().getHealth() - this.config().emergencyHealth - SAFE_FALL_HEALTH_BUFFER;
        return (int) Math.max(3, Math.min(SAFE_FALL_MAX, 3 + Math.floor(spare)));
    }

    public MovementController.Status tickMovement() {
        return this.movement.tick(this.player(), this.helperBlocks);
    }

    /**
     * Stand position for placing a block. Without flight, positions in the air are accepted as a second choice when
     * they can be reached by building a pillar of helper blocks below them.
     */
    private @Nullable PlacementSolver.StandSpot findPlaceSpot(int index, BuildTarget target) {
        ClientPlayerEntity player = this.player();
        Predicate<BlockPos> standable = this.standable().and(feet -> !this.failedFromHere(index, feet));
        PlacementSolver.StandSpot spot = this.solver.findStandSpot(player, target, this.reach(), this.canFly(),
                standable, STAND_SPOT_CANDIDATES);
        if (spot != null || this.canFly()) {
            return spot;
        }
        int helpers = this.helperCount();
        if (helpers <= 0) {
            return null;
        }
        Predicate<BlockPos> pillar = this.pillarStandable(helpers).and(feet -> !this.failedFromHere(index, feet));
        return this.solver.findStandSpot(player, target, this.reach(), false, pillar, STAND_SPOT_CANDIDATES);
    }

    /**
     * Whether a placement of the target already failed from {@code feet} or right next to it (see
     * {@link #isPositionalFailure}). The whole neighbourhood counts: a stand spot one block over is reached without
     * really moving, and the click then fails exactly like before - six times in a row, until the block is given up.
     */
    public boolean failedFromHere(int index, BlockPos feet) {
        Long failedFrom = this.failedPlacementFrom.get(index);
        return failedFrom != null && BlockPos.fromLong(failedFrom).getSquaredDistance(feet) <= 2.0;
    }

    /** Beyond walking-over distance (horizontally): reached as a journey first, see the candidate loop in decide(). */
    public static boolean isFarAway(BlockPos pos, ClientPlayerEntity player) {
        double dx = pos.getX() + 0.5 - player.getX();
        double dz = pos.getZ() + 0.5 - player.getZ();
        return dx * dx + dz * dz > FAR_TARGET_DISTANCE * FAR_TARGET_DISTANCE;
    }

    /** The agent could not get to the block at all: no path, or the approach failed on the way. */
    private static boolean isMovementFailure(String reason) {
        return reason.startsWith("path_") || "unreachable".equals(reason);
    }

    /**
     * A placement that failed from where the player stood (the planned click did not reproduce with the real
     * crosshair, or the server did not confirm it) must not be tried from exactly there again: that just repeats the
     * same failure until the target is given up on. The next attempt looks for another stand position instead.
     */
    private static boolean isPositionalFailure(String reason) {
        return "crosshair_mismatch".equals(reason) || "no_placement_option".equals(reason) || "aim_timeout".equals(reason)
                || "placement_not_confirmed".equals(reason);
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
                if ((flags & NavWorld.PASSABLE) == 0 || (flags & (NavWorld.WATER | NavWorld.DANGER | NavWorld.OCCUPIED)) != 0) {
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
            // Head under water is no place to work from: the player floats up out of it, or runs out of air.
            if ((headFlags & NavWorld.WATER) != 0) {
                return false;
            }
            // A slab-like low block raises the feet half a block, so standing there needs one more block of headroom
            // (see NavWorld.RAISED) - without this, a spot the pathfinder can never actually reach got proposed anyway.
            if ((feetFlags & NavWorld.RAISED) != 0
                    && (nav.flags(feet.getX(), feet.getY() + 2, feet.getZ()) & NavWorld.PASSABLE) == 0) {
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

    /**
     * Feet positions to get within reach of something, including elevated ones reached by pillaring up with the
     * helper blocks on hand - not only for placing blocks, but for anything that just needs a clear view, such as
     * mining a block or opening a container standing on top of a wall.
     */
    public Predicate<BlockPos> approachStandable() {
        int helpers = this.helperCount();
        return this.canFly() || helpers <= 0 ? this.standable() : this.pillarStandable(helpers);
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
        this.placeCooldown = this.config().placeDelayTicks();
        this.lastClickTick = this.tick;
    }

    /**
     * Right-click with the item in hand and no block target (a bucket, a lily pad). The server casts its own ray with
     * the view direction sent along, which is why callers only use it once that direction is known to the server.
     */
    public void useItem() {
        ClientPlayerEntity player = this.player();
        ActionResult result = this.interactionManager().interactItem(player, Hand.MAIN_HAND);
        if (result instanceof ActionResult.Success success && success.swingSource() == ActionResult.SwingSource.CLIENT) {
            player.swingHand(Hand.MAIN_HAND);
        }
        this.placeCooldown = this.config().placeDelayTicks();
        this.lastClickTick = this.tick;
    }

    /** Under water with less than half the air left (and nothing that lets the player breathe there). */
    private boolean needsAir(ClientPlayerEntity player) {
        return player.isSubmergedInWater() && player.getAir() < player.getMaxAir() / 2 && !player.isInCreativeMode()
                && !StatusEffectUtil.hasWaterBreathing(player);
    }

    public boolean hasItem(Item item) {
        ClientPlayerEntity player = this.player();
        return player.isInCreativeMode() || InventoryHelper.count(player.getInventory(), item) > 0;
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
        this.finalCheckDone = false;
        this.finalVerified.clear();
        this.placedByAgent.remove(pos.asLong());
        this.helpers.remove(pos.asLong());
        this.helperRemovalAttempts.remove(pos.asLong());
        this.helperRemovalRetryAt.remove(pos.asLong());
        this.failures.remove(pos.asLong());
    }

    private boolean hasTool(StateMatcher.Tool tool) {
        ClientPlayerEntity player = this.player();
        return player.isInCreativeMode() || InventoryHelper.find(player.getInventory(), tool::matches) >= 0;
    }

    /**
     * Puts a matching tool into the main hand; in creative mode one is taken from the creative inventory.
     */
    public boolean selectTool(StateMatcher.Tool tool) {
        ClientPlayerEntity player = this.player();
        if (player.isInCreativeMode()) {
            return InventoryHelper.select(this.client, tool.creativeItem());
        }
        if (tool.matches(player.getMainHandStack())) {
            return true;
        }
        int slot = InventoryHelper.find(player.getInventory(), tool::matches);
        if (slot < 0) {
            return false;
        }
        InventoryHelper.selectSlot(this.client, slot);
        return tool.matches(player.getMainHandStack());
    }

    /**
     * Fetches one matching tool (any hoe, any shovel, ...) from the known storage.
     */
    private boolean startToolFetch(StateMatcher.Tool tool) {
        StorageDatabase storage = this.manager.storage();
        Vec3d pos = this.player().getEntityPos();
        for (Map.Entry<String, Integer> entry : storage.totals().entrySet()) {
            Item item = Registries.ITEM.get(Identifier.tryParse(entry.getKey()));
            if (entry.getValue() <= 0 || !tool.matches(new ItemStack(item))) {
                continue;
            }
            LinkedHashMap<String, Integer> needs = new LinkedHashMap<>();
            needs.put(entry.getKey(), 1);
            WithdrawalPlanner.Plan plan = WithdrawalPlanner.plan(storage, this.dimensionId(), pos.x, pos.y, pos.z, needs,
                    Math.max(1, InventoryHelper.freeSlots(this.player().getInventory())), BuildAgent::maxStackSize, this.unreachableKeys());
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
                    Math.max(1, InventoryHelper.freeSlots(this.player().getInventory())), BuildAgent::maxStackSize, this.unreachableKeys());
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
        if (this.runtime == null) {
            return 0;
        }
        // With a block selection only the selected blocks are going to be built.
        Set<Item> selection = this.runtime.session().strategy == BuildStrategy.BLOCKS ? null : this.selection();
        int remaining = selection == null ? this.runtime.plan().pendingCount() : this.runtime.pendingTargets(selection);
        return this.eta.remainingMinutes(remaining);
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
                    .append(" pending=").append(plan.pendingCount()).append(" helpers=").append(this.helpers.size())
                    .append(" parked=").append(this.parkedCount).append(" decide=")
                    .append(this.decisions == 0 ? 0 : this.decisionMicrosTotal / this.decisions).append('/')
                    .append(this.decisionMicrosMax).append("us");
            int shown = 0;
            for (int index : plan.upcoming(12)) {
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
    private void travelFailed(BlockPos destination) {
        this.movement.stop();
        if (++this.travelFailures >= 3) {
            this.manager.pause(this, "pause.unreachable_area", List.of(destination.toShortString()));
            return;
        }
        this.movement.forgetFailures();
        this.travelRetryAt = this.tick + 40L * this.travelFailures;
    }

    private final class MoveOnlyTask implements AgentTask {
        private final BlockPos destination;

        private MoveOnlyTask(BlockPos destination) {
            this.destination = destination;
        }

        @Override
        public Result tick(BuildAgent agent) {
            MovementController.Status status = agent.tickMovement();
            if (status == MovementController.Status.MOVING) {
                return Result.RUNNING;
            }
            return status == MovementController.Status.ARRIVED ? Result.SUCCESS : Result.FAILED;
        }

        @Override
        public int timeoutTicks() {
            // A journey across a plot of hundreds of blocks takes minutes; the movement's own stuck detection ends a
            // journey that gets nowhere, the time limit only catches one that never ends.
            return 20 * 60 * 10;
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

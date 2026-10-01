package net.clanimg.litematica_agent.movement;

import net.clanimg.litematica_agent.LitematicaAgentClient;
import net.clanimg.litematica_agent.inventory.InventoryHelper;
import net.clanimg.litematica_agent.placement.Aiming;
import net.clanimg.litematica_agent.movement.pathing.AStarPathfinder;
import net.clanimg.litematica_agent.movement.pathing.Goal;
import net.clanimg.litematica_agent.movement.pathing.MoveType;
import net.clanimg.litematica_agent.movement.pathing.PathNode;
import net.clanimg.litematica_agent.movement.pathing.PathOptions;
import net.clanimg.litematica_agent.movement.pathing.PathResult;
import net.clanimg.litematica_agent.movement.pathing.PosUtil;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;

/**
 * Walks, jumps, swims, climbs and flies along a path using only movement keys and the camera.
 */
public final class MovementController {
    private static final int STUCK_CHECK_TICKS = 20;
    private static final double HOVER_ABOVE_NODE = 0.12;
    private static final double HOVER_UNDER_CEILING = 0.08;
    /** Horizontal flight keeps 0.91 of its speed a tick: the glide after letting go is about ten ticks' worth. */
    private static final double FLIGHT_GLIDE_TICKS = 10.0;
    private static final int MAX_REPATHS = 4;
    private static final int PATH_NODE_BUDGET = 20_000;
    /** Path segments per journey: with a search budget of a few dozen blocks per segment, this covers a large plot. */
    private static final int MAX_SEGMENTS = 256;
    private static final long NO_PILLAR_MILLIS = 60_000L;
    private static final long AVOID_MILLIS = 60_000L;
    /** Longest time for breaking one block below the feet (stone by hand takes about 8 seconds). */
    private static final int MAX_DIG_TICKS = 20 * 20;

    public enum Status {
        IDLE,
        MOVING,
        ARRIVED,
        FAILED
    }

    /**
     * Lets the movement controller place helper blocks when it has to pillar up.
     */
    public interface HelperBlocks {
        int available();

        /** Selects a helper block and clicks the top face of {@code below}. */
        boolean placeOnTop(BlockPos below);

        /** Mines the helper block at {@code pos} (below the feet); returns true once it is gone. */
        boolean mine(BlockPos pos);
    }

    private final RotationController rotation;
    private final Set<Long> noFlyColumns = new HashSet<>();
    /** Positions where pillaring failed, with the time until which they are avoided. */
    private final Map<Long, Long> noPillarUntil = new HashMap<>();
    /** Feet positions the player got stuck on the way to, with the time until which paths avoid them. */
    private final Map<Long, Long> avoidUntil = new HashMap<>();

    private @Nullable Goal goal;
    private @Nullable PathOptions baseOptions;
    private @Nullable List<PathNode> path;
    private int index;
    private Status status = Status.IDLE;
    private String failure = "";
    private boolean partialPath;

    private int ticksSinceProgressCheck;
    private double lastDistance = Double.MAX_VALUE;
    private int stuckLevel;
    private int recoveryTicks;
    private int repaths;
    private long lastEmptyPathLog;
    private long lastOffPathLog;
    private int segments;
    private int flyToggleTicks;
    private int flyToggleAge = -1;
    private int pillarTicks;
    private int digTicks;
    private @Nullable BlockPos passage;
    private int passageTicks;
    private int passageClickCooldown;
    private long lastExpirySweep;
    private boolean sprintAllowed = true;
    private @Nullable LongPredicate virtualSolid;
    private @Nullable LongPredicate helperPositions;

    public MovementController(RotationController rotation) {
        this.rotation = rotation;
    }

    public void setSprintAllowed(boolean allowed) {
        this.sprintAllowed = allowed;
    }

    public Status getStatus() {
        return this.status;
    }

    public String getFailure() {
        return this.failure;
    }

    public boolean isMoving() {
        return this.status == Status.MOVING;
    }

    /**
     * The next planned steps, for optional in-world visualization; empty while idle.
     */
    public List<PathNode> upcomingPath(int maxNodes) {
        if (this.path == null || this.status != Status.MOVING) {
            return List.of();
        }
        int from = Math.min(this.index, this.path.size());
        int to = Math.min(this.path.size(), from + maxNodes);
        return this.path.subList(from, to);
    }

    public void markNoFly(BlockPos pos) {
        this.noFlyColumns.add(PosUtil.packColumn(pos.getX(), pos.getZ()));
    }

    public boolean isNoFly(int x, int z) {
        return this.noFlyColumns.contains(PosUtil.packColumn(x, z));
    }

    public PathOptions defaultOptions(ClientPlayerEntity player, int helperBlocks) {
        // Still in the air counts too: while the abilities are out of step for a moment (a server re-sending them),
        // walking options from a hover position have no first step at all.
        boolean canFly = player.getAbilities().allowFlying || player.getAbilities().flying;
        PathOptions options = canFly ? PathOptions.flying() : PathOptions.walking();
        return options.withHelperBlocks(canFly ? 0 : helperBlocks)
                .withMaxNodes(PATH_NODE_BUDGET)
                .withNoFly(column -> this.noFlyColumns.contains(column))
                .withNoPillar(pos -> this.noPillarUntil.getOrDefault(pos, 0L) > System.currentTimeMillis())
                .withForbidden(pos -> this.avoidUntil.getOrDefault(pos, 0L) > System.currentTimeMillis());
    }

    /**
     * Plans a path and starts moving. Returns false if not even a partial path exists.
     */
    public boolean moveTo(ClientPlayerEntity player, Goal goal, PathOptions options, @Nullable LongPredicate virtualSolid) {
        return this.moveTo(player, goal, options, virtualSolid, null);
    }

    public boolean moveTo(ClientPlayerEntity player, Goal goal, PathOptions options, @Nullable LongPredicate virtualSolid,
                          @Nullable LongPredicate helperPositions) {
        this.helperPositions = helperPositions;
        this.goal = goal;
        this.baseOptions = options;
        this.virtualSolid = virtualSolid;
        this.repaths = 0;
        this.segments = 0;
        return this.plan(player);
    }

    private boolean plan(ClientPlayerEntity player) {
        if (this.goal == null || this.baseOptions == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - this.lastExpirySweep >= 10_000L) {
            this.noPillarUntil.values().removeIf(until -> until <= now);
            this.avoidUntil.values().removeIf(until -> until <= now);
            this.lastExpirySweep = now;
        }
        World world = player.getEntityWorld();
        BlockPos feet = feetPos(player);
        LongPredicate solid = this.virtualSolid == null ? PathOptions.NONE : this.virtualSolid;
        LongPredicate helperPositions = this.helperPositions == null ? PathOptions.NONE : this.helperPositions;
        WorldNavAdapter nav = new WorldNavAdapter(world, solid, helperPositions);
        PathResult result = new AStarPathfinder(nav).find(feet.getX(), feet.getY(), feet.getZ(), this.goal, this.baseOptions);
        if (result.isEmpty() && player.getAbilities().flying && this.baseOptions.canFly()) {
            // Hovering in the layer being built, the start cell may be misjudged (walled in by fresh blocks, the body
            // half in a block): one cell higher the open air begins - search from there and fly up first.
            PathResult above = new AStarPathfinder(nav).find(feet.getX(), feet.getY() + 1, feet.getZ(), this.goal, this.baseOptions);
            if (!above.isEmpty()) {
                List<PathNode> nodes = new java.util.ArrayList<>(above.nodes().size() + 1);
                nodes.add(new PathNode(feet.getX(), feet.getY(), feet.getZ(), MoveType.START));
                nodes.add(new PathNode(feet.getX(), feet.getY() + 1, feet.getZ(), MoveType.FLY_UP));
                nodes.addAll(above.nodes().subList(1, above.nodes().size()));
                result = new PathResult(nodes, above.reachedGoal(), above.expanded());
            }
        }

        this.resetProgress();
        this.digTicks = 0;
        this.passage = null;
        this.passageTicks = 0;
        this.failure = "";
        if (result.reachedGoal() && result.nodes().size() <= 1) {
            this.path = result.nodes();
            this.index = 0;
            this.status = Status.ARRIVED;
            return true;
        }
        if (result.isEmpty()) {
            this.path = null;
            this.status = Status.FAILED;
            this.failure = "no_path";
            this.explainEmptyPath(player, feet, result, this.baseOptions, nav);
            return false;
        }
        this.path = result.nodes();
        this.partialPath = !result.reachedGoal();
        this.index = 0;
        this.status = Status.MOVING;
        return true;
    }

    /**
     * A search that could not even leave the start is worth a line in the log: what the player stands in, what is
     * around, and whether flight was allowed - it says where the agent walled itself in (or thinks it did).
     */
    private void explainEmptyPath(ClientPlayerEntity player, BlockPos feet, PathResult result, PathOptions options,
                                  WorldNavAdapter nav) {
        long now = System.currentTimeMillis();
        if (now - this.lastEmptyPathLog < 2000L) {
            return;
        }
        this.lastEmptyPathLog = now;
        StringBuilder around = new StringBuilder();
        int[][] offsets = {{0, 0, 0}, {0, 1, 0}, {0, 2, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] o : offsets) {
            around.append(String.format(java.util.Locale.ROOT, " (%d,%d,%d)=%d", o[0], o[1], o[2],
                    nav.flags(feet.getX() + o[0], feet.getY() + o[1], feet.getZ() + o[2])));
        }
        LitematicaAgentClient.LOGGER.info("No path from {} (pos {}): expanded={} canFly={} allowFlying={} flying={} noFlyHere={} "
                        + "goalHeuristic={} flags(dx,dy,dz)={}", feet.toShortString(), player.getEntityPos(), result.expanded(),
                options.canFly(), player.getAbilities().allowFlying, player.getAbilities().flying,
                options.noFlyColumn().test(PosUtil.packColumn(feet.getX(), feet.getZ())),
                this.goal == null ? -1 : this.goal.heuristic(feet.getX(), feet.getY(), feet.getZ()), around);
    }

    /** Forgets spots where moving failed, e.g. when the player resumes after fixing something. */
    public void forgetFailures() {
        this.noPillarUntil.clear();
        this.avoidUntil.clear();
    }

    public void stop() {
        this.path = null;
        this.goal = null;
        this.status = Status.IDLE;
        this.pillarTicks = 0;
        this.flyToggleTicks = 0;
        this.passage = null;
        this.passageTicks = 0;
        InputController.clear();
    }

    public Status tick(ClientPlayerEntity player, HelperBlocks helpers) {
        if (this.status != Status.MOVING || this.path == null) {
            return this.status;
        }
        InputController.clear();

        if (this.pillarTicks > 0) {
            this.tickPillar(player, helpers);
            return this.status;
        }

        this.advanceIndex(player);
        if (this.index >= this.path.size() - 1) {
            BlockPos feet = feetPos(player);
            if (this.partialPath && this.goal != null && !this.goal.isGoal(feet.getX(), feet.getY(), feet.getZ())) {
                // Long distances are covered in several path segments; that is progress, not a failure.
                if (++this.segments > MAX_SEGMENTS || !this.plan(player)) {
                    this.fail("no_path");
                }
                return this.status;
            }
            this.status = Status.ARRIVED;
            this.settle(player);
            return this.status;
        }

        PathNode next = this.path.get(this.index + 1);
        if (this.isOffPath(player)) {
            long now = System.currentTimeMillis();
            if (now - this.lastOffPathLog >= 1000L) {
                this.lastOffPathLog = now;
                LitematicaAgentClient.LOGGER.info("Off the path at {} (flying={}, velocity={}): next {} to {} {} {}, index {}/{}, "
                                + "replans so far {}", player.getEntityPos(), player.getAbilities().flying, player.getVelocity(),
                        next.move(), next.x(), next.y(), next.z(), this.index, this.path.size(), this.repaths);
            }
            this.replan(player);
            return this.status;
        }

        if (this.openPassage(player, next)) {
            return this.status;
        }

        if (next.move().isFlying() && !player.getAbilities().allowFlying && !player.getAbilities().flying) {
            this.markNoFly(player.getBlockPos());
            this.replan(player);
            return this.status;
        }

        if (this.recoveryTicks > 0) {
            this.recoveryTicks--;
            this.applyRecovery(player, next);
            return this.status;
        }

        if (next.move() == MoveType.PILLAR) {
            this.pillarTicks = 1;
            this.tickPillar(player, helpers);
            return this.status;
        }

        if (next.move() == MoveType.DIG_DOWN) {
            if (this.tickDigDown(player, next, helpers)) {
                // Breaking a block by hand takes seconds without the player moving; that is progress, not being stuck.
                this.resetProgress();
            } else {
                this.checkStuck(player, next);
            }
            return this.status;
        }

        boolean keepFlying = player.getAbilities().flying && !this.isNoFly(next.x(), next.z());
        if (next.move().isFlying() || keepFlying) {
            this.tickFlying(player, next);
        } else {
            this.tickWalking(player, next);
        }

        this.checkStuck(player, next);
        return this.status;
    }

    private void tickWalking(ClientPlayerEntity player, PathNode next) {
        if (player.getAbilities().flying) {
            // Land before walking so flight cannot be lost mid-air, e.g. above a street between two plots.
            InputController.setSneak(true);
            this.steerHorizontally(player, next, false);
            return;
        }

        Vec3d target = center(next);
        double distance = horizontalDistance(player.getEntityPos(), target);
        boolean last = this.index + 1 >= this.path.size() - 1;

        switch (next.move()) {
            case CLIMB_UP, SWIM_UP -> {
                InputController.setJump(true);
                this.steerHorizontally(player, next, distance > 0.2);
            }
            case CLIMB_DOWN -> this.steerHorizontally(player, next, distance > 0.25);
            case ASCEND -> {
                this.steerHorizontally(player, next, true);
                if ((distance < 1.4 && player.isOnGround()) || player.horizontalCollision) {
                    InputController.setJump(true);
                }
            }
            default -> {
                boolean press = !last || distance > 0.25;
                if (last && distance < 0.7 && horizontalSpeed(player) > 0.12) {
                    press = false;
                }
                this.steerHorizontally(player, next, press);
                if (player.horizontalCollision && player.isOnGround()) {
                    InputController.setJump(true);
                }
                if (player.isTouchingWater()) {
                    InputController.setJump(true);
                }
                InputController.setSprint(this.sprintAllowed && this.straightNodesAhead() >= 4 && !player.isTouchingWater());
            }
        }
    }

    /** Stops before a closed door, aims, clicks, and waits for the observed open state. */
    private boolean openPassage(ClientPlayerEntity player, PathNode next) {
        BlockPos feet = new BlockPos(next.x(), next.y(), next.z());
        BlockPos closed = null;
        for (BlockPos candidate : new BlockPos[]{feet, feet.up()}) {
            BlockState state = player.getEntityWorld().getBlockState(candidate);
            if (WorldNavAdapter.isOpenablePassage(state) && !state.get(Properties.OPEN)) {
                closed = candidate;
                break;
            }
        }
        if (closed == null) {
            this.passage = null;
            this.passageTicks = 0;
            return false;
        }
        if (player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(closed)) > 3.5 * 3.5) {
            return false;
        }
        if (!closed.equals(this.passage)) {
            this.passage = closed;
            this.passageTicks = 0;
            this.passageClickCooldown = 0;
        }
        if (++this.passageTicks > 80) {
            // Locked/protected doors must not cause an endless click loop.
            this.avoid(player, next);
            this.replan(player);
            return true;
        }
        if (--this.passageClickCooldown > 0) {
            return true;
        }
        Aiming.Aim aim = Aiming.aimAt(player, closed, player.getEyePos(), 3.5);
        if (aim == null) {
            return true;
        }
        InventoryHelper.selectNeutralSlot(player);
        BlockHitResult hit = Aiming.crosshair(player, 3.5);
        if (hit != null && hit.getBlockPos().equals(closed) && !player.isSneaking()) {
            this.rotation.hold(player);
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.interactionManager != null && RotationController.isKnownToServer(player)) {
                client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
                player.swingHand(Hand.MAIN_HAND);
                this.passageClickCooldown = 12;
            }
        } else {
            this.rotation.setTarget(aim.yaw(), aim.pitch());
        }
        return true;
    }

    private void tickFlying(ClientPlayerEntity player, PathNode next) {
        if (!player.getAbilities().flying) {
            this.toggleFlight(player);
            return;
        }
        this.flyToggleTicks = 0;
        // Clearly above the node's block, never touching it: in survival, feet on a block end the flight, and from the
        // edge of the layer just built that is a fall of a hundred blocks. Under a ceiling two blocks up (building a
        // layer below the finished ones) the 1.8 blocks of body only fit low: at 0.25 the head is in the ceiling and
        // the player stands, W pressed, with no speed at all.
        Vec3d pos = player.getEntityPos();
        double hover = this.hoverAbove(player, next, pos);
        boolean underCeiling = hover < HOVER_ABOVE_NODE;
        Vec3d target = new Vec3d(next.x() + 0.5, next.y() + hover, next.z() + 0.5);
        Vec3d velocity = player.getVelocity();
        double distance = horizontalDistance(pos, target);
        World world = player.getEntityWorld();
        if (underCeiling && hasCollision(world, next.x(), next.y() - 1, next.z())
                && hasCollision(world, MathHelper.floor(pos.x), next.y() - 1, MathHelper.floor(pos.z))) {
            // Two blocks of room over a floor: no height to hover at that fits, so land and walk it like a player
            // would (survival flight ends on touching down; the next flying step turns it back on).
            InputController.setSneak(true);
            this.steerHorizontally(player, next, false);
            return;
        }
        // The end of a partial segment is not a stop: the next segment carries straight on, so no braking there.
        boolean last = this.index + 1 >= this.path.size() - 1 && !this.partialPath;

        double towards = distance < 1.0E-4 ? 0.0
                : (velocity.x * (target.x - pos.x) + velocity.z * (target.z - pos.z)) / distance;
        double brakeDistance = towards * (last ? 6.0 : 2.5);
        double dy = target.y - pos.y;
        double vy = velocity.y;
        // Height first when well off it: a sloped path descends a block per block, faster than flight sinks while
        // rushing ahead, and the player ended up metres above the path, off it, replanned and gave up as "stuck".
        boolean press = distance > 0.2 && distance > brakeDistance && Math.abs(dy) < 2.0
                && !(underCeiling && pos.y > next.y() + 0.2);
        // Flight glides: at 0.3 blocks a tick it coasts three more blocks (friction 0.91 a tick), swung past the stand
        // spot, back, past again, and after twenty ticks without getting closer counted as stuck. Reverse thrust
        // whenever the glide would overshoot the last node.
        boolean brake = last && towards > 0.04 && towards * FLIGHT_GLIDE_TICKS > distance + 0.15;
        boolean facing = this.steerHorizontally(player, next, press && !brake);
        if (brake && facing) {
            InputController.setBack(true);
        }

        // A tick of shift sinks about 0.375 blocks with its glide; under a ceiling that has to start early enough to
        // get the head below it, and rising back up waits for the usual slack so it does not see-saw.
        double sinkSlack = underCeiling ? 0.06 : 0.25;
        // Never below the node's level while moving sideways: even a hair of the body in the row below catches on
        // every block down there (the ledge in front, the layer being built).
        boolean belowLedge = pos.y < next.y() + 0.02;
        if (belowLedge || (dy > 0.25 && dy > vy * 3.0)) {
            InputController.setJump(true);
        } else if (dy < -sinkSlack && dy < vy * 3.0) {
            InputController.setSneak(true);
        }
        InputController.setSprint(this.sprintAllowed && !last && this.straightNodesAhead() >= 5);
    }

    /** Falling with flight allowed but off: double-tap space until it is back on, before anything else. */
    public void recoverFlight(ClientPlayerEntity player) {
        InputController.setSneak(false);
        this.toggleFlight(player);
    }

    /** How high above the node to hover: low when a block two above the node or the player would catch the head. */
    private double hoverAbove(ClientPlayerEntity player, PathNode next, Vec3d pos) {
        World world = player.getEntityWorld();
        int ceiling = next.y() + 2;
        if (hasCollision(world, next.x(), ceiling, next.z())
                || hasCollision(world, MathHelper.floor(pos.x), ceiling, MathHelper.floor(pos.z))) {
            return HOVER_UNDER_CEILING;
        }
        return HOVER_ABOVE_NODE;
    }

    private static boolean hasCollision(World world, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty();
    }

    /**
     * Double-taps space: press, release, press within the 7 tick window of the vanilla client. Counted once per
     * tick, however often it is asked (the movement and the agent's fall recovery may both ask in one tick).
     */
    private void toggleFlight(ClientPlayerEntity player) {
        if (this.flyToggleAge != player.age) {
            this.flyToggleAge = player.age;
            this.flyToggleTicks++;
        }
        int step = this.flyToggleTicks % 8;
        InputController.setJump(step == 1 || step == 3);
    }

    private void tickPillar(ClientPlayerEntity player, HelperBlocks helpers) {
        if (this.path == null || this.index + 1 >= this.path.size()) {
            this.pillarTicks = 0;
            return;
        }
        PathNode from = this.path.get(this.index);
        PathNode to = this.path.get(this.index + 1);
        BlockPos helperPos = new BlockPos(from.x(), from.y(), from.z());
        Vec3d pos = player.getEntityPos();

        this.pillarTicks++;
        if (this.pillarTicks > 60 || helpers.available() <= 0) {
            if (this.pillarTicks > 60) {
                // Something at this spot prevents pillaring (low ceiling, someone in the way...): plan around it for a
                // while. It may be possible again later, so the spot is not blocked for good.
                this.noPillarUntil.put(PosUtil.pack(from.x(), from.y(), from.z()), System.currentTimeMillis() + NO_PILLAR_MILLIS);
            }
            this.pillarTicks = 0;
            this.fail("pillar_failed");
            return;
        }

        if (!player.getEntityWorld().getBlockState(helperPos).getCollisionShape(player.getEntityWorld(), helperPos).isEmpty()) {
            if (pos.y >= to.y() - 0.05 && player.isOnGround()) {
                this.pillarTicks = 0;
                this.index++;
                this.resetProgress();
            }
            return;
        }

        this.rotation.setTarget(player.getYaw(), 90.0F);
        double distance = horizontalDistance(pos, center(from));
        if (distance > 0.2) {
            this.steerHorizontally(player, from, true);
            return;
        }
        if (player.isOnGround()) {
            // Look down first: the block is placed mid-jump with a view direction the server already knows.
            if (player.getPitch() > 89.5F && RotationController.isKnownToServer(player)) {
                InputController.setJump(true);
            }
            return;
        }
        if (pos.y > from.y() + 1.02 && player.getPitch() > 80.0F) {
            helpers.placeOnTop(helperPos.down());
        }
    }

    /**
     * @return true while the block below is being mined
     */
    private boolean tickDigDown(ClientPlayerEntity player, PathNode next, HelperBlocks helpers) {
        BlockPos below = new BlockPos(next.x(), next.y(), next.z());
        if (horizontalDistance(player.getEntityPos(), center(next)) > 0.25) {
            this.steerHorizontally(player, next, true);
            return false;
        }
        this.rotation.setTarget(player.getYaw(), 90.0F);
        if (player.getPitch() <= 80.0F || !player.isOnGround()) {
            return false;
        }
        if (++this.digTicks > MAX_DIG_TICKS) {
            this.fail("dig_failed");
            return false;
        }
        helpers.mine(below);
        return true;
    }

    /** Turns towards the node and, if asked, walks; returns true once the player faces it closely enough to push. */
    private boolean steerHorizontally(ClientPlayerEntity player, PathNode next, boolean pressForward) {
        Vec3d pos = player.getEntityPos();
        Vec3d target = center(next);
        double dx = target.x - pos.x;
        double dz = target.z - pos.z;
        if (dx * dx + dz * dz < 1.0E-4) {
            return true;
        }
        float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        this.rotation.setTarget(yaw, next.move().isFlying() ? 10.0F : 15.0F);
        float diff = Math.abs(MathHelper.wrapDegrees(yaw - player.getYaw()));
        boolean facing = diff < 50.0F;
        if (pressForward && facing) {
            InputController.setForward(true);
        }
        return facing;
    }

    private void advanceIndex(ClientPlayerEntity player) {
        if (this.path == null) {
            return;
        }
        // In flight the player covers several nodes a tick and swings wide of their centres; looking only a few nodes
        // ahead with walking precision lost the path at speed, replanned four times and gave up as "stuck".
        boolean flying = player.getAbilities().flying;
        int limit = Math.min(this.path.size() - 1, this.index + (flying ? 12 : 4));
        for (int i = limit; i > this.index; i--) {
            PathNode node = this.path.get(i);
            boolean isLast = i == this.path.size() - 1;
            if (isAt(player, node, isLast ? (flying ? 0.45 : 0.3) : flying ? 0.9 : 0.45)) {
                this.index = i;
                this.digTicks = 0;
                this.resetProgress();
                // Progress along the path: replans so far were detours, not signs of being stuck.
                this.repaths = 0;
                return;
            }
        }
    }

    private static boolean isAt(ClientPlayerEntity player, PathNode node, double tolerance) {
        Vec3d pos = player.getEntityPos();
        if (horizontalDistance(pos, center(node)) > tolerance) {
            return false;
        }
        double dy = pos.y - node.y();
        if (node.move().isFlying() || player.getAbilities().flying) {
            return Math.abs(dy) < 1.0;
        }
        return dy > -0.4 && dy < 0.9 && (player.isOnGround() || player.isTouchingWater() || player.isClimbing());
    }

    private boolean isOffPath(ClientPlayerEntity player) {
        if (this.path == null) {
            return false;
        }
        Vec3d pos = player.getEntityPos();
        double best = Double.MAX_VALUE;
        int from = Math.max(0, this.index - 1);
        int to = Math.min(this.path.size() - 1, this.index + 2);
        for (int i = from; i <= to; i++) {
            PathNode node = this.path.get(i);
            best = Math.min(best, pos.distanceTo(new Vec3d(node.x() + 0.5, node.y(), node.z() + 0.5)));
        }
        return best > 3.0;
    }

    private void checkStuck(ClientPlayerEntity player, PathNode next) {
        this.ticksSinceProgressCheck++;
        if (this.ticksSinceProgressCheck < STUCK_CHECK_TICKS) {
            return;
        }
        this.ticksSinceProgressCheck = 0;
        double distance = player.getEntityPos().distanceTo(new Vec3d(next.x() + 0.5, next.y(), next.z() + 0.5));
        if (distance < this.lastDistance - 0.1) {
            this.lastDistance = distance;
            this.stuckLevel = 0;
            return;
        }
        this.lastDistance = distance;
        this.stuckLevel++;
        if (this.stuckLevel <= 3) {
            this.recoveryTicks = player.getAbilities().flying ? 5 : 10;
        } else {
            this.stuckLevel = 0;
            this.avoid(player, next);
            this.replan(player);
        }
    }

    /**
     * The step did not work out although the path allowed it, so something is in the way that the path does not know
     * about (a player, a block shape it misjudged...). That position is avoided for a while and the new path goes
     * around it instead of trying the same step again.
     */
    private void avoid(ClientPlayerEntity player, PathNode next) {
        World world = player.getEntityWorld();
        BlockPos feet = new BlockPos(next.x(), next.y(), next.z());
        Vec3d pos = player.getEntityPos();
        float wantedYaw = (float) (MathHelper.atan2(next.z() + 0.5 - pos.z, next.x() + 0.5 - pos.x) * (180.0 / Math.PI)) - 90.0F;
        LitematicaAgentClient.LOGGER.info("Stuck before {} to {}: below {}, feet {}, head {} - avoiding it for a while "
                        + "(player {} velocity {} yaw {} wanted {} flying={} keys {} index {}/{} partial={})",
                next.move(), feet.toShortString(), world.getBlockState(feet.down()), world.getBlockState(feet),
                world.getBlockState(feet.up()), pos, player.getVelocity(), player.getYaw(), wantedYaw,
                player.getAbilities().flying, InputController.describe(), this.index,
                this.path == null ? 0 : this.path.size(), this.partialPath);
        this.avoidUntil.put(PosUtil.pack(next.x(), next.y(), next.z()), System.currentTimeMillis() + AVOID_MILLIS);
    }

    private void applyRecovery(ClientPlayerEntity player, PathNode next) {
        this.steerHorizontally(player, next, true);
        if (player.getAbilities().flying) {
            // No jumping over things in the air: ten ticks of space is a climb of three blocks, straight off the path.
            switch (this.stuckLevel) {
                case 1 -> InputController.setLeft(true);
                case 2 -> InputController.setRight(true);
                default -> InputController.setBack(true);
            }
            return;
        }
        switch (this.stuckLevel) {
            case 1 -> InputController.setJump(true);
            case 2 -> InputController.setLeft(true);
            default -> InputController.setRight(true);
        }
    }

    private boolean replan(ClientPlayerEntity player) {
        this.repaths++;
        if (this.repaths > MAX_REPATHS) {
            this.fail("stuck");
            return false;
        }
        return this.plan(player);
    }

    private void fail(String reason) {
        if (this.path != null && this.index + 1 < this.path.size()) {
            PathNode next = this.path.get(this.index + 1);
            LitematicaAgentClient.LOGGER.info("Movement failed ({}): next step {} to {} {} {}", reason, next.move(),
                    next.x(), next.y(), next.z());
        } else {
            LitematicaAgentClient.LOGGER.info("Movement failed ({}): at the end of a path of {} nodes (partial={}, repaths={}, "
                            + "segments={}), last node {}", reason, this.path == null ? 0 : this.path.size(), this.partialPath,
                    this.repaths, this.segments, this.path == null || this.path.isEmpty() ? "-" : this.path.get(this.path.size() - 1));
        }
        this.status = Status.FAILED;
        this.failure = reason;
        this.path = null;
        InputController.clear();
    }

    private void settle(ClientPlayerEntity player) {
        InputController.clear();
    }

    private void resetProgress() {
        this.ticksSinceProgressCheck = 0;
        this.lastDistance = Double.MAX_VALUE;
        this.stuckLevel = 0;
        this.recoveryTicks = 0;
    }

    private int straightNodesAhead() {
        if (this.path == null || this.index + 1 >= this.path.size()) {
            return 0;
        }
        PathNode first = this.path.get(this.index + 1);
        PathNode previous = this.path.get(this.index);
        int dx = first.x() - previous.x();
        int dz = first.z() - previous.z();
        int count = 0;
        for (int i = this.index + 1; i < this.path.size(); i++) {
            PathNode node = this.path.get(i);
            PathNode before = this.path.get(i - 1);
            if (node.x() - before.x() != dx || node.z() - before.z() != dz || node.y() != before.y()) {
                break;
            }
            count++;
        }
        return count;
    }

    public static BlockPos feetPos(ClientPlayerEntity player) {
        Vec3d pos = player.getEntityPos();
        return BlockPos.ofFloored(pos.x, pos.y + 0.2, pos.z);
    }

    private static Vec3d center(PathNode node) {
        return new Vec3d(node.x() + 0.5, node.y(), node.z() + 0.5);
    }

    private static double horizontalDistance(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double horizontalSpeed(ClientPlayerEntity player) {
        Vec3d velocity = player.getVelocity();
        return Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
    }
}

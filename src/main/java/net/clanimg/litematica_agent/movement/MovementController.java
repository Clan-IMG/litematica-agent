package net.clanimg.litematica_agent.movement;

import net.clanimg.litematica_agent.LitematicaAgentClient;
import net.clanimg.litematica_agent.movement.pathing.AStarPathfinder;
import net.clanimg.litematica_agent.movement.pathing.Goal;
import net.clanimg.litematica_agent.movement.pathing.MoveType;
import net.clanimg.litematica_agent.movement.pathing.PathNode;
import net.clanimg.litematica_agent.movement.pathing.PathOptions;
import net.clanimg.litematica_agent.movement.pathing.PathResult;
import net.clanimg.litematica_agent.movement.pathing.PosUtil;
import net.minecraft.client.network.ClientPlayerEntity;
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
    private static final int MAX_REPATHS = 4;
    private static final int PATH_NODE_BUDGET = 20_000;
    private static final int MAX_SEGMENTS = 64;
    private static final long NO_PILLAR_MILLIS = 60_000L;
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
    private int segments;
    private int flyToggleTicks;
    private int pillarTicks;
    private int digTicks;
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

    public void markNoFly(BlockPos pos) {
        this.noFlyColumns.add(PosUtil.packColumn(pos.getX(), pos.getZ()));
    }

    public boolean isNoFly(int x, int z) {
        return this.noFlyColumns.contains(PosUtil.packColumn(x, z));
    }

    public PathOptions defaultOptions(ClientPlayerEntity player, int helperBlocks) {
        boolean canFly = player.getAbilities().allowFlying;
        PathOptions options = canFly ? PathOptions.flying() : PathOptions.walking();
        return options.withHelperBlocks(canFly ? 0 : helperBlocks)
                .withMaxNodes(PATH_NODE_BUDGET)
                .withNoFly(column -> this.noFlyColumns.contains(column))
                .withNoPillar(pos -> this.noPillarUntil.getOrDefault(pos, 0L) > System.currentTimeMillis());
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
        World world = player.getEntityWorld();
        BlockPos feet = feetPos(player);
        LongPredicate solid = this.virtualSolid == null ? PathOptions.NONE : this.virtualSolid;
        LongPredicate helperPositions = this.helperPositions == null ? PathOptions.NONE : this.helperPositions;
        PathResult result = new AStarPathfinder(new WorldNavAdapter(world, solid, helperPositions))
                .find(feet.getX(), feet.getY(), feet.getZ(), this.goal, this.baseOptions);

        this.resetProgress();
        this.digTicks = 0;
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
            return false;
        }
        this.path = result.nodes();
        this.partialPath = !result.reachedGoal();
        this.index = 0;
        this.status = Status.MOVING;
        return true;
    }

    /** Forgets spots where moving failed, e.g. when the player resumes after fixing something. */
    public void forgetFailures() {
        this.noPillarUntil.clear();
    }

    public void stop() {
        this.path = null;
        this.goal = null;
        this.status = Status.IDLE;
        this.pillarTicks = 0;
        this.flyToggleTicks = 0;
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
            this.replan(player);
            return this.status;
        }

        if (next.move().isFlying() && !player.getAbilities().allowFlying) {
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

    private void tickFlying(ClientPlayerEntity player, PathNode next) {
        if (!player.getAbilities().flying) {
            this.toggleFlight();
            return;
        }
        this.flyToggleTicks = 0;
        Vec3d target = new Vec3d(next.x() + 0.5, next.y() + 0.1, next.z() + 0.5);
        Vec3d pos = player.getEntityPos();
        Vec3d velocity = player.getVelocity();
        double distance = horizontalDistance(pos, target);
        boolean last = this.index + 1 >= this.path.size() - 1;

        double towards = distance < 1.0E-4 ? 0.0
                : (velocity.x * (target.x - pos.x) + velocity.z * (target.z - pos.z)) / distance;
        double brakeDistance = towards * (last ? 6.0 : 2.5);
        boolean press = distance > 0.2 && distance > brakeDistance;
        this.steerHorizontally(player, next, press);

        double dy = target.y - pos.y;
        double vy = velocity.y;
        if (dy > 0.25 && dy > vy * 3.0) {
            InputController.setJump(true);
        } else if (dy < -0.25 && dy < vy * 3.0) {
            InputController.setSneak(true);
        }
        InputController.setSprint(this.sprintAllowed && !last && this.straightNodesAhead() >= 5);
    }

    /**
     * Double-taps space: press, release, press within the 7 tick window of the vanilla client.
     */
    private void toggleFlight() {
        this.flyToggleTicks++;
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
            InputController.setJump(true);
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

    private void steerHorizontally(ClientPlayerEntity player, PathNode next, boolean pressForward) {
        Vec3d pos = player.getEntityPos();
        Vec3d target = center(next);
        double dx = target.x - pos.x;
        double dz = target.z - pos.z;
        if (dx * dx + dz * dz < 1.0E-4) {
            return;
        }
        float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        this.rotation.setTarget(yaw, next.move().isFlying() ? 10.0F : 15.0F);
        float diff = Math.abs(MathHelper.wrapDegrees(yaw - player.getYaw()));
        if (pressForward && diff < 50.0F) {
            InputController.setForward(true);
        }
    }

    private void advanceIndex(ClientPlayerEntity player) {
        if (this.path == null) {
            return;
        }
        int limit = Math.min(this.path.size() - 1, this.index + 4);
        for (int i = limit; i > this.index; i--) {
            PathNode node = this.path.get(i);
            boolean isLast = i == this.path.size() - 1;
            if (isAt(player, node, isLast ? 0.3 : 0.45)) {
                this.index = i;
                this.digTicks = 0;
                this.resetProgress();
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
            return Math.abs(dy) < 0.6;
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
            this.recoveryTicks = 10;
        } else {
            this.stuckLevel = 0;
            this.replan(player);
        }
    }

    private void applyRecovery(ClientPlayerEntity player, PathNode next) {
        this.steerHorizontally(player, next, true);
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
            LitematicaAgentClient.LOGGER.info("Movement failed ({})", reason);
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

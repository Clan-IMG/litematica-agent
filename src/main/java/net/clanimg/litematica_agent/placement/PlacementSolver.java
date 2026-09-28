package net.clanimg.litematica_agent.placement;

import net.clanimg.litematica_agent.mixin.BlockItemInvoker;
import net.clanimg.litematica_agent.movement.RotationController;
import net.clanimg.litematica_agent.schematic.BuildTarget;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EntityPose;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Finds out how a block has to be clicked so that vanilla placement logic produces exactly the schematic state.
 * Every candidate is verified by running the item's own placement code with the candidate view direction.
 */
public final class PlacementSolver {
    private static final Box FAR_AWAY = new Box(0.0, -100_000.0, 0.0, 0.6, -99_998.2, 0.6);
    private static final double[] FACE_OFFSETS = {0.0, -0.3, 0.3};
    private static final float[] FEASIBILITY_PITCHES = {-80.0F, 0.0F, 80.0F};

    /**
     * @param hit        what the crosshair will hit when looking at {@code aimPoint}
     * @param sneak      whether sneak must be held while clicking
     * @param yaw        camera yaw needed
     * @param pitch      camera pitch needed
     * @param lookTrick  the camera looks in a different direction than the clicked point (see {@link #setAllowLookTricks})
     */
    public record Option(BlockHitResult hit, Vec3d aimPoint, boolean sneak, float yaw, float pitch, boolean lookTrick) {
    }

    private static final float[] TRICK_PITCHES = {-90.0F, 0.0F, 90.0F};

    private boolean allowLookTricks = true;
    /** Look-trick results per clicked face, valid for one target. The view direction does not depend on the eye. */
    private final Map<String, Optional<float[]>> trickCache = new HashMap<>();
    private @Nullable BuildTarget trickCacheTarget;

    /**
     * Some orientations (e.g. an observer facing up on the floor) can only be produced by looking in a direction
     * other than the clicked face. Vanilla servers accept that, but a player could not do it with the crosshair.
     */
    public void setAllowLookTricks(boolean allow) {
        this.allowLookTricks = allow;
    }

    public record StandSpot(BlockPos feet, Option option) {
    }

    private record AimPoint(Vec3d point, Direction face) {
    }

    /**
     * Cheap check whether the block can be placed at all right now, ignoring reach and line of sight.
     * Fails e.g. when a torch has no wall to attach to yet.
     */
    public boolean isFeasible(ClientPlayerEntity player, BuildTarget target) {
        if (!(target.item() instanceof BlockItem blockItem)) {
            return false;
        }
        World world = player.getEntityWorld();
        ItemStack stack = new ItemStack(target.item());
        boolean rotationBlock = target.state().contains(Properties.ROTATION);
        int yawSteps = rotationBlock ? 16 : 4;

        for (AimPoint aim : this.aimPoints(world, target.pos(), true)) {
            BlockPos clicked = BlockPos.ofFloored(aim.point().subtract(
                    aim.face().getOffsetX() * 0.01, aim.face().getOffsetY() * 0.01, aim.face().getOffsetZ() * 0.01));
            BlockHitResult hit = new BlockHitResult(aim.point(), aim.face(), clicked, false);
            for (int yawStep = 0; yawStep < yawSteps; yawStep++) {
                float yaw = yawStep * (360.0F / yawSteps);
                for (float pitch : FEASIBILITY_PITCHES) {
                    for (boolean sneak : new boolean[]{false, true}) {
                        BlockState result = this.simulate(player, blockItem, stack, hit, yaw, pitch, sneak, target.pos());
                        if (result != null && fits(world, result, target)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Whether the target becomes placeable if the given helper blocks existed. The helpers are inserted into the
     * client world only for the duration of this check.
     */
    public boolean isFeasibleWith(ClientPlayerEntity player, BuildTarget target, List<BlockPos> helperPositions, BlockState helper) {
        World world = player.getEntityWorld();
        List<BlockState> previous = new ArrayList<>();
        for (BlockPos pos : helperPositions) {
            previous.add(world.getBlockState(pos));
        }
        try {
            for (BlockPos pos : helperPositions) {
                world.setBlockState(pos, helper, Block.FORCE_STATE | Block.SKIP_DROPS, 0);
            }
            return this.isFeasible(player, target);
        } finally {
            for (int i = helperPositions.size() - 1; i >= 0; i--) {
                world.setBlockState(helperPositions.get(i), previous.get(i), Block.FORCE_STATE | Block.SKIP_DROPS, 0);
            }
        }
    }

    /**
     * Finds a click that works from the given eye position, including line of sight and reach.
     */
    public @Nullable Option findFromEye(ClientPlayerEntity player, BuildTarget target, Vec3d eye, double reach) {
        return this.findFromEye(player, target, eye, reach, true);
    }

    /**
     * @param tricks whether a look trick may be used (and only if they are allowed at all), see {@link #setAllowLookTricks}
     */
    public @Nullable Option findFromEye(ClientPlayerEntity player, BuildTarget target, Vec3d eye, double reach, boolean tricks) {
        if (!(target.item() instanceof BlockItem blockItem)) {
            return null;
        }
        World world = player.getEntityWorld();
        ItemStack stack = new ItemStack(target.item());
        Option fallback = null;
        List<Option> trickCandidates = tricks && this.allowLookTricks ? new ArrayList<>() : null;

        for (AimPoint aim : this.aimPoints(world, target.pos(), true)) {
            double distance = eye.distanceTo(aim.point());
            if (distance > reach || distance < 0.3) {
                continue;
            }
            Vec3d direction = aim.point().subtract(eye).normalize();
            Vec3d end = eye.add(direction.multiply(distance + 0.6));
            BlockHitResult hit = world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE,
                    RaycastContext.FluidHandling.NONE, player));
            if (hit.getType() != HitResult.Type.BLOCK || eye.distanceTo(hit.getPos()) > reach) {
                continue;
            }
            float[] angles = RotationController.anglesTo(eye, aim.point());
            boolean mustSneak = Interactables.needsSneak(world, hit.getBlockPos());

            for (boolean sneak : mustSneak ? new boolean[]{true} : new boolean[]{false, true}) {
                BlockState result = this.simulate(player, blockItem, stack, hit, angles[0], angles[1], sneak, target.pos());
                if (result == null || !fits(world, result, target)) {
                    continue;
                }
                Option option = new Option(hit, aim.point(), sneak, angles[0], angles[1], false);
                if (!sneak) {
                    return option;
                }
                if (fallback == null) {
                    fallback = option;
                }
            }
            if (trickCandidates != null && trickCandidates.isEmpty()) {
                Option trick = this.findLookTrick(player, blockItem, stack, target, hit, aim.point(), mustSneak);
                if (trick != null) {
                    trickCandidates.add(trick);
                }
            }
        }
        if (fallback != null) {
            return fallback;
        }
        return trickCandidates == null || trickCandidates.isEmpty() ? null : trickCandidates.get(0);
    }

    /**
     * Like {@link #findFromEye} from where the player is now. A click that needs sneaking has to work from the lower
     * eye of a crouching player as well, unless the player hovers in flight, where sneaking does not crouch.
     */
    public @Nullable Option findFromPlayer(ClientPlayerEntity player, BuildTarget target, double reach, boolean tricks) {
        Option option = this.findFromEye(player, target, player.getEyePos(), reach, tricks);
        if (option == null || !option.sneak() || player.getAbilities().flying || player.isInSneakingPose()) {
            return option;
        }
        Vec3d crouched = player.getEntityPos().add(0.0, player.getEyeHeight(EntityPose.CROUCHING), 0.0);
        return this.findFromEye(player, target, crouched, reach, tricks) == null ? null : option;
    }

    private @Nullable Option findLookTrick(ClientPlayerEntity player, BlockItem item, ItemStack stack, BuildTarget target,
                                           BlockHitResult hit, Vec3d aimPoint, boolean mustSneak) {
        if (!target.equals(this.trickCacheTarget)) {
            this.trickCache.clear();
            this.trickCacheTarget = target;
        }
        Vec3d hitPos = hit.getPos();
        String key = hit.getBlockPos().asLong() + ":" + hit.getSide().ordinal() + ":"
                + Math.round(hitPos.x * 4) + ":" + Math.round(hitPos.y * 4) + ":" + Math.round(hitPos.z * 4);
        Optional<float[]> cached = this.trickCache.computeIfAbsent(key, ignored -> Optional.ofNullable(
                this.searchLookTrick(player, item, stack, target, hit, mustSneak)));
        return cached.map(found -> new Option(hit, aimPoint, found[2] > 0.5F, found[0], found[1], true)).orElse(null);
    }

    private float @Nullable [] searchLookTrick(ClientPlayerEntity player, BlockItem item, ItemStack stack, BuildTarget target,
                                               BlockHitResult hit, boolean mustSneak) {
        int yawSteps = target.state().contains(Properties.ROTATION) ? 16 : 4;
        for (float pitch : TRICK_PITCHES) {
            for (int step = 0; step < yawSteps; step++) {
                float yaw = step * (360.0F / yawSteps);
                for (boolean sneak : mustSneak ? new boolean[]{true} : new boolean[]{false, true}) {
                    BlockState result = this.simulate(player, item, stack, hit, yaw, pitch, sneak, target.pos());
                    if (result != null && fits(player.getEntityWorld(), result, target)) {
                        return new float[]{yaw > 180.0F ? yaw - 360.0F : yaw, pitch, sneak ? 1.0F : 0.0F};
                    }
                }
            }
        }
        return null;
    }

    /**
     * Searches positions around the target from which it can be placed. A position that needs a look trick is only
     * taken when no position works without one: anti-cheats notice a click that does not match the view direction.
     *
     * @param standable accepts feet positions the player can stand (or hover) at
     */
    public @Nullable StandSpot findStandSpot(ClientPlayerEntity player, BuildTarget target, double reach,
                                             boolean flying, Predicate<BlockPos> standable, int maxCandidates) {
        StandSpot spot = this.findStandSpot(player, target, reach, flying, standable, maxCandidates, false);
        if (spot == null && this.allowLookTricks) {
            spot = this.findStandSpot(player, target, reach, flying, standable, maxCandidates, true);
        }
        return spot;
    }

    private @Nullable StandSpot findStandSpot(ClientPlayerEntity player, BuildTarget target, double reach, boolean flying,
                                              Predicate<BlockPos> standable, int maxCandidates, boolean tricks) {
        World world = player.getEntityWorld();
        float standingEye = player.getEyeHeight(EntityPose.STANDING);
        float sneakingEye = player.getEyeHeight(EntityPose.CROUCHING);
        // The player never stops exactly on the block centre, so plan with some distance to the reach limit.
        double planningReach = reach - StandSpots.ARRIVAL_MARGIN;
        // The player never stands inside the block's own space, even if its shape is thin (a door, a trapdoor): the
        // player never stops exactly on the spot and would end up in the way.
        StandSpots.Result<Option> result = StandSpots.search(player, target.pos(), reach, flying, standable,
                new Box(target.pos()), maxCandidates, feet -> {
                    Option option = this.findFromEye(player, target, StandSpots.eyeAt(feet, standingEye), planningReach, tricks);
                    // Sneaking crouches and lowers the eye on the ground; only while hovering in flight it does not.
                    if (option != null && option.sneak() && (!flying || hasGround(world, feet))) {
                        option = this.findFromEye(player, target, StandSpots.eyeAt(feet, sneakingEye), planningReach, tricks);
                    }
                    return option;
                });
        return result == null ? null : new StandSpot(result.feet(), result.value());
    }

    private static boolean hasGround(World world, BlockPos feet) {
        BlockPos below = feet.down();
        return !world.getBlockState(below).getCollisionShape(world, below).isEmpty();
    }

    /**
     * Verifies a real crosshair hit with the player's current rotation and sneak state right before clicking.
     */
    public boolean check(ClientPlayerEntity player, BuildTarget target, BlockHitResult hit, boolean sneak) {
        if (!(target.item() instanceof BlockItem blockItem)) {
            return false;
        }
        // Without sneaking, a click on a chest, hopper, lever... uses that block instead of placing anything.
        if (!sneak && Interactables.needsSneak(player.getEntityWorld(), hit.getBlockPos())) {
            return false;
        }
        BlockState result = this.simulate(player, blockItem, new ItemStack(target.item()), hit,
                player.getYaw(), player.getPitch(), sneak, target.pos());
        return result != null && fits(player.getEntityWorld(), result, target);
    }

    /**
     * Whether a simulated result is a valid step towards the target. A double-chest half may stay single only while its
     * partner is still missing; next to a waiting partner it has to join it, or the chest holds only half of what it
     * should (a sneaking click, e.g. on the chest below, would keep it single).
     */
    static boolean fits(World world, BlockState result, BuildTarget target) {
        BlockState wanted = target.state();
        if (!StateMatcher.isValidPlacement(result, wanted)) {
            return false;
        }
        if (!wanted.contains(Properties.CHEST_TYPE) || wanted.get(Properties.CHEST_TYPE) == ChestType.SINGLE
                || !result.contains(Properties.CHEST_TYPE) || result.get(Properties.CHEST_TYPE) != ChestType.SINGLE) {
            return true;
        }
        Direction toPartner = ChestBlock.getFacing(wanted);
        BlockState partner = world.getBlockState(target.pos().offset(toPartner));
        boolean partnerWaiting = partner.isOf(wanted.getBlock())
                && partner.get(Properties.HORIZONTAL_FACING) == wanted.get(Properties.HORIZONTAL_FACING)
                && (partner.get(Properties.CHEST_TYPE) == ChestType.SINGLE || ChestBlock.getFacing(partner) == toPartner.getOpposite());
        return !partnerWaiting;
    }

    private @Nullable BlockState simulate(ClientPlayerEntity player, BlockItem item, ItemStack stack, BlockHitResult hit,
                                          float yaw, float pitch, boolean sneak, BlockPos expectedPos) {
        float oldYaw = player.getYaw();
        float oldPitch = player.getPitch();
        PlayerInput oldInput = player.input.playerInput;
        Box oldBox = player.getBoundingBox();
        try {
            player.setYaw(yaw);
            player.setPitch(pitch);
            player.input.playerInput = new PlayerInput(oldInput.forward(), oldInput.backward(), oldInput.left(),
                    oldInput.right(), oldInput.jump(), sneak, oldInput.sprint());
            player.setBoundingBox(FAR_AWAY);

            ItemPlacementContext context = new ItemPlacementContext(player, Hand.MAIN_HAND, stack, hit);
            context = item.getPlacementContext(context);
            if (context == null || !context.canPlace() || !context.getBlockPos().equals(expectedPos)) {
                return null;
            }
            return ((BlockItemInvoker) item).litematicaAgent$getPlacementState(context);
        } catch (RuntimeException e) {
            return null;
        } finally {
            player.setYaw(oldYaw);
            player.setPitch(oldPitch);
            player.input.playerInput = oldInput;
            player.setBoundingBox(oldBox);
        }
    }

    private List<AimPoint> aimPoints(World world, BlockPos target, boolean includeSelf) {
        List<AimPoint> points = new ArrayList<>();
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = target.offset(direction);
            BlockState state = world.getBlockState(neighbor);
            if (state.isAir()) {
                continue;
            }
            VoxelShape shape = state.getOutlineShape(world, neighbor);
            if (shape.isEmpty()) {
                continue;
            }
            addFacePoints(points, neighbor, direction.getOpposite(), shape.getBoundingBox());
        }
        if (includeSelf) {
            BlockState current = world.getBlockState(target);
            if (!current.isAir()) {
                VoxelShape shape = current.getOutlineShape(world, target);
                if (!shape.isEmpty()) {
                    for (Direction face : Direction.values()) {
                        addFacePoints(points, target, face, shape.getBoundingBox());
                    }
                }
            }
        }
        return points;
    }

    /**
     * Points on one face of the block's outline box: the centre and four points towards the edges. Using the real
     * outline matters for thin blocks such as slabs, carpets and repeaters.
     */
    private static void addFacePoints(List<AimPoint> points, BlockPos block, Direction face, Box box) {
        double cx = block.getX() + (face.getOffsetX() == 0 ? (box.minX + box.maxX) / 2.0 : face.getOffsetX() > 0 ? box.maxX : box.minX);
        double cy = block.getY() + (face.getOffsetY() == 0 ? (box.minY + box.maxY) / 2.0 : face.getOffsetY() > 0 ? box.maxY : box.minY);
        double cz = block.getZ() + (face.getOffsetZ() == 0 ? (box.minZ + box.maxZ) / 2.0 : face.getOffsetZ() > 0 ? box.maxZ : box.minZ);
        double spanX = (box.maxX - box.minX) * 0.3;
        double spanY = (box.maxY - box.minY) * 0.3;
        double spanZ = (box.maxZ - box.minZ) * 0.3;
        Direction.Axis axis = face.getAxis();
        for (double a : FACE_OFFSETS) {
            for (double b : FACE_OFFSETS) {
                if (a != 0.0 && b != 0.0) {
                    continue;
                }
                Vec3d point = switch (axis) {
                    case X -> new Vec3d(cx, cy + a / 0.3 * spanY, cz + b / 0.3 * spanZ);
                    case Y -> new Vec3d(cx + a / 0.3 * spanX, cy, cz + b / 0.3 * spanZ);
                    case Z -> new Vec3d(cx + a / 0.3 * spanX, cy + b / 0.3 * spanY, cz);
                };
                points.add(new AimPoint(point, face));
            }
        }
    }

    public static @Nullable Box collisionBox(World world, BuildTarget target) {
        VoxelShape shape = target.state().getCollisionShape(world, target.pos());
        if (shape.isEmpty()) {
            return null;
        }
        return shape.getBoundingBox().offset(target.pos());
    }
}

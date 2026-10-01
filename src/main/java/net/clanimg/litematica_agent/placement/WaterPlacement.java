package net.clanimg.litematica_agent.placement;

import net.clanimg.litematica_agent.movement.RotationController;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidFillable;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.state.property.Properties;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Water the way a player handles it, with buckets: a full bucket puts a source next to the clicked face, or into a
 * waterloggable block it hits; an empty bucket takes a source, or drains a waterlogged block.
 * <p>
 * Blocks that live in water (waterlogged stairs, seagrass, sea pickles...) are not waterlogged afterwards: their
 * position gets a water source first and the block is placed into it, which the game waterlogs by itself.
 */
public final class WaterPlacement {
    /** What a bucket has to do at a target position before or instead of placing a block there. */
    public enum Action {
        NONE,
        /** Put a water source at the position (a water target, or water for a block that is placed into it). */
        FILL,
        /** The right block is there but dry: pour water into it. */
        WATERLOG,
        /** The right block is there but waterlogged: take the water out. */
        DRAIN
    }

    /** How far around the player and the target a renewable source for refilling an empty bucket is searched. */
    public static final int REFILL_RADIUS = 10;
    /**
     * Water buckets the agent carries. Buckets are refilled from renewable water on site, so a few are enough
     * however much water the schematic has; storage only has to provide these for the first sources.
     */
    public static final int BUCKETS_CARRIED = 4;

    /** Neighbours tried for the click that puts water at a position; the ground below first, like a player. */
    private static final Direction[] FILL_ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
    private static final double[][] FACE_POINTS = {{0.5, 0.5}, {0.25, 0.25}, {0.25, 0.75}, {0.75, 0.25}, {0.75, 0.75}};

    private WaterPlacement() {
    }

    /** A plain water source block of the schematic. Flowing water is left out: it follows from the sources. */
    public static boolean isWaterTarget(BlockState target) {
        return target.isOf(Blocks.WATER) && target.getFluidState().isStill();
    }

    /** Blocks that contain water: waterlogged blocks, seagrass, kelp... They are placed into a water source. */
    public static boolean needsWater(BlockState target) {
        return !target.isOf(Blocks.WATER) && target.getFluidState().isOf(Fluids.WATER);
    }

    /** Placed onto the surface of water, like a lily pad. */
    public static boolean placedOnWater(BlockState target) {
        return target.isOf(Blocks.LILY_PAD) || target.isOf(Blocks.FROGSPAWN);
    }

    /** Anything the agent needs a bucket or open water for. */
    public static boolean involvesWater(BlockState target) {
        return isWaterTarget(target) || needsWater(target) || placedOnWater(target);
    }

    /** A still water source (a water block or a waterlogged block), not flowing water. */
    public static boolean hasSource(BlockView world, BlockPos pos) {
        return world.getFluidState(pos).isOf(Fluids.WATER);
    }

    public static Action actionFor(BlockView world, BlockPos pos, BlockState target) {
        BlockState state = world.getBlockState(pos);
        if (isWaterTarget(target)) {
            if (state.isOf(Blocks.WATER) && state.getFluidState().isStill()) {
                return Action.NONE;
            }
            return state.isAir() || state.isReplaceable() ? Action.FILL : Action.NONE;
        }
        if (needsWater(target)) {
            if (state.getBlock() == target.getBlock() && state.contains(Properties.WATERLOGGED)
                    && !state.get(Properties.WATERLOGGED)
                    && StateMatcher.isComplete(state.with(Properties.WATERLOGGED, true), target)) {
                return Action.WATERLOG;
            }
            return (state.isAir() || state.isReplaceable()) && !hasSource(world, pos) ? Action.FILL : Action.NONE;
        }
        if (target.contains(Properties.WATERLOGGED) && !target.get(Properties.WATERLOGGED)
                && state.getBlock() == target.getBlock() && state.contains(Properties.WATERLOGGED)
                && state.get(Properties.WATERLOGGED)
                && StateMatcher.isComplete(state.with(Properties.WATERLOGGED, false), target)) {
            return Action.DRAIN;
        }
        return Action.NONE;
    }

    /**
     * Where to click with a full bucket so that a source ends up at {@code pos}: a face of a neighbour pointing at it.
     * A dry waterloggable neighbour does not work, the bucket would fill that block instead. The bucket's ray ignores
     * water, so the click goes through water already there.
     */
    public static @Nullable Aiming.Aim fillAim(ClientPlayerEntity player, BlockPos pos, Vec3d eye, double reach) {
        World world = player.getEntityWorld();
        Aiming.Aim best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction towards : FILL_ORDER) {
            BlockPos neighbour = pos.offset(towards);
            BlockState state = world.getBlockState(neighbour);
            VoxelShape shape = state.getOutlineShape(world, neighbour);
            if (shape.isEmpty()) {
                continue;
            }
            if (state.getBlock() instanceof FluidFillable fillable
                    && fillable.canFillWithFluid(player, world, neighbour, state, Fluids.WATER)) {
                continue;
            }
            Direction face = towards.getOpposite();
            for (Vec3d point : facePoints(neighbour, shape.getBoundingBox(), face)) {
                double distance = eye.distanceTo(point);
                if (distance > reach || distance >= bestDistance) {
                    continue;
                }
                BlockHitResult hit = raycast(player, eye, point, RaycastContext.FluidHandling.NONE);
                if (hit == null || !hit.getBlockPos().equals(neighbour) || hit.getSide() != face) {
                    continue;
                }
                best = aim(hit, eye, point);
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Aim at the block itself for pouring water into it; the bucket's ray ignores the water around it. */
    public static @Nullable Aiming.Aim waterlogAim(ClientPlayerEntity player, BlockPos pos, Vec3d eye, double reach) {
        return Aiming.aimAt(player, pos, eye, reach);
    }

    /** Aim at a waterlogged block for draining it: an empty bucket takes the first source on its way, so it has to be this one. */
    public static @Nullable Aiming.Aim drainAim(ClientPlayerEntity player, BlockPos pos, Vec3d eye, double reach) {
        Aiming.Aim aim = Aiming.aimAt(player, pos, eye, reach);
        if (aim == null) {
            return null;
        }
        BlockHitResult hit = raycast(player, eye, aim.point(), RaycastContext.FluidHandling.SOURCE_ONLY);
        return hit != null && hit.getBlockPos().equals(pos) ? aim : null;
    }

    /** Aim at the water surface below {@code pos}; the item (a lily pad) ends up on top of it. */
    public static @Nullable Aiming.Aim surfaceAim(ClientPlayerEntity player, BlockPos pos, Vec3d eye, double reach) {
        return sourceAim(player, pos.down(), eye, reach, false);
    }

    /**
     * A water block the game refills right away when a bucket takes it: at least two plain sources beside it and ground
     * or water below (the infinite water rule). Taking from it never undoes finished work.
     */
    @SuppressWarnings("deprecation") // isSolid() is exactly what the game's own infinite water rule checks below
    public static boolean isRenewable(BlockView world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!state.isOf(Blocks.WATER) || !state.getFluidState().isStill()) {
            return false;
        }
        int sources = 0;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockState side = world.getBlockState(pos.offset(direction));
            if (side.isOf(Blocks.WATER) && side.getFluidState().isStill()) {
                sources++;
            }
        }
        if (sources < 2) {
            return false;
        }
        BlockState below = world.getBlockState(pos.down());
        return below.isSolid() || below.getFluidState().isOf(Fluids.WATER);
    }

    /** Whether a plain water source lies around {@code center}, renewable or not (e.g. water just poured). */
    public static boolean hasWaterNear(BlockView world, BlockPos center, int radius) {
        for (BlockPos pos : BlockPos.iterate(center.add(-radius, -radius, -radius), center.add(radius, radius, radius))) {
            BlockState state = world.getBlockState(pos);
            if (state.isOf(Blocks.WATER) && state.getFluidState().isStill()) {
                return true;
            }
        }
        return false;
    }

    /** The nearest renewable water block around {@code center}, or null. */
    public static @Nullable BlockPos findRenewable(BlockView world, BlockPos center, int radius) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(center.add(-radius, -radius, -radius), center.add(radius, radius, radius))) {
            double distance = pos.getSquaredDistance(center);
            if (distance < bestDistance && isRenewable(world, pos)) {
                best = pos.toImmutable();
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Aim for filling an empty bucket: the first source on the way must be a renewable one. */
    public static @Nullable Aiming.Aim refillAim(ClientPlayerEntity player, BlockPos source, Vec3d eye, double reach) {
        return sourceAim(player, source, eye, reach, true);
    }

    private static @Nullable Aiming.Aim sourceAim(ClientPlayerEntity player, BlockPos water, Vec3d eye, double reach,
                                                  boolean renewableOnly) {
        World world = player.getEntityWorld();
        Aiming.Aim best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Vec3d point : waterPoints(water)) {
            double distance = eye.distanceTo(point);
            if (distance > reach || distance >= bestDistance) {
                continue;
            }
            BlockHitResult hit = raycast(player, eye, point, RaycastContext.FluidHandling.SOURCE_ONLY);
            if (hit == null) {
                continue;
            }
            boolean fits = renewableOnly ? isRenewable(world, hit.getBlockPos())
                    : hit.getBlockPos().equals(water) && hasSource(world, water);
            if (fits) {
                best = aim(hit, eye, point);
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * What a bucket (or a lily pad) would hit with the player's current view direction, the same ray the server
     * casts with the rotation sent along with the click.
     */
    public static @Nullable BlockHitResult currentHit(ClientPlayerEntity player, RaycastContext.FluidHandling fluids) {
        Vec3d eye = player.getEyePos();
        Vec3d end = eye.add(player.getRotationVec(1.0F).multiply(player.getBlockInteractionRange()));
        BlockHitResult hit = player.getEntityWorld().raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE,
                fluids, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    private static @Nullable BlockHitResult raycast(ClientPlayerEntity player, Vec3d eye, Vec3d point,
                                                    RaycastContext.FluidHandling fluids) {
        Vec3d direction = point.subtract(eye).normalize();
        Vec3d end = eye.add(direction.multiply(eye.distanceTo(point) + 0.5));
        BlockHitResult hit = player.getEntityWorld().raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE,
                fluids, player));
        return hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    private static Aiming.Aim aim(BlockHitResult hit, Vec3d eye, Vec3d point) {
        float[] angles = RotationController.anglesTo(eye, point);
        return new Aiming.Aim(hit, point, angles[0], angles[1]);
    }

    /** Points on one face of the neighbour's outline box, well inside the edges. */
    private static List<Vec3d> facePoints(BlockPos pos, Box box, Direction face) {
        List<Vec3d> points = new ArrayList<>(FACE_POINTS.length);
        for (double[] uv : FACE_POINTS) {
            double x;
            double y;
            double z;
            switch (face.getAxis()) {
                case X -> {
                    x = face.getOffsetX() > 0 ? box.maxX : box.minX;
                    y = lerp(box.minY, box.maxY, uv[0]);
                    z = lerp(box.minZ, box.maxZ, uv[1]);
                }
                case Y -> {
                    y = face.getOffsetY() > 0 ? box.maxY : box.minY;
                    x = lerp(box.minX, box.maxX, uv[0]);
                    z = lerp(box.minZ, box.maxZ, uv[1]);
                }
                default -> {
                    z = face.getOffsetZ() > 0 ? box.maxZ : box.minZ;
                    x = lerp(box.minX, box.maxX, uv[0]);
                    y = lerp(box.minY, box.maxY, uv[1]);
                }
            }
            points.add(new Vec3d(pos.getX() + x, pos.getY() + y, pos.getZ() + z));
        }
        return points;
    }

    private static double lerp(double min, double max, double fraction) {
        return min + (max - min) * fraction;
    }

    /** Points inside the water volume of a source, reached from above or from a side. */
    private static List<Vec3d> waterPoints(BlockPos water) {
        List<Vec3d> points = new ArrayList<>(9);
        for (double[] uv : FACE_POINTS) {
            points.add(new Vec3d(water.getX() + uv[0], water.getY() + 0.7, water.getZ() + uv[1]));
        }
        points.add(new Vec3d(water.getX() + 0.5, water.getY() + 0.4, water.getZ() + 0.05));
        points.add(new Vec3d(water.getX() + 0.5, water.getY() + 0.4, water.getZ() + 0.95));
        points.add(new Vec3d(water.getX() + 0.05, water.getY() + 0.4, water.getZ() + 0.5));
        points.add(new Vec3d(water.getX() + 0.95, water.getY() + 0.4, water.getZ() + 0.5));
        return points;
    }
}

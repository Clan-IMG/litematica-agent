package net.clanimg.litematica_agent.placement;

import net.clanimg.litematica_agent.movement.RotationController;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayerEntity;
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

/**
 * Finds a visible point on a block to look at and click, e.g. to open a chest or to break a block.
 */
public final class Aiming {
    private Aiming() {
    }

    public record Aim(BlockHitResult hit, Vec3d point, float yaw, float pitch) {
    }

    public static @Nullable Aim aimAt(ClientPlayerEntity player, BlockPos pos, Vec3d eye, double reach) {
        World world = player.getEntityWorld();
        BlockState state = world.getBlockState(pos);
        VoxelShape shape = state.getOutlineShape(world, pos);
        Box box = shape.isEmpty() ? new Box(0, 0, 0, 1, 1, 1) : shape.getBoundingBox();
        Vec3d center = box.getCenter().add(pos.getX(), pos.getY(), pos.getZ());

        Aim best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction face : Direction.values()) {
            Vec3d point = new Vec3d(
                    face.getOffsetX() == 0 ? center.x : pos.getX() + (face.getOffsetX() > 0 ? box.maxX : box.minX),
                    face.getOffsetY() == 0 ? center.y : pos.getY() + (face.getOffsetY() > 0 ? box.maxY : box.minY),
                    face.getOffsetZ() == 0 ? center.z : pos.getZ() + (face.getOffsetZ() > 0 ? box.maxZ : box.minZ));
            double distance = eye.distanceTo(point);
            if (distance > reach || distance >= bestDistance) {
                continue;
            }
            Vec3d direction = point.subtract(eye).normalize();
            Vec3d end = eye.add(direction.multiply(distance + 0.5));
            BlockHitResult hit = world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE,
                    RaycastContext.FluidHandling.NONE, player));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) {
                continue;
            }
            float[] angles = RotationController.anglesTo(eye, point);
            best = new Aim(hit, point, angles[0], angles[1]);
            bestDistance = distance;
        }
        return best;
    }

    /**
     * What the player's crosshair hits right now with the current camera rotation.
     */
    public static @Nullable BlockHitResult crosshair(ClientPlayerEntity player, double reach) {
        HitResult result = player.raycast(reach, 1.0F, false);
        return result instanceof BlockHitResult blockHit && result.getType() == HitResult.Type.BLOCK ? blockHit : null;
    }
}

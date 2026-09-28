package net.clanimg.litematica_agent.placement;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Searches feet positions around a block, nearest to the player first.
 */
public final class StandSpots {
    /** Distance kept to the reach limit when choosing a position, because arrival is not exact. */
    public static final double ARRIVAL_MARGIN = 0.6;
    private StandSpots() {
    }

    public record Result<T>(BlockPos feet, T value) {
    }

    /**
     * @param avoid     the player's body must not intersect this box (the block that is going to be placed)
     * @param evaluator returns a non-null value when the feet position works
     */
    public static <T> @Nullable Result<T> search(ClientPlayerEntity player, BlockPos around, double reach, boolean flying,
                                                  Predicate<BlockPos> standable, @Nullable Box avoid, int maxCandidates,
                                                  Function<BlockPos, @Nullable T> evaluator) {
        Vec3d playerPos = player.getEntityPos();
        int radius = (int) Math.ceil(reach);
        int minDy = flying ? -4 : -3;
        int maxDy = flying ? 3 : 2;

        List<BlockPos> candidates = new ArrayList<>();
        double maxHorizontal = (reach + 0.5) * (reach + 0.5);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > maxHorizontal) {
                    continue;
                }
                for (int dy = minDy; dy <= maxDy; dy++) {
                    BlockPos feet = around.add(dx, dy, dz);
                    if (avoid != null && playerBox(feet).intersects(avoid)) {
                        continue;
                    }
                    candidates.add(feet);
                }
            }
        }
        // A flying agent works from above, like the nozzle of a 3D printer, so it does not wall itself in.
        candidates.sort(Comparator.comparingDouble(feet ->
                feet.getSquaredDistance(playerPos) + Math.abs(feet.getY() - around.getY()) * 2.0
                        + (flying && feet.getY() < around.getY() ? 64.0 : 0.0)));

        // First the positions closest to the player (short walks), then, if none of them works, the positions
        // closest to the block itself, e.g. inside a room while the player is outside.
        int evaluated = 0;
        List<BlockPos> rest = new ArrayList<>();
        for (BlockPos feet : candidates) {
            if (evaluated >= maxCandidates) {
                rest.add(feet);
                continue;
            }
            if (!standable.test(feet)) {
                continue;
            }
            evaluated++;
            T value = evaluator.apply(feet);
            if (value != null) {
                return new Result<>(feet, value);
            }
        }
        rest.sort(Comparator.comparingDouble(feet -> feet.getSquaredDistance(around)));
        int budget = maxCandidates * 3;
        for (BlockPos feet : rest) {
            if (budget-- <= 0) {
                break;
            }
            if (!standable.test(feet)) {
                continue;
            }
            T value = evaluator.apply(feet);
            if (value != null) {
                return new Result<>(feet, value);
            }
        }
        return null;
    }

    public static Box playerBox(BlockPos feet) {
        return new Box(feet.getX() + 0.2, feet.getY(), feet.getZ() + 0.2, feet.getX() + 0.8, feet.getY() + 1.8, feet.getZ() + 0.8);
    }

    public static Vec3d eyeAt(BlockPos feet, float eyeHeight) {
        return new Vec3d(feet.getX() + 0.5, feet.getY() + eyeHeight, feet.getZ() + 0.5);
    }
}

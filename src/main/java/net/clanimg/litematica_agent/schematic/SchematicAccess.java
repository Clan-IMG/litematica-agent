package net.clanimg.litematica_agent.schematic;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.PositionUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bridge to Litematica. Litematica has no public API, so this class is the only place touching its internals.
 */
public final class SchematicAccess {
    private SchematicAccess() {
    }

    public static List<SchematicPlacement> getPlacements() {
        return DataManager.getSchematicPlacementManager().getAllSchematicsPlacements();
    }

    public static @Nullable SchematicPlacement getSelectedPlacement() {
        return DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
    }

    public static Optional<SchematicPlacement> find(PlacementRef ref) {
        SchematicPlacement fallback = null;
        for (SchematicPlacement placement : getPlacements()) {
            if (placement.getHashId() != null && placement.getHashId().toString().equals(ref.hashId())) {
                return Optional.of(placement);
            }
            if (fallback == null && ref.matchesLoosely(placement)) {
                fallback = placement;
            }
        }
        return Optional.ofNullable(fallback);
    }

    /**
     * Distance from {@code pos} to the closest point of any enabled sub-region box of the placement.
     */
    public static double distanceTo(SchematicPlacement placement, Vec3d pos) {
        double best = Double.MAX_VALUE;
        for (Box box : placement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).values()) {
            BlockPos p1 = box.getPos1();
            BlockPos p2 = box.getPos2();
            if (p1 == null || p2 == null) {
                continue;
            }
            double cx = clamp(pos.x, Math.min(p1.getX(), p2.getX()), Math.max(p1.getX(), p2.getX()) + 1);
            double cy = clamp(pos.y, Math.min(p1.getY(), p2.getY()), Math.max(p1.getY(), p2.getY()) + 1);
            double cz = clamp(pos.z, Math.min(p1.getZ(), p2.getZ()), Math.max(p1.getZ(), p2.getZ()) + 1);
            best = Math.min(best, pos.distanceTo(new Vec3d(cx, cy, cz)));
        }
        return best;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Reads every non-air block of all enabled sub-regions and converts it to world coordinates, using the same
     * transformation Litematica applies when pasting ({@code SchematicPlacingUtils.placeBlocksWithinChunk}).
     */
    public static SchematicReadResult readTargets(SchematicPlacement placement) {
        List<BuildTarget> targets = new ArrayList<>();
        List<UnsupportedBlock> unsupported = new ArrayList<>();
        LitematicaSchematic schematic = placement.getSchematic();
        if (schematic == null) {
            return new SchematicReadResult(targets, unsupported);
        }

        BlockPos origin = placement.getOrigin();
        BlockRotation mainRotation = placement.getRotation();
        BlockMirror mainMirror = placement.getMirror();
        MaterialCache materials = MaterialCache.getInstance();

        for (Map.Entry<String, SubRegionPlacement> entry : placement.getEnabledRelativeSubRegionPlacements().entrySet()) {
            String regionName = entry.getKey();
            SubRegionPlacement sub = entry.getValue();
            LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);
            Vec3i regionSize = schematic.getAreaSizeAsVec3i(regionName);
            if (container == null || regionSize == null) {
                continue;
            }

            BlockPos regionPos = sub.getPos();
            BlockPos posEndRel = new BlockPos(PositionUtils.getRelativeEndPositionFromAreaSize(regionSize)).add(regionPos);
            BlockPos posMinRel = PositionUtils.getMinCorner(regionPos, posEndRel);
            BlockPos regionPosTransformed = PositionUtils.getTransformedBlockPos(regionPos, mainMirror, mainRotation);
            BlockPos totalOffset = regionPosTransformed.add(origin);

            BlockRotation rotationCombined = mainRotation.rotate(sub.getRotation());
            BlockMirror mirrorSub = sub.getMirror();
            if (mirrorSub != BlockMirror.NONE
                    && (mainRotation == BlockRotation.CLOCKWISE_90 || mainRotation == BlockRotation.COUNTERCLOCKWISE_90)) {
                mirrorSub = mirrorSub == BlockMirror.FRONT_BACK ? BlockMirror.LEFT_RIGHT : BlockMirror.FRONT_BACK;
            }

            int offX = posMinRel.getX() - regionPos.getX();
            int offY = posMinRel.getY() - regionPos.getY();
            int offZ = posMinRel.getZ() - regionPos.getZ();
            Vec3i size = container.getSize();
            BlockPos.Mutable relative = new BlockPos.Mutable();

            for (int y = 0; y < size.getY(); y++) {
                for (int z = 0; z < size.getZ(); z++) {
                    for (int x = 0; x < size.getX(); x++) {
                        BlockState state = container.get(x, y, z);
                        if (state.isAir() || state.isOf(Blocks.STRUCTURE_VOID)) {
                            continue;
                        }
                        relative.set(offX + x, offY + y, offZ + z);
                        BlockPos worldPos = PositionUtils.getTransformedPlacementPosition(relative, placement, sub).add(totalOffset);

                        if (mainMirror != BlockMirror.NONE) {
                            state = state.mirror(mainMirror);
                        }
                        if (mirrorSub != BlockMirror.NONE) {
                            state = state.mirror(mirrorSub);
                        }
                        if (rotationCombined != BlockRotation.NONE) {
                            state = state.rotate(rotationCombined);
                        }

                        addTarget(worldPos.toImmutable(), state, materials, targets, unsupported);
                    }
                }
            }
        }

        return new SchematicReadResult(targets, unsupported);
    }

    private static void addTarget(BlockPos pos, BlockState state, MaterialCache materials,
                                  List<BuildTarget> targets, List<UnsupportedBlock> unsupported) {
        if (materials.requiresMultipleItems(state)) {
            unsupported.add(new UnsupportedBlock(pos, state, UnsupportedBlock.Reason.MULTIPLE_ITEMS));
            return;
        }
        ItemStack stack = materials.getRequiredBuildItemForState(state);
        if (stack.isEmpty()) {
            // Upper door halves, bed heads, piston heads, ... are created together with their counterpart.
            if (!state.getFluidState().isEmpty() && state.getFluidState().isStill()) {
                unsupported.add(new UnsupportedBlock(pos, state, UnsupportedBlock.Reason.FLUID));
            }
            return;
        }
        if (!(stack.getItem() instanceof BlockItem)) {
            unsupported.add(new UnsupportedBlock(pos, state, UnsupportedBlock.Reason.NOT_A_BLOCK_ITEM));
            return;
        }
        targets.add(new BuildTarget(pos, state, stack.getItem(), Math.max(1, stack.getCount())));
    }

    public record SchematicReadResult(List<BuildTarget> targets, List<UnsupportedBlock> unsupported) {
    }

    public record UnsupportedBlock(BlockPos pos, BlockState state, Reason reason) {
        public enum Reason {
            FLUID,
            MULTIPLE_ITEMS,
            NOT_A_BLOCK_ITEM
        }
    }
}

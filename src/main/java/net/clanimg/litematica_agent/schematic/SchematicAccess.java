package net.clanimg.litematica_agent.schematic;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.PositionUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.clanimg.litematica_agent.placement.WaterPlacement;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
     * Reads the whole schematic at once, on the calling thread. Sessions read it in stages on background threads
     * instead ({@link #readBlocks}, {@link #requirementsFor}, {@link #toResult}), which keeps the render thread free.
     */
    public static SchematicReadResult readTargets(SchematicPlacement placement) {
        SchematicBlocks blocks = readBlocks(placement);
        return toResult(blocks.states(), requirementsFor(blocks.distinct()));
    }

    /** Every non-air block of the schematic by packed world position, and the distinct states among them. */
    public record SchematicBlocks(Long2ObjectLinkedOpenHashMap<BlockState> states, Set<BlockState> distinct) {
    }

    /**
     * Reads every non-air block of all enabled sub-regions and converts it to world coordinates, using the same
     * transformation Litematica applies when pasting ({@code SchematicPlacingUtils.placeBlocksWithinChunk}). Only reads
     * the schematic's own data, so it may run on a background thread.
     */
    public static SchematicBlocks readBlocks(SchematicPlacement placement) {
        LitematicaSchematic schematic = placement.getSchematic();
        // Sized once from the file's own block count, densely packed: a map that doubles itself two dozen times on
        // the way to tens of millions of entries briefly needs twice its final size, which is what tips a 4 GB game
        // over the edge; and the default fill leaves a third of the slots empty.
        int expected = schematic == null ? 16 : Math.max(16, schematic.getMetadata().getTotalBlocks());
        Long2ObjectLinkedOpenHashMap<BlockState> blocks = new Long2ObjectLinkedOpenHashMap<>(expected, 0.9f);
        Set<BlockState> distinct = new ReferenceOpenHashSet<>();
        if (schematic == null) {
            return new SchematicBlocks(blocks, distinct);
        }

        BlockPos origin = placement.getOrigin();
        BlockRotation mainRotation = placement.getRotation();
        BlockMirror mainMirror = placement.getMirror();

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

                        blocks.put(worldPos.asLong(), state);
                        distinct.add(state);
                    }
                }
            }
        }
        return new SchematicBlocks(blocks, distinct);
    }

    /**
     * What a block state needs to be built: its item and count, or that it needs several items at once.
     */
    public record Requirement(ItemStack stack, boolean multipleItems) {
    }

    /**
     * Looks up the item of every distinct block state. Uses Litematica's material cache, which is not made for other
     * threads, so this runs on the render thread; there are only as many states as block types in the schematic.
     */
    public static Map<BlockState, Requirement> requirementsFor(Set<BlockState> states) {
        MaterialCache materials = MaterialCache.getInstance();
        Map<BlockState, Requirement> requirements = new Reference2ObjectOpenHashMap<>();
        for (BlockState state : states) {
            if (materials.requiresMultipleItems(state)) {
                requirements.put(state, new Requirement(ItemStack.EMPTY, true));
                continue;
            }
            // A dirt path is made from dirt with a shovel; the dirt path item itself cannot be obtained in survival.
            ItemStack stack = state.isOf(Blocks.DIRT_PATH) ? new ItemStack(Items.DIRT) : materials.getRequiredBuildItemForState(state);
            requirements.put(state, new Requirement(stack.copy(), false));
        }
        return requirements;
    }

    /**
     * Turns the blocks into build targets, leaving out what cannot be built. May run on a background thread.
     */
    public static SchematicReadResult toResult(Long2ObjectLinkedOpenHashMap<BlockState> blocks, Map<BlockState, Requirement> requirements) {
        List<BuildTarget> targets = new ArrayList<>(blocks.size());
        List<UnsupportedBlock> unsupported = new ArrayList<>();
        for (Long2ObjectMap.Entry<BlockState> entry : blocks.long2ObjectEntrySet()) {
            BlockPos pos = BlockPos.fromLong(entry.getLongKey());
            BlockState state = entry.getValue();
            if (isHalfOfPair(pos, state, blocks)) {
                unsupported.add(new UnsupportedBlock(pos, state, UnsupportedBlock.Reason.HALF_PAIR));
                continue;
            }
            addTarget(pos, state, requirements.get(state), targets, unsupported);
        }
        return new SchematicReadResult(targets, unsupported, blocks);
    }

    /**
     * Beds, doors and tall plants always consist of two blocks. A schematic can contain only one half (e.g. cut with
     * Axiom without block updates), which cannot be built legitimately: placing it creates the other half as well.
     */
    private static boolean isHalfOfPair(BlockPos pos, BlockState state, Long2ObjectMap<BlockState> blocks) {
        if (state.contains(Properties.BED_PART) && state.contains(Properties.HORIZONTAL_FACING)) {
            Direction facing = state.get(Properties.HORIZONTAL_FACING);
            BedPart part = state.get(Properties.BED_PART);
            BlockState other = blocks.get(pos.offset(part == BedPart.FOOT ? facing : facing.getOpposite()).asLong());
            return other == null || !other.isOf(state.getBlock()) || other.get(Properties.BED_PART) == part
                    || other.get(Properties.HORIZONTAL_FACING) != facing;
        }
        if (state.contains(Properties.DOUBLE_BLOCK_HALF)) {
            DoubleBlockHalf half = state.get(Properties.DOUBLE_BLOCK_HALF);
            BlockState other = blocks.get((half == DoubleBlockHalf.LOWER ? pos.up() : pos.down()).asLong());
            return other == null || !other.isOf(state.getBlock()) || other.get(Properties.DOUBLE_BLOCK_HALF) == half;
        }
        return false;
    }

    private static void addTarget(BlockPos pos, BlockState state, @Nullable Requirement requirement,
                                  List<BuildTarget> targets, List<UnsupportedBlock> unsupported) {
        if (WaterPlacement.isWaterTarget(state)) {
            // Poured with a bucket; the buckets are refilled on site, see WaterPlacement.
            targets.add(new BuildTarget(pos, state, Items.WATER_BUCKET, 1));
            return;
        }
        if (state.isOf(Blocks.WATER)) {
            // Flowing water follows from the sources next to it by itself.
            return;
        }
        if (state.isOf(Blocks.LIGHT) || state.isOf(Blocks.BARRIER) || state.isOf(Blocks.BEDROCK)
                || state.isOf(Blocks.COMMAND_BLOCK) || state.isOf(Blocks.CHAIN_COMMAND_BLOCK)
                || state.isOf(Blocks.REPEATING_COMMAND_BLOCK) || state.isOf(Blocks.STRUCTURE_BLOCK)
                || state.isOf(Blocks.JIGSAW) || state.isOf(Blocks.END_PORTAL_FRAME) || state.isOf(Blocks.SPAWNER)) {
            unsupported.add(new UnsupportedBlock(pos, state, UnsupportedBlock.Reason.SURVIVAL_UNOBTAINABLE));
            return;
        }
        if (requirement != null && requirement.multipleItems()) {
            unsupported.add(new UnsupportedBlock(pos, state, UnsupportedBlock.Reason.MULTIPLE_ITEMS));
            return;
        }
        ItemStack stack = requirement == null ? ItemStack.EMPTY : requirement.stack();
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

    /**
     * @param states every non-air block of the schematic by packed position, including those that are not targets
     */
    public record SchematicReadResult(List<BuildTarget> targets, List<UnsupportedBlock> unsupported,
                                      Long2ObjectMap<BlockState> states) {
    }

    /**
     * A schematic block that cannot be built as it is. Most are left out; {@link Reason#BECOMES_DIRT} is built as dirt.
     */
    public record UnsupportedBlock(BlockPos pos, BlockState state, Reason reason) {
        public enum Reason {
            FLUID,
            MULTIPLE_ITEMS,
            NOT_A_BLOCK_ITEM,
            HALF_PAIR,
            NO_SUPPORT,
            MISSING_WATER,
            SURVIVAL_UNOBTAINABLE,
            BECOMES_DIRT,
            OUTSIDE_WORLD
        }
    }
}

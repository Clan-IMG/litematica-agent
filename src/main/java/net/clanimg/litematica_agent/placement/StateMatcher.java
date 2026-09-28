package net.clanimg.litematica_agent.placement;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DaylightDetectorBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.block.enums.SlabType;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.Direction;

import java.util.Set;

/**
 * Decides which block state properties matter when comparing the world with the schematic.
 * <ul>
 *     <li>placement properties are fixed by how the block is placed (facing, half, ...)</li>
 *     <li>count properties grow with every additional placement (candles, slabs, ...)</li>
 *     <li>interaction properties are changed afterwards by right-clicking (repeater delay, ...)</li>
 *     <li>everything else follows from neighbours or game ticks and is ignored</li>
 * </ul>
 */
public final class StateMatcher {
    private static final Set<Property<?>> PLACEMENT_PROPERTIES = Set.of(
            Properties.FACING,
            Properties.HORIZONTAL_FACING,
            Properties.HOPPER_FACING,
            Properties.VERTICAL_DIRECTION,
            Properties.AXIS,
            Properties.HORIZONTAL_AXIS,
            Properties.BLOCK_HALF,
            Properties.DOUBLE_BLOCK_HALF,
            Properties.DOOR_HINGE,
            Properties.BLOCK_FACE,
            Properties.ROTATION,
            Properties.ORIENTATION,
            Properties.HANGING,
            Properties.ATTACHMENT,
            Properties.BED_PART
    );

    private static final Set<IntProperty> COUNT_PROPERTIES = Set.of(
            Properties.CANDLES,
            Properties.PICKLES,
            Properties.EGGS,
            Properties.LAYERS,
            Properties.FLOWER_AMOUNT,
            Properties.SEGMENT_AMOUNT
    );

    private StateMatcher() {
    }

    /**
     * Whether the world state fully equals the schematic state (ignoring neighbour-driven properties).
     */
    public static boolean isComplete(BlockState world, BlockState target) {
        return placementMatches(world, target, false) && countsEqual(world, target) && interactionsMatch(world, target);
    }

    /**
     * Whether a simulated placement result is a valid step towards the target.
     */
    public static boolean isValidPlacement(BlockState result, BlockState target) {
        if (!placementMatches(result, target, true)) {
            return false;
        }
        for (IntProperty property : COUNT_PROPERTIES) {
            if (result.contains(property) && target.contains(property) && result.get(property) > target.get(property)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The block and its placement properties already match; only right-click adjustments are missing.
     */
    public static boolean needsInteraction(BlockState world, BlockState target) {
        return placementMatches(world, target, false) && countsEqual(world, target) && !interactionsMatch(world, target);
    }

    /**
     * The same block is present but with fewer items (e.g. a single slab where a double slab is wanted).
     */
    public static boolean needsIncrement(BlockState world, BlockState target) {
        if (!placementMatches(world, target, true)) {
            return false;
        }
        if (target.contains(Properties.SLAB_TYPE) && target.get(Properties.SLAB_TYPE) == SlabType.DOUBLE
                && world.get(Properties.SLAB_TYPE) != SlabType.DOUBLE) {
            return true;
        }
        for (IntProperty property : COUNT_PROPERTIES) {
            if (world.contains(property) && target.contains(property) && world.get(property) < target.get(property)) {
                return true;
            }
        }
        return false;
    }

    private static boolean placementMatches(BlockState actual, BlockState target, boolean duringPlacement) {
        if (actual.getBlock() != target.getBlock()) {
            return false;
        }
        for (Property<?> property : target.getProperties()) {
            if (!actual.contains(property)) {
                continue;
            }
            if (property == Properties.SLAB_TYPE) {
                SlabType wanted = target.get(Properties.SLAB_TYPE);
                SlabType got = actual.get(Properties.SLAB_TYPE);
                if (wanted == SlabType.DOUBLE ? !duringPlacement && got != SlabType.DOUBLE : got != wanted) {
                    return false;
                }
            } else if (property == Properties.CHEST_TYPE) {
                ChestType wanted = target.get(Properties.CHEST_TYPE);
                ChestType got = actual.get(Properties.CHEST_TYPE);
                // A double-chest half stays single until its partner is placed next to it.
                if (got != wanted && got != ChestType.SINGLE) {
                    return false;
                }
            } else if (isPlacementProperty(property) && !actual.get(property).equals(target.get(property))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPlacementProperty(Property<?> property) {
        if (PLACEMENT_PROPERTIES.contains(property)) {
            return true;
        }
        // Mods and a few vanilla blocks declare their own facing/axis property instances.
        Class<?> type = property.getType();
        return (type == Direction.class && property.getName().equals("facing"))
                || (type == Direction.Axis.class && property.getName().equals("axis"));
    }

    private static boolean countsEqual(BlockState actual, BlockState target) {
        if (target.contains(Properties.SLAB_TYPE) && actual.get(Properties.SLAB_TYPE) != target.get(Properties.SLAB_TYPE)) {
            return false;
        }
        for (IntProperty property : COUNT_PROPERTIES) {
            if (target.contains(property) && actual.contains(property) && !actual.get(property).equals(target.get(property))) {
                return false;
            }
        }
        return true;
    }

    private static boolean interactionsMatch(BlockState actual, BlockState target) {
        for (Property<?> property : interactionProperties(target)) {
            if (actual.contains(property) && !actual.get(property).equals(target.get(property))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Properties that the agent adjusts by right-clicking the placed block.
     */
    public static Set<Property<?>> interactionProperties(BlockState state) {
        Block block = state.getBlock();
        if (state.isOf(Blocks.REPEATER)) {
            return Set.of(Properties.DELAY);
        }
        if (state.isOf(Blocks.COMPARATOR)) {
            return Set.of(Properties.COMPARATOR_MODE);
        }
        if (state.isOf(Blocks.NOTE_BLOCK)) {
            return Set.of(Properties.NOTE);
        }
        if (block instanceof LeverBlock) {
            return Set.of(Properties.POWERED);
        }
        if (block instanceof DaylightDetectorBlock) {
            return Set.of(Properties.INVERTED);
        }
        if (block instanceof DoorBlock door && door.getBlockSetType().canOpenByHand()
                || block instanceof TrapdoorBlock && !state.isOf(Blocks.IRON_TRAPDOOR)
                || block instanceof FenceGateBlock) {
            return Set.of(Properties.OPEN);
        }
        return Set.of();
    }

    /**
     * Number of right-clicks needed to turn {@code actual} into {@code target}, or 0 if nothing can be done.
     */
    public static int clicksNeeded(BlockState actual, BlockState target) {
        if (actual.getBlock() != target.getBlock()) {
            return 0;
        }
        if (target.isOf(Blocks.REPEATER)) {
            return Math.floorMod(target.get(Properties.DELAY) - actual.get(Properties.DELAY), 4);
        }
        if (target.isOf(Blocks.NOTE_BLOCK)) {
            return Math.floorMod(target.get(Properties.NOTE) - actual.get(Properties.NOTE), 25);
        }
        for (Property<?> property : interactionProperties(target)) {
            if (!actual.get(property).equals(target.get(property))) {
                return 1;
            }
        }
        return 0;
    }
}

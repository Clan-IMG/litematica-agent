package net.clanimg.litematica_agent.placement;

import net.minecraft.block.AbstractCandleBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.DaylightDetectorBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.block.enums.SlabType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

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
     * Only right-clicks are missing: adjustments of a matching block (repeater delay, lighting a candle, ...) or the
     * tool conversion of a placed base block (dirt to farmland or dirt path).
     */
    public static boolean needsInteraction(BlockState world, BlockState target) {
        if (isConversionBase(world, target)) {
            return true;
        }
        return placementMatches(world, target, false) && countsEqual(world, target) && !interactionsMatch(world, target);
    }

    /** Items the agent right-clicks with to change a block after placing it. */
    public enum Tool {
        FLINT_AND_STEEL(Items.FLINT_AND_STEEL),
        SHOVEL(Items.IRON_SHOVEL),
        HOE(Items.IRON_HOE);

        private final Item creativeItem;

        Tool(Item creativeItem) {
            this.creativeItem = creativeItem;
        }

        /** The item taken from the creative inventory; in survival any matching item is used. */
        public Item creativeItem() {
            return this.creativeItem;
        }

        public boolean matches(ItemStack stack) {
            return switch (this) {
                case FLINT_AND_STEEL -> stack.isOf(Items.FLINT_AND_STEEL) || stack.isOf(Items.FIRE_CHARGE);
                case SHOVEL -> stack.isIn(ItemTags.SHOVELS);
                case HOE -> stack.isIn(ItemTags.HOES);
            };
        }
    }

    /**
     * What the next right-click from {@code world} towards {@code target} has to be done with; null means an empty
     * hand. Only meaningful while {@link #needsInteraction} is true.
     */
    public static @Nullable Tool toolFor(BlockState world, BlockState target) {
        if (target.isOf(Blocks.FARMLAND) && !world.isOf(Blocks.FARMLAND)) {
            return Tool.HOE;
        }
        if (target.isOf(Blocks.DIRT_PATH) && !world.isOf(Blocks.DIRT_PATH)) {
            return Tool.SHOVEL;
        }
        if (target.contains(Properties.LIT) && world.contains(Properties.LIT) && world.get(Properties.LIT) != target.get(Properties.LIT)) {
            if (target.get(Properties.LIT)) {
                return Tool.FLINT_AND_STEEL;
            }
            // Candles go out with an empty hand, campfires need a shovel.
            return world.getBlock() instanceof CampfireBlock ? Tool.SHOVEL : null;
        }
        return null;
    }

    /** The tool a target needs when it is built from scratch, or null if placing it is enough. */
    public static @Nullable Tool toolNeeded(BlockState target) {
        if (target.isOf(Blocks.FARMLAND)) {
            return Tool.HOE;
        }
        if (target.isOf(Blocks.DIRT_PATH)) {
            return Tool.SHOVEL;
        }
        if (target.getBlock() instanceof AbstractCandleBlock && target.get(Properties.LIT)) {
            return Tool.FLINT_AND_STEEL;
        }
        if (target.getBlock() instanceof CampfireBlock && !target.get(Properties.LIT)) {
            return Tool.SHOVEL;
        }
        return null;
    }

    /**
     * The state that is actually placed: farmland and dirt paths cannot be placed, they start as dirt and are
     * converted with a hoe or a shovel.
     */
    public static BlockState placementState(BlockState target) {
        if (target.isOf(Blocks.FARMLAND) || target.isOf(Blocks.DIRT_PATH)) {
            return Blocks.DIRT.getDefaultState();
        }
        return target;
    }

    private static boolean isConversionBase(BlockState world, BlockState target) {
        if (target.isOf(Blocks.FARMLAND)) {
            return world.isOf(Blocks.DIRT) || world.isOf(Blocks.GRASS_BLOCK) || world.isOf(Blocks.DIRT_PATH)
                    || world.isOf(Blocks.COARSE_DIRT);
        }
        if (target.isOf(Blocks.DIRT_PATH)) {
            return world.isOf(Blocks.DIRT) || world.isOf(Blocks.GRASS_BLOCK) || world.isOf(Blocks.PODZOL)
                    || world.isOf(Blocks.MYCELIUM) || world.isOf(Blocks.COARSE_DIRT) || world.isOf(Blocks.ROOTED_DIRT);
        }
        return false;
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
        if (block instanceof AbstractCandleBlock || block instanceof CampfireBlock) {
            return Set.of(Properties.LIT);
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

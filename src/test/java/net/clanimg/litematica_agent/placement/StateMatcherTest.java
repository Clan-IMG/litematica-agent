package net.clanimg.litematica_agent.placement;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.ChestType;
import net.minecraft.block.enums.ComparatorMode;
import net.minecraft.block.enums.SlabType;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateMatcherTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @Test
    void litCandleNeedsFlintAndSteel() {
        BlockState target = Blocks.CANDLE.getDefaultState().with(Properties.CANDLES, 2).with(Properties.LIT, true);
        BlockState placed = target.with(Properties.LIT, false);
        assertFalse(StateMatcher.isComplete(placed, target));
        assertTrue(StateMatcher.needsInteraction(placed, target));
        assertEquals(StateMatcher.Tool.FLINT_AND_STEEL, StateMatcher.toolFor(placed, target));
        assertEquals(StateMatcher.Tool.FLINT_AND_STEEL, StateMatcher.toolNeeded(target));
        assertTrue(StateMatcher.isComplete(target, target));
    }

    @Test
    void campfireIsPutOutWithAShovel() {
        BlockState target = Blocks.CAMPFIRE.getDefaultState().with(Properties.LIT, false);
        assertEquals(StateMatcher.Tool.SHOVEL, StateMatcher.toolFor(target.with(Properties.LIT, true), target));
        assertEquals(StateMatcher.Tool.SHOVEL, StateMatcher.toolNeeded(target));
    }

    @Test
    void farmlandIsPlacedAsDirtAndHoed() {
        BlockState farmland = Blocks.FARMLAND.getDefaultState();
        assertEquals(Blocks.DIRT.getDefaultState(), StateMatcher.placementState(farmland));
        assertTrue(StateMatcher.needsInteraction(Blocks.DIRT.getDefaultState(), farmland));
        assertEquals(StateMatcher.Tool.HOE, StateMatcher.toolFor(Blocks.GRASS_BLOCK.getDefaultState(), farmland));
        assertFalse(StateMatcher.needsInteraction(Blocks.STONE.getDefaultState(), farmland));
        assertTrue(StateMatcher.isComplete(farmland.with(Properties.MOISTURE, 7), farmland));
    }

    @Test
    void stairsNeedSameFacingAndHalf() {
        BlockState target = Blocks.OAK_STAIRS.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.EAST);
        assertTrue(StateMatcher.isComplete(target, target));
        assertFalse(StateMatcher.isComplete(target.with(Properties.HORIZONTAL_FACING, Direction.WEST), target));
        assertFalse(StateMatcher.isComplete(target.with(Properties.BLOCK_HALF, BlockHalf.TOP), target));
    }

    @Test
    void neighbourDrivenPropertiesAreIgnored() {
        BlockState target = Blocks.OAK_FENCE.getDefaultState().with(Properties.NORTH, true);
        BlockState world = Blocks.OAK_FENCE.getDefaultState().with(Properties.NORTH, false);
        assertTrue(StateMatcher.isComplete(world, target));
    }

    @Test
    void missingOrExtraWaterIsNotACompletedSchematicBlock() {
        BlockState dry = Blocks.STONE_SLAB.getDefaultState();
        BlockState wet = dry.with(Properties.WATERLOGGED, true);
        assertFalse(StateMatcher.isComplete(dry, wet));
        assertFalse(StateMatcher.isComplete(wet, dry));
        assertTrue(StateMatcher.isComplete(wet, wet));
    }

    @Test
    void doubleSlabNeedsTwoPlacements() {
        BlockState target = Blocks.OAK_SLAB.getDefaultState().with(Properties.SLAB_TYPE, SlabType.DOUBLE);
        BlockState single = Blocks.OAK_SLAB.getDefaultState().with(Properties.SLAB_TYPE, SlabType.BOTTOM);
        assertTrue(StateMatcher.isValidPlacement(single, target));
        assertFalse(StateMatcher.isComplete(single, target));
        assertTrue(StateMatcher.needsIncrement(single, target));
        assertTrue(StateMatcher.isComplete(target, target));
    }

    @Test
    void topSlabRejectsBottomPlacement() {
        BlockState target = Blocks.OAK_SLAB.getDefaultState().with(Properties.SLAB_TYPE, SlabType.TOP);
        BlockState bottom = Blocks.OAK_SLAB.getDefaultState().with(Properties.SLAB_TYPE, SlabType.BOTTOM);
        assertFalse(StateMatcher.isValidPlacement(bottom, target));
    }

    @Test
    void candlesCountUp() {
        BlockState target = Blocks.CANDLE.getDefaultState().with(Properties.CANDLES, 3);
        BlockState one = Blocks.CANDLE.getDefaultState().with(Properties.CANDLES, 1);
        BlockState four = Blocks.CANDLE.getDefaultState().with(Properties.CANDLES, 4);
        assertTrue(StateMatcher.isValidPlacement(one, target));
        assertTrue(StateMatcher.needsIncrement(one, target));
        assertFalse(StateMatcher.isValidPlacement(four, target));
    }

    @Test
    void repeaterDelayIsAdjustedByClicks() {
        BlockState target = Blocks.REPEATER.getDefaultState().with(Properties.DELAY, 3);
        BlockState placed = Blocks.REPEATER.getDefaultState().with(Properties.DELAY, 1);
        assertTrue(StateMatcher.isValidPlacement(placed, target));
        assertTrue(StateMatcher.needsInteraction(placed, target));
        assertEquals(2, StateMatcher.clicksNeeded(placed, target));
        assertEquals(3, StateMatcher.clicksNeeded(target.with(Properties.DELAY, 4), target.with(Properties.DELAY, 3)));
    }

    @Test
    void comparatorLeverNoteBlockAndDoors() {
        BlockState comparator = Blocks.COMPARATOR.getDefaultState().with(Properties.COMPARATOR_MODE, ComparatorMode.SUBTRACT);
        assertEquals(1, StateMatcher.clicksNeeded(Blocks.COMPARATOR.getDefaultState(), comparator));

        BlockState lever = Blocks.LEVER.getDefaultState().with(Properties.POWERED, true);
        assertTrue(StateMatcher.needsInteraction(Blocks.LEVER.getDefaultState(), lever));

        BlockState note = Blocks.NOTE_BLOCK.getDefaultState().with(Properties.NOTE, 7);
        assertEquals(7, StateMatcher.clicksNeeded(Blocks.NOTE_BLOCK.getDefaultState(), note));

        BlockState openDoor = Blocks.OAK_DOOR.getDefaultState().with(Properties.OPEN, true);
        assertTrue(StateMatcher.needsInteraction(Blocks.OAK_DOOR.getDefaultState(), openDoor));

        BlockState openIronDoor = Blocks.IRON_DOOR.getDefaultState().with(Properties.OPEN, true);
        assertTrue(StateMatcher.isComplete(Blocks.IRON_DOOR.getDefaultState(), openIronDoor),
                "iron doors are opened by redstone, not by hand");
    }

    @Test
    void chestHalfMayStaySingleUntilPartnerIsPlaced() {
        BlockState target = Blocks.CHEST.getDefaultState().with(Properties.CHEST_TYPE, ChestType.LEFT);
        BlockState single = Blocks.CHEST.getDefaultState();
        assertTrue(StateMatcher.isValidPlacement(single, target));
        assertFalse(StateMatcher.isValidPlacement(Blocks.CHEST.getDefaultState().with(Properties.CHEST_TYPE, ChestType.RIGHT), target));
    }

    @Test
    void differentBlockNeverMatches() {
        assertFalse(StateMatcher.isComplete(Blocks.DIRT.getDefaultState(), Blocks.STONE.getDefaultState()));
        assertFalse(StateMatcher.isValidPlacement(Blocks.DIRT.getDefaultState(), Blocks.STONE.getDefaultState()));
    }
}

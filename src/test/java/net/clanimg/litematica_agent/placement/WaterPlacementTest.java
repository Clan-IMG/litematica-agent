package net.clanimg.litematica_agent.placement;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.clanimg.litematica_agent.planning.BuildCategory;
import net.clanimg.litematica_agent.schematic.BuildTarget;
import net.clanimg.litematica_agent.schematic.SchematicAccess;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.SlabType;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import net.minecraft.world.EmptyBlockView;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterPlacementTest {
    private static final BlockPos POS = new BlockPos(0, 64, 0);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    private static BlockState water() {
        return Blocks.WATER.getDefaultState();
    }

    private static BlockState flowing(int level) {
        return Blocks.WATER.getDefaultState().with(Properties.LEVEL_15, level);
    }

    private static BlockState slab(SlabType type, boolean waterlogged) {
        return Blocks.STONE_SLAB.getDefaultState().with(Properties.SLAB_TYPE, type).with(Properties.WATERLOGGED, waterlogged);
    }

    @Test
    void waterTargetIsPouredIntoAirAndFlowingWaterOnly() {
        assertEquals(WaterPlacement.Action.FILL, WaterPlacement.actionFor(world(Blocks.AIR.getDefaultState()), POS, water()));
        assertEquals(WaterPlacement.Action.FILL, WaterPlacement.actionFor(world(flowing(3)), POS, water()));
        assertEquals(WaterPlacement.Action.NONE, WaterPlacement.actionFor(world(water()), POS, water()));
        // A wrong solid block is the business of the normal break logic, not of a bucket.
        assertEquals(WaterPlacement.Action.NONE, WaterPlacement.actionFor(world(Blocks.STONE.getDefaultState()), POS, water()));
    }

    @Test
    void waterloggedTargetGetsWaterFirstAndIsThenPlacedIntoIt() {
        BlockState target = slab(SlabType.BOTTOM, true);
        assertEquals(WaterPlacement.Action.FILL, WaterPlacement.actionFor(world(Blocks.AIR.getDefaultState()), POS, target));
        assertEquals(WaterPlacement.Action.FILL, WaterPlacement.actionFor(world(flowing(2)), POS, target));
        assertEquals(WaterPlacement.Action.NONE, WaterPlacement.actionFor(world(water()), POS, target));
        // Already built dry: pour water into it instead of breaking it.
        assertEquals(WaterPlacement.Action.WATERLOG, WaterPlacement.actionFor(world(slab(SlabType.BOTTOM, false)), POS, target));
        // A dry slab of the wrong half is a wrong block, not a missing waterlog.
        assertEquals(WaterPlacement.Action.NONE, WaterPlacement.actionFor(world(slab(SlabType.TOP, false)), POS, target));
    }

    @Test
    void dryTargetWithWaterInsideIsDrained() {
        BlockState target = slab(SlabType.TOP, false);
        assertEquals(WaterPlacement.Action.DRAIN, WaterPlacement.actionFor(world(slab(SlabType.TOP, true)), POS, target));
        assertEquals(WaterPlacement.Action.NONE, WaterPlacement.actionFor(world(slab(SlabType.TOP, false)), POS, target));
    }

    @Test
    void seagrassWaitsForWaterAndIsNeverDry() {
        BlockState seagrass = Blocks.SEAGRASS.getDefaultState();
        assertTrue(WaterPlacement.needsWater(seagrass));
        assertEquals(WaterPlacement.Action.FILL, WaterPlacement.actionFor(world(Blocks.AIR.getDefaultState()), POS, seagrass));
        assertEquals(WaterPlacement.Action.NONE, WaterPlacement.actionFor(world(water()), POS, seagrass));
        assertFalse(WaterPlacement.needsWater(Blocks.LILY_PAD.getDefaultState()));
        assertTrue(WaterPlacement.placedOnWater(Blocks.LILY_PAD.getDefaultState()));
    }

    @Test
    void onlyWaterWithTwoSourceNeighboursAndGroundBelowIsRenewable() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int x = -1; x <= 3; x++) {
            blocks.put(new BlockPos(x, 63, 0), Blocks.STONE.getDefaultState());
        }
        blocks.put(new BlockPos(0, 64, 0), water());
        blocks.put(new BlockPos(1, 64, 0), water());
        blocks.put(new BlockPos(2, 64, 0), water());
        BlockView world = world(blocks);
        assertTrue(WaterPlacement.isRenewable(world, new BlockPos(1, 64, 0)));
        assertFalse(WaterPlacement.isRenewable(world, new BlockPos(0, 64, 0)), "an end of the row has only one neighbour");
        assertEquals(new BlockPos(1, 64, 0), WaterPlacement.findRenewable(world, new BlockPos(3, 64, 3), 5));

        // The same row without ground below does not refill.
        blocks.remove(new BlockPos(1, 63, 0));
        assertFalse(WaterPlacement.isRenewable(world, new BlockPos(1, 64, 0)));
        assertEquals(null, WaterPlacement.findRenewable(world, new BlockPos(3, 64, 3), 5));
    }

    @Test
    void waterComesAfterWallsAndAquaticBlocksLast() {
        assertEquals(BuildCategory.FLUID, category(water()));
        assertEquals(BuildCategory.AQUATIC, category(slab(SlabType.BOTTOM, true)));
        assertEquals(BuildCategory.AQUATIC, category(Blocks.SEAGRASS.getDefaultState()));
        assertEquals(BuildCategory.AQUATIC, category(Blocks.LILY_PAD.getDefaultState()));
        assertEquals(BuildCategory.SOLID, category(slab(SlabType.BOTTOM, false)));
        assertTrue(BuildCategory.FLUID.ordinal() > BuildCategory.POWER_SOURCE.ordinal());
        assertTrue(BuildCategory.AQUATIC.ordinal() > BuildCategory.FLUID.ordinal());
    }

    @Test
    void onlyAStillSourceCompletesAWaterTarget() {
        assertTrue(StateMatcher.isComplete(water(), water()));
        assertFalse(StateMatcher.isComplete(flowing(1), water()));
        assertFalse(StateMatcher.isComplete(Blocks.AIR.getDefaultState(), water()));
    }

    @Test
    void schematicWaterSourcesBecomeBucketTargetsAndFlowingWaterIsLeftOut() {
        Long2ObjectLinkedOpenHashMap<BlockState> states = new Long2ObjectLinkedOpenHashMap<>();
        states.put(new BlockPos(0, 64, 0).asLong(), water());
        states.put(new BlockPos(1, 64, 0).asLong(), flowing(4));
        SchematicAccess.SchematicReadResult result = SchematicAccess.toResult(states, Map.of());
        List<BuildTarget> targets = result.targets();
        assertEquals(1, targets.size());
        assertEquals(Items.WATER_BUCKET, targets.get(0).item());
        assertEquals(new BlockPos(0, 64, 0), targets.get(0).pos());
        assertTrue(result.unsupported().isEmpty(), () -> "flowing water is no problem: " + result.unsupported());
    }

    private static BuildCategory category(BlockState state) {
        return BuildCategory.classify(state, EmptyBlockView.INSTANCE, POS);
    }

    private static BlockView world(BlockState atPos) {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        blocks.put(POS, atPos);
        return world(blocks);
    }

    private static BlockView world(Map<BlockPos, BlockState> blocks) {
        return new BlockView() {
            @Override
            public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
                return null;
            }

            @Override
            public BlockState getBlockState(BlockPos pos) {
                return blocks.getOrDefault(pos, Blocks.AIR.getDefaultState());
            }

            @Override
            public FluidState getFluidState(BlockPos pos) {
                return this.getBlockState(pos).getFluidState();
            }

            @Override
            public int getHeight() {
                return 384;
            }

            @Override
            public int getBottomY() {
                return -64;
            }
        };
    }
}

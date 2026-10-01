package net.clanimg.litematica_agent.gametest;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.clanimg.litematica_agent.placement.StateMatcher;
import net.clanimg.litematica_agent.schematic.BuildTarget;
import net.clanimg.litematica_agent.schematic.SchematicAccess;
import net.clanimg.litematica_agent.schematic.SchematicAccess.SchematicReadResult;
import net.clanimg.litematica_agent.schematic.SchematicAccess.UnsupportedBlock;
import net.clanimg.litematica_agent.schematic.SurvivalCheck;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Preflight around water: the builder pours water itself (FluidTask), so aquatic targets in a dry world are kept -
 * seagrass, waterlogged slabs, and a lily pad on water that only the schematic contains. Waterlogging must still match
 * exactly, and command-only blocks stay excluded with a diagnosis.
 */
public final class AquaticPreflightGameTest implements FabricClientGameTest {
    private static final BlockPos DRY_SEAGRASS = new BlockPos(6, -59, 6);
    private static final BlockPos WET_SEAGRASS = new BlockPos(10, -59, 6);
    private static final BlockPos DRY_SLAB = new BlockPos(6, -59, 8);
    private static final BlockPos WET_SLAB = new BlockPos(14, -59, 6);
    private static final BlockPos DRY_LILY = new BlockPos(6, -58, 10);
    private static final BlockPos WET_LILY = WET_SEAGRASS.up();

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientWorld().waitForChunksRender();
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamemode survival @a");
            server.runCommand("difficulty peaceful");
            server.runCommand("tp @a 8 -59 12");
            server.runCommand("fill 4 -60 4 18 -60 12 dirt");
            server.runCommand("fill 4 -59 4 18 -57 12 air");
            // Two enclosed source blocks prevent the fixture from wetting the dry controls through fluid ticks.
            server.runCommand("fill 9 -59 5 11 -59 7 stone");
            server.runCommand("setblock 10 -59 6 water");
            server.runCommand("fill 13 -59 5 15 -59 7 stone");
            server.runCommand("setblock 14 -59 6 water");
            context.waitTicks(20);

            context.runOnClient(client -> {
                require(!client.world.getFluidState(DRY_SEAGRASS).isIn(FluidTags.WATER), "Dry control became wet");
                require(client.world.getFluidState(WET_SEAGRASS).isIn(FluidTags.WATER), "Wet fixture has no water");
                require(client.world.getBlockState(DRY_SEAGRASS.down()).isOf(Blocks.DIRT), "Dry support missing");
                require(client.world.getBlockState(WET_SEAGRASS.down()).isOf(Blocks.DIRT), "Wet support missing");

                BlockState wetSlab = Blocks.OAK_SLAB.getDefaultState().with(Properties.WATERLOGGED, true);
                List<BuildTarget> targets = List.of(
                        new BuildTarget(DRY_SEAGRASS, Blocks.SEAGRASS.getDefaultState(), Items.SEAGRASS, 1),
                        new BuildTarget(WET_SEAGRASS, Blocks.SEAGRASS.getDefaultState(), Items.SEAGRASS, 1),
                        new BuildTarget(DRY_SLAB, wetSlab, Items.OAK_SLAB, 1),
                        new BuildTarget(WET_SLAB, wetSlab, Items.OAK_SLAB, 1),
                        new BuildTarget(DRY_LILY, Blocks.LILY_PAD.getDefaultState(), Items.LILY_PAD, 1),
                        new BuildTarget(WET_LILY, Blocks.LILY_PAD.getDefaultState(), Items.LILY_PAD, 1));
                Long2ObjectLinkedOpenHashMap<BlockState> states = new Long2ObjectLinkedOpenHashMap<>();
                for (BuildTarget target : targets) {
                    states.put(target.pos().asLong(), target.state());
                }
                // This water exists only in the schematic; the builder pours it before the lily pad goes on top.
                states.put(DRY_LILY.down().asLong(), Blocks.WATER.getDefaultState());
                SchematicReadResult checked = SurvivalCheck.apply(new SchematicReadResult(targets, List.of(), states),
                        client.world);

                assertKept(checked, DRY_SEAGRASS);
                assertKept(checked, DRY_SLAB);
                assertKept(checked, DRY_LILY);
                assertKept(checked, WET_SEAGRASS);
                assertKept(checked, WET_SLAB);
                assertKept(checked, WET_LILY);
                require(checked.targets().size() == 6 && checked.unsupported().isEmpty(),
                        "Unexpected preflight counts: " + checked);

                BlockState drySlab = wetSlab.with(Properties.WATERLOGGED, false);
                require(!StateMatcher.isComplete(drySlab, wetSlab), "Dry slab incorrectly completes wet target");
                require(!StateMatcher.isComplete(wetSlab, drySlab), "Wet slab incorrectly completes dry target");
                require(StateMatcher.isComplete(wetSlab, wetSlab), "Matching waterlogged slab must complete");
                require(StateMatcher.isComplete(drySlab, drySlab), "Matching dry slab must complete");

                assertUnobtainableBlocks();
                GameTestSupport.LOGGER.info("Aquatic preflight passed: aquatic targets kept for the bucket builder, "
                        + "schematic water supports a lily pad, waterlogged completion exact, command blocks excluded");
            });
        }
    }

    private static void assertUnobtainableBlocks() {
        List<Block> blocks = List.of(Blocks.LIGHT, Blocks.BARRIER, Blocks.BEDROCK, Blocks.COMMAND_BLOCK,
                Blocks.CHAIN_COMMAND_BLOCK, Blocks.REPEATING_COMMAND_BLOCK, Blocks.STRUCTURE_BLOCK,
                Blocks.JIGSAW, Blocks.END_PORTAL_FRAME, Blocks.SPAWNER);
        Long2ObjectLinkedOpenHashMap<BlockState> states = new Long2ObjectLinkedOpenHashMap<>();
        List<BlockPos> positions = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            BlockPos pos = new BlockPos(i, -58, 14);
            positions.add(pos);
            states.put(pos.asLong(), blocks.get(i).getDefaultState());
        }
        SchematicReadResult checked = SchematicAccess.toResult(states,
                SchematicAccess.requirementsFor(new HashSet<>(states.values())));
        require(checked.targets().isEmpty(), "Command-only blocks were added to executable build targets");
        require(checked.unsupported().size() == blocks.size(), "Command-only blocks disappeared without diagnosis");
        for (BlockPos pos : positions) {
            assertRejected(checked, pos, UnsupportedBlock.Reason.SURVIVAL_UNOBTAINABLE);
        }
    }

    private static void assertRejected(SchematicReadResult result, BlockPos pos, UnsupportedBlock.Reason reason) {
        require(result.targets().stream().noneMatch(target -> target.pos().equals(pos)),
                "Impossible block remains executable at " + pos);
        require(result.unsupported().stream().anyMatch(block -> block.pos().equals(pos) && block.reason() == reason),
                "Expected preflight reason " + reason + " at " + pos + ": " + result.unsupported());
    }

    private static void assertKept(SchematicReadResult result, BlockPos pos) {
        require(result.targets().stream().anyMatch(target -> target.pos().equals(pos)),
                "Supported wet target was rejected at " + pos + ": " + result.unsupported());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

package net.clanimg.litematica_agent.gametest;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.clanimg.litematica_agent.agent.AgentManager;
import net.clanimg.litematica_agent.agent.AgentSession;
import net.clanimg.litematica_agent.agent.SessionState;
import net.clanimg.litematica_agent.config.AgentConfig;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Survival build of a pond: 50 water sources poured with only four buckets from the storage (refilled on site from
 * the agent's own water), a waterlogged slab and seagrass on the pond floor, sea pickles, a waterlogged top slab at
 * the surface and a lily pad on top - everything verified against the schematic afterwards.
 */
public final class WaterBuildGameTest implements FabricClientGameTest {
    private static final BlockPos MIN = new BlockPos(6, -60, 6);
    private static final BlockPos MAX = new BlockPos(12, -57, 12);
    private static final BlockPos[] CHESTS = {new BlockPos(0, -60, -4), new BlockPos(2, -60, -4)};

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSupport.logMemory("before " + getClass().getSimpleName());
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientWorld().waitForChunksRender();
            TestServerContext server = singleplayer.getServer();
            server.runCommand("difficulty peaceful");
            server.runCommand("gamemode survival @a");
            server.runCommand("clear @a");
            server.runCommand("tp @a 2 -60 -2 180 30");

            server.runCommand("setblock 0 -60 -4 chest[facing=south]{Items:["
                    + "{Slot:0b,id:\"minecraft:stone\",count:64},{Slot:1b,id:\"minecraft:stone\",count:64},"
                    + "{Slot:2b,id:\"minecraft:stone_slab\",count:8},{Slot:3b,id:\"minecraft:seagrass\",count:4},"
                    + "{Slot:4b,id:\"minecraft:sea_pickle\",count:4},{Slot:5b,id:\"minecraft:lily_pad\",count:4}]}");
            server.runCommand("setblock 2 -60 -4 chest[facing=south]{Items:["
                    + "{Slot:0b,id:\"minecraft:water_bucket\",count:1},{Slot:1b,id:\"minecraft:water_bucket\",count:1},"
                    + "{Slot:2b,id:\"minecraft:water_bucket\",count:1},{Slot:3b,id:\"minecraft:water_bucket\",count:1},"
                    + "{Slot:4b,id:\"minecraft:bread\",count:16},{Slot:5b,id:\"minecraft:cobblestone\",count:64}]}");

            // A raised stone pond, two blocks deep, with life in it.
            server.runCommand("fill 6 -60 6 12 -60 12 stone");
            server.runCommand("fill 6 -59 6 12 -58 12 stone hollow");
            server.runCommand("fill 7 -59 7 11 -58 11 water");
            server.runCommand("setblock 8 -59 8 stone_slab[type=bottom,waterlogged=true]");
            server.runCommand("setblock 10 -59 10 seagrass");
            server.runCommand("setblock 9 -59 10 sea_pickle[pickles=2,waterlogged=true]");
            server.runCommand("setblock 11 -58 9 stone_slab[type=top,waterlogged=true]");
            server.runCommand("setblock 9 -57 9 lily_pad");
            context.waitTicks(20);

            SchematicPlacement placement = context.computeOnClient(client ->
                    GameTestSupport.createPlacement(client, "agent-pond", MIN, MAX));
            server.runCommand("fill 6 -60 6 12 -57 12 air");
            context.waitFor(client -> client.world.getBlockState(new BlockPos(9, -58, 9)).isAir()
                    && client.world.getBlockState(MIN).isAir(), 100);
            context.waitTicks(10);

            context.runOnClient(client -> {
                AgentManager.get().config().verboseLogging = true;
                AgentManager.get().config().wrongBlockMode = AgentConfig.WrongBlockMode.SKIP;
                AgentManager.get().worldData().storageHomeCommand = "";
                AgentManager.get().worldData().buildHomeCommand = "";
                AgentManager.get().startNew();
            });
            GameTestSupport.awaitLoading(context);
            context.waitTicks(5);
            int sessionId = context.computeOnClient(client -> AgentManager.get().sessions().get(0).id);
            context.waitFor(client -> {
                AgentSession session = AgentManager.get().findSession(sessionId);
                return session != null && session.state == SessionState.SCANNING_STORAGE;
            }, 200);
            int unsupported = context.computeOnClient(client -> AgentManager.get().focused().unsupported().size());
            if (unsupported != 0) {
                throw new AssertionError("The pond has no survival-impossible block, but preflight excluded " + unsupported);
            }

            for (BlockPos chest : CHESTS) {
                context.runOnClient(client -> client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND,
                        new BlockHitResult(Vec3d.ofCenter(chest).add(0.0, 0.5, 0.0), Direction.UP, chest, false)));
                context.waitForScreen(GenericContainerScreen.class);
                context.waitTicks(5);
                GameTestSupport.clickButton(context, "scan.all");
                context.waitTicks(20);
                context.runOnClient(client -> client.player.closeHandledScreen());
                context.waitTicks(5);
            }
            context.waitFor(client -> AgentManager.get().findSession(sessionId).state == SessionState.READY, 200);
            context.takeScreenshot("water-01-ready");

            GameTestSupport.resetRotationCheck();
            context.runOnClient(client -> AgentManager.get().begin(sessionId));
            GameTestSupport.awaitLoading(context);
            context.waitTicks(600);
            context.takeScreenshot("water-02-building");

            AgentSession paused = GameTestSupport.waitForAgent(context, sessionId, 20 * 60 * 10);
            context.takeScreenshot("water-03-finished");
            if (paused != null) {
                throw new AssertionError("Agent paused: " + paused.pauseReason + " " + paused.pauseArgs);
            }
            GameTestSupport.assertBuilt(context, placement, "water");
            context.runOnClient(client -> {
                var world = client.world;
                require(world.getBlockState(new BlockPos(8, -59, 8)).get(Properties.WATERLOGGED), "floor slab is not waterlogged");
                require(world.getBlockState(new BlockPos(11, -58, 9)).get(Properties.WATERLOGGED), "top slab is not waterlogged");
                require(world.getBlockState(new BlockPos(10, -59, 10)).isOf(Blocks.SEAGRASS), "seagrass missing");
                require(world.getBlockState(new BlockPos(9, -57, 9)).isOf(Blocks.LILY_PAD), "lily pad missing");
                int sources = 0;
                for (BlockPos pos : BlockPos.iterate(7, -59, 7, 11, -58, 11)) {
                    if (world.getFluidState(pos).isStill()) {
                        sources++;
                    }
                }
                require(sources == 50, "expected 50 still water blocks in the pond, found " + sources);
            });
            GameTestSupport.assertNoRotationMismatches("water");
            GameTestSupport.LOGGER.info("[water] PASS: pond with 50 sources, waterlogged slabs, seagrass, sea pickles and lily pad");
            context.runOnClient(client -> GameTestSupport.removePlacement(client, placement));
        }
        GameTestSupport.logMemory("after " + getClass().getSimpleName());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

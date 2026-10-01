package net.clanimg.litematica_agent.gametest;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.clanimg.litematica_agent.agent.AgentManager;
import net.clanimg.litematica_agent.agent.AgentSession;
import net.clanimg.litematica_agent.config.AgentConfig;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.util.math.BlockPos;

/**
 * Creative end-to-end test: the agent rebuilds a small hut with orientation-sensitive blocks and redstone parts
 * that are adjusted after placement. It runs at the fastest speed, which must still build every block exactly.
 */
public class CreativeBuildGameTest implements FabricClientGameTest {
    private static final BlockPos MIN = new BlockPos(5, -60, 5);
    private static final BlockPos MAX = new BlockPos(11, -55, 11);

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSupport.logMemory("before " + getClass().getSimpleName());
        GameTestSupport.resetRotationCheck();
        context.runOnClient(client -> {
            AgentManager.get().config().speed = AgentConfig.FASTEST_SPEED;
            AgentManager.get().config().verboseLogging = true;
        });
        try {
            this.buildAndCheck(context);
        } finally {
            context.runOnClient(client -> AgentManager.get().config().speed = AgentConfig.DEFAULT_SPEED);
        }
    }

    private void buildAndCheck(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientWorld().waitForChunksRender();
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamemode creative @a");
            server.runCommand("tp @a 2 -60 2 -45 20");
            buildHut(server);
            context.waitTicks(20);

            SchematicPlacement placement = context.computeOnClient(client ->
                    GameTestSupport.createPlacement(client, "agent-creative", MIN, MAX));
            server.runCommand("fill 5 -60 5 11 -55 11 air");
            context.waitTicks(20);
            context.takeScreenshot("creative-01-placement");

            context.runOnClient(client -> AgentManager.get().startNew());

            GameTestSupport.awaitLoading(context);
            context.waitTicks(5);
            int sessionId = context.computeOnClient(client -> AgentManager.get().sessions().get(0).id);
            context.runOnClient(client -> AgentManager.get().begin(sessionId));
            GameTestSupport.awaitLoading(context);
            context.waitTicks(200);
            context.takeScreenshot("creative-02-building");

            AgentSession paused = GameTestSupport.waitForAgent(context, sessionId, 20 * 60 * 6);
            context.takeScreenshot("creative-03-finished");
            if (paused != null) {
                throw new AssertionError("Agent paused: " + paused.pauseReason + " " + paused.pauseArgs);
            }
            GameTestSupport.assertBuilt(context, placement, "creative");
            // The observer facing up on the floor can only be placed with a look trick, which anti-cheats notice.
            GameTestSupport.LOGGER.info("Right-clicks with look tricks in the creative hut: {}", GameTestSupport.rotationMismatches());
            context.runOnClient(client -> GameTestSupport.removePlacement(client, placement));
        }
    }

    private static void buildHut(TestServerContext server) {
        server.runCommand("fill 5 -60 5 11 -60 11 stone_bricks");
        server.runCommand("fill 5 -59 5 11 -57 11 oak_planks hollow");
        server.runCommand("fill 5 -56 5 11 -56 11 oak_planks");
        server.runCommand("fill 6 -59 6 10 -57 10 air");

        server.runCommand("setblock 8 -59 11 oak_door[facing=north,half=lower,hinge=left]");
        server.runCommand("setblock 8 -58 11 oak_door[facing=north,half=upper,hinge=left]");
        server.runCommand("setblock 5 -58 8 glass");
        server.runCommand("setblock 11 -58 8 glass_pane");

        server.runCommand("setblock 6 -59 6 oak_stairs[facing=east]");
        server.runCommand("setblock 7 -59 6 stone_brick_stairs[facing=south,half=top]");
        server.runCommand("setblock 8 -59 6 oak_slab[type=top]");
        server.runCommand("setblock 9 -59 6 smooth_stone_slab[type=double]");
        server.runCommand("setblock 10 -59 6 oak_log[axis=x]");
        server.runCommand("setblock 10 -59 7 chest[facing=west]");
        server.runCommand("setblock 10 -59 8 furnace[facing=north]");
        server.runCommand("setblock 6 -59 8 observer[facing=up]");
        server.runCommand("setblock 6 -59 9 piston[facing=west]");
        server.runCommand("setblock 7 -59 9 hopper[facing=east]");
        server.runCommand("setblock 8 -59 9 repeater[facing=north,delay=3]");
        server.runCommand("setblock 9 -59 9 comparator[facing=south,mode=subtract]");
        server.runCommand("setblock 7 -58 7 oak_trapdoor[facing=north,half=top,open=true]");
        server.runCommand("setblock 6 -58 10 wall_torch[facing=east]");
        server.runCommand("setblock 10 -58 10 ladder[facing=west]");
        server.runCommand("setblock 6 -59 10 lever[face=wall,facing=east,powered=true]");
        server.runCommand("setblock 9 -59 10 note_block[note=7]");
        server.runCommand("setblock 8 -55 8 candle[candles=3]");
    }
}

package net.clanimg.litematica_agent.gametest;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.clanimg.litematica_agent.agent.AgentManager;
import net.clanimg.litematica_agent.agent.AgentSession;
import net.clanimg.litematica_agent.agent.SessionState;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * The storage is far away from the build site, like on a city-build server: the agent uses the configured home
 * commands to get to the storage and back.
 */
public class HomeTravelGameTest implements FabricClientGameTest {
    private static final BlockPos MIN = new BlockPos(5, -60, 5);
    private static final BlockPos MAX = new BlockPos(7, -59, 7);
    private static final BlockPos CHEST = new BlockPos(160, -60, 0);

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSupport.logMemory("before " + getClass().getSimpleName());
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientWorld().waitForChunksRender();
            TestServerContext server = singleplayer.getServer();
            context.runOnClient(client -> {
                AgentManager.get().config().verboseLogging = true;
                client.options.getViewDistance().setValue(6);
            });
            server.runCommand("difficulty peaceful");
            server.runCommand("gamemode survival @a");
            server.runCommand("clear @a");
            server.runOnServer(minecraftServer -> minecraftServer.getPlayerManager().getPlayerList()
                    .forEach(player -> minecraftServer.getPlayerManager().addToOperators(player.getPlayerConfigEntry())));
            server.runCommand("forceload add 160 0");
            server.runCommand("setblock 160 -60 0 chest[facing=south]{Items:[{Slot:0b,id:\"minecraft:bricks\",count:64}]}");

            server.runCommand("fill 5 -60 5 7 -59 7 bricks");
            server.runCommand("tp @a 3 -60 3");
            context.waitTicks(40);
            SchematicPlacement placement = context.computeOnClient(client ->
                    GameTestSupport.createPlacement(client, "agent-home", MIN, MAX));
            server.runCommand("fill 5 -60 5 7 -59 7 air");
            context.waitTicks(10);

            context.runOnClient(client -> {
                AgentManager.get().worldData().storageHomeCommand = "tp @s 160 -60 2";
                AgentManager.get().worldData().buildHomeCommand = "tp @s 3 -60 3";
                AgentManager.get().startNew();
            });
            context.waitTicks(10);
            int sessionId = context.computeOnClient(client -> AgentManager.get().sessions().get(0).id);

            // The player walks (here: is teleported) to the storage once to scan it.
            server.runCommand("tp @a 160 -60 2 180 30");
            context.waitFor(client -> client.world.isChunkLoaded(CHEST.getX() >> 4, CHEST.getZ() >> 4)
                    && client.world.getBlockState(CHEST).isOf(net.minecraft.block.Blocks.CHEST), 20 * 60);
            context.waitTicks(20);
            context.runOnClient(client -> client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND,
                    new BlockHitResult(Vec3d.ofCenter(CHEST).add(0.0, 0.5, 0.0), Direction.UP, CHEST, false)));
            context.waitForScreen(GenericContainerScreen.class);
            context.waitTicks(5);
            GameTestSupport.clickButton(context, "scan.all");
            context.waitTicks(10);
            context.runOnClient(client -> client.player.closeHandledScreen());
            server.runCommand("tp @a 3 -60 3");
            context.waitFor(client -> client.world.isChunkLoaded(0, 0), 20 * 60);
            context.waitFor(client -> {
                AgentSession session = AgentManager.get().findSession(sessionId);
                return session != null && session.state == SessionState.READY;
            }, 200);

            context.runOnClient(client -> AgentManager.get().begin(sessionId));
            AgentSession paused = GameTestSupport.waitForAgent(context, sessionId, 20 * 60 * 4);
            context.takeScreenshot("home-01-finished");
            if (paused != null) {
                throw new AssertionError("Agent paused: " + paused.pauseReason + " " + paused.pauseArgs);
            }
            GameTestSupport.assertBuilt(context, placement, "home");
            double distance = context.computeOnClient(client -> client.player.getEntityPos().distanceTo(Vec3d.ofCenter(MIN)));
            if (distance > 16) {
                throw new AssertionError("The agent should end at the build site, but is " + distance + " blocks away");
            }
        }
    }
}

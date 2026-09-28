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
 * Survival without flight: a tower that needs helper blocks to climb, eating while building, and the wrong-block
 * safety rule.
 */
public class SurvivalAdvancedGameTest implements FabricClientGameTest {
    private static final BlockPos TOWER_MIN = new BlockPos(6, -60, 6);
    private static final BlockPos TOWER_MAX = new BlockPos(6, -53, 6);
    private static final BlockPos FLOOR_MIN = new BlockPos(10, -60, 2);
    private static final BlockPos FLOOR_MAX = new BlockPos(12, -60, 4);
    private static final BlockPos CHEST = new BlockPos(2, -60, -4);

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSupport.logMemory("before " + getClass().getSimpleName());
        GameTestSupport.resetRotationCheck();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientWorld().waitForChunksRender();
            TestServerContext server = singleplayer.getServer();
            context.runOnClient(client -> AgentManager.get().config().verboseLogging = true);
            server.runCommand("difficulty easy");
            server.runCommand("gamemode survival @a");
            server.runCommand("clear @a");
            server.runCommand("tp @a 2 -60 -2 180 30");
            server.runCommand("setblock 2 -60 -4 chest[facing=south]{Items:[{Slot:0b,id:\"minecraft:stone\",count:32},"
                    + "{Slot:1b,id:\"minecraft:cobblestone\",count:64},{Slot:2b,id:\"minecraft:bread\",count:16}]}");

            server.runCommand("fill 6 -60 6 6 -53 6 stone");
            context.waitTicks(20);
            SchematicPlacement tower = context.computeOnClient(client ->
                    GameTestSupport.createPlacement(client, "agent-tower", TOWER_MIN, TOWER_MAX));
            server.runCommand("fill 6 -60 6 6 -53 6 air");
            context.waitTicks(10);

            context.runOnClient(client -> AgentManager.get().startNew());
            context.waitTicks(10);
            int towerSession = context.computeOnClient(client -> AgentManager.get().sessions().get(0).id);
            scanChest(context);
            context.waitFor(client -> state(towerSession) == SessionState.READY, 100);

            // The test opened the chest itself without looking at it; only the agent's clicks count.
            GameTestSupport.resetRotationCheck();
            context.runOnClient(client -> AgentManager.get().begin(towerSession));
            context.waitTicks(200);
            // Make the player hungry in the middle of the build: the agent has to eat by itself.
            server.runOnServer(minecraftServer -> minecraftServer.getPlayerManager().getPlayerList()
                    .forEach(player -> player.getHungerManager().setFoodLevel(4)));
            context.waitFor(client -> client.player.getHungerManager().getFoodLevel() <= 6, 100);
            context.waitFor(client -> client.player.getHungerManager().getFoodLevel() > 6, 20 * 60);
            context.takeScreenshot("advanced-01-ate");

            AgentSession paused = GameTestSupport.waitForAgent(context, towerSession, 20 * 60 * 5);
            context.takeScreenshot("advanced-02-tower");
            if (paused != null) {
                throw new AssertionError("Tower session paused: " + paused.pauseReason + " " + paused.pauseArgs);
            }
            GameTestSupport.assertBuilt(context, tower, "tower");
            int helpersLeft = context.computeOnClient(client -> {
                int count = 0;
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        for (int y = -60; y <= -50; y++) {
                            if (client.world.getBlockState(new BlockPos(6 + dx, y, 6 + dz)).isOf(net.minecraft.block.Blocks.COBBLESTONE)) {
                                count++;
                            }
                        }
                    }
                }
                return count;
            });
            if (helpersLeft > 0) {
                throw new AssertionError(helpersLeft + " helper blocks were left around the tower");
            }

            // A new survival session requires an empty inventory, so the leftovers go back first.
            context.runOnClient(client -> AgentManager.get().deposit());
            context.waitFor(client -> AgentManager.get().activeAgent() == null, 20 * 90);

            // Wrong block: the agent must stop instead of destroying it.
            server.runCommand("fill 10 -60 2 12 -60 4 stone");
            context.waitTicks(20);
            SchematicPlacement floor = context.computeOnClient(client ->
                    GameTestSupport.createPlacement(client, "agent-floor", FLOOR_MIN, FLOOR_MAX));
            server.runCommand("fill 10 -60 2 12 -60 4 air");
            server.runCommand("setblock 11 -60 3 oak_planks");
            context.waitTicks(10);
            context.runOnClient(client -> AgentManager.get().startNew());
            context.waitTicks(10);
            int floorSession = context.computeOnClient(client -> AgentManager.get().sessions().get(0).id);
            context.waitFor(client -> state(floorSession) == SessionState.CONFIRM_STORAGE, 100);
            context.runOnClient(client -> AgentManager.get().storageKeep(floorSession));
            context.waitFor(client -> state(floorSession) == SessionState.READY, 100);
            context.runOnClient(client -> AgentManager.get().begin(floorSession));
            AgentSession wrong = GameTestSupport.waitForAgent(context, floorSession, 20 * 60 * 2);
            if (wrong == null || !"pause.wrong_block".equals(wrong.pauseReason)) {
                throw new AssertionError("Expected a wrong-block pause but got " + (wrong == null ? "completion" : wrong.pauseReason));
            }
            boolean plankKept = context.computeOnClient(client ->
                    client.world.getBlockState(new BlockPos(11, -60, 3)).isOf(net.minecraft.block.Blocks.OAK_PLANKS));
            if (!plankKept) {
                throw new AssertionError("The agent destroyed a block it was not allowed to touch");
            }
            context.takeScreenshot("advanced-03-wrong-block");

            context.runOnClient(client -> AgentManager.get().answerApproval(true));
            AgentSession pausedAgain = GameTestSupport.waitForAgent(context, floorSession, 20 * 60 * 3);
            if (pausedAgain != null) {
                throw new AssertionError("Floor session paused again: " + pausedAgain.pauseReason + " " + pausedAgain.pauseArgs);
            }
            GameTestSupport.assertBuilt(context, floor, "floor");
            GameTestSupport.assertNoRotationMismatches("tower and floor");
        }
    }

    private static SessionState state(int sessionId) {
        AgentSession session = AgentManager.get().findSession(sessionId);
        return session == null ? null : session.state;
    }

    private static void scanChest(ClientGameTestContext context) {
        context.runOnClient(client -> client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND,
                new BlockHitResult(Vec3d.ofCenter(CHEST).add(0.0, 0.5, 0.0), Direction.UP, CHEST, false)));
        context.waitForScreen(GenericContainerScreen.class);
        context.waitTicks(5);
        GameTestSupport.clickButton(context, "scan.all");
        context.waitTicks(10);
        context.runOnClient(client -> client.player.closeHandledScreen());
        context.waitTicks(5);
    }
}

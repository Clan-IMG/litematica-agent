package net.clanimg.litematica_agent.gametest;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.clanimg.litematica_agent.agent.AgentManager;
import net.clanimg.litematica_agent.agent.AgentSession;
import net.clanimg.litematica_agent.agent.SessionState;
import net.clanimg.litematica_agent.inventory.InventoryHelper;
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
 * Survival end-to-end test: storage scan through the chest menu buttons, then the agent walks, restocks from the
 * chests on its own and builds.
 */
public class SurvivalBuildGameTest implements FabricClientGameTest {
    private static final BlockPos MIN = new BlockPos(6, -60, 6);
    private static final BlockPos MAX = new BlockPos(10, -58, 10);
    private static final BlockPos[] CHESTS = {new BlockPos(0, -60, -4), new BlockPos(2, -60, -4), new BlockPos(4, -60, -4)};

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSupport.logMemory("before " + getClass().getSimpleName());
        GameTestSupport.resetRotationCheck();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientWorld().waitForChunksRender();
            TestServerContext server = singleplayer.getServer();
            server.runCommand("difficulty peaceful");
            server.runCommand("gamemode survival @a");
            server.runCommand("clear @a");
            server.runCommand("tp @a 2 -60 -2 180 30");

            server.runCommand("setblock 0 -60 -4 chest[facing=south]{Items:[{Slot:0b,id:\"minecraft:stone_bricks\",count:64},{Slot:1b,id:\"minecraft:oak_planks\",count:64}]}");
            server.runCommand("setblock 2 -60 -4 chest[facing=south]{Items:[{Slot:0b,id:\"minecraft:oak_stairs\",count:8},{Slot:1b,id:\"minecraft:oak_slab\",count:8},{Slot:2b,id:\"minecraft:glass\",count:8},{Slot:3b,id:\"minecraft:torch\",count:8}]}");
            server.runCommand("setblock 4 -60 -4 chest[facing=south]{Items:[{Slot:0b,id:\"minecraft:bread\",count:16},{Slot:1b,id:\"minecraft:cobblestone\",count:64}]}");

            server.runCommand("fill 6 -60 6 10 -60 10 stone_bricks");
            server.runCommand("fill 6 -59 6 10 -58 10 oak_planks hollow");
            server.runCommand("fill 7 -59 7 9 -58 9 air");
            server.runCommand("setblock 8 -58 6 glass");
            server.runCommand("setblock 7 -59 7 oak_stairs[facing=south]");
            server.runCommand("setblock 9 -59 9 oak_slab[type=top]");
            server.runCommand("setblock 9 -58 7 wall_torch[facing=south]");
            server.runCommand("setblock 8 -59 10 air");
            server.runCommand("setblock 8 -58 10 air");
            context.waitTicks(20);

            SchematicPlacement placement = context.computeOnClient(client ->
                    GameTestSupport.createPlacement(client, "agent-survival", MIN, MAX));
            server.runCommand("fill 6 -60 6 10 -58 10 air");
            context.waitTicks(20);

            context.runOnClient(client -> AgentManager.get().startNew());
            context.waitTicks(5);
            int sessionId = context.computeOnClient(client -> AgentManager.get().sessions().get(0).id);
            context.waitFor(client -> {
                AgentSession session = AgentManager.get().findSession(sessionId);
                return session != null && session.state == SessionState.SCANNING_STORAGE;
            }, 100);
            context.takeScreenshot("survival-01-scan-hud");

            for (BlockPos chest : CHESTS) {
                context.runOnClient(client -> {
                    Vec3d hit = Vec3d.ofCenter(chest).add(0.0, 0.5, 0.0);
                    client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND,
                            new BlockHitResult(hit, Direction.UP, chest, false));
                });
                context.waitForScreen(GenericContainerScreen.class);
                context.waitTicks(5);
                GameTestSupport.clickButton(context, "scan.all");
                context.waitTicks(30);
                if (chest.getX() == 0) {
                    context.takeScreenshot("survival-02-chest-scanned");
                }
                context.runOnClient(client -> client.player.closeHandledScreen());
                context.waitTicks(5);
            }

            context.waitFor(client -> {
                AgentSession session = AgentManager.get().findSession(sessionId);
                return session != null && session.state == SessionState.READY;
            }, 100);
            context.takeScreenshot("survival-03-ready");

            // The test opened the chests itself without looking at them; only the agent's clicks count.
            GameTestSupport.resetRotationCheck();
            context.runOnClient(client -> AgentManager.get().begin(sessionId));
            context.waitTicks(120);
            context.takeScreenshot("survival-04-building");

            AgentSession paused = GameTestSupport.waitForAgent(context, sessionId, 20 * 60 * 6);
            context.takeScreenshot("survival-05-finished");
            if (paused != null) {
                throw new AssertionError("Agent paused: " + paused.pauseReason + " " + paused.pauseArgs);
            }
            GameTestSupport.assertBuilt(context, placement, "survival");
            GameTestSupport.assertNoRotationMismatches("survival");

            // Leftovers go back into the chests.
            context.runOnClient(client -> AgentManager.get().deposit());
            context.waitFor(client -> AgentManager.get().activeAgent() == null, 20 * 90);
            int leftovers = context.computeOnClient(client -> {
                int count = 0;
                for (int i = 0; i < 36; i++) {
                    var stack = client.player.getInventory().getStack(i);
                    if (!stack.isEmpty() && !InventoryHelper.isTool(stack) && !InventoryHelper.isFood(stack)) {
                        count += stack.getCount();
                    }
                }
                return count;
            });
            context.takeScreenshot("survival-06-deposited");
            if (leftovers > 0) {
                throw new AssertionError(leftovers + " building items were not put back into the storage");
            }
            context.runOnClient(client -> GameTestSupport.removePlacement(client, placement));
        }
    }
}

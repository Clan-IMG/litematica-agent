package net.clanimg.litematica_agent.gametest;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.clanimg.litematica_agent.agent.AgentManager;
import net.clanimg.litematica_agent.agent.AgentSession;
import net.clanimg.litematica_agent.agent.SessionState;
import net.clanimg.litematica_agent.storage.ContainerRecord;
import net.clanimg.litematica_agent.movement.InputController;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.util.math.BlockPos;

/**
 * Pause and resume, switching between several sessions, persistence across leaving the world, cancelling.
 */
public class SessionManagementGameTest implements FabricClientGameTest {
    private static final BlockPos A_MIN = new BlockPos(5, -60, 5);
    private static final BlockPos A_MAX = new BlockPos(9, -58, 9);
    private static final BlockPos B_MIN = new BlockPos(5, -60, 15);
    private static final BlockPos B_MAX = new BlockPos(8, -59, 18);
    private static final BlockPos C_MIN = new BlockPos(15, -60, 5);
    private static final BlockPos C_MAX = new BlockPos(19, -58, 9);

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSupport.logMemory("before " + getClass().getSimpleName());
        GameTestSupport.resetRotationCheck();
        TestWorldSave save;
        int sessionC;
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            save = singleplayer.getWorldSave();
            singleplayer.getClientWorld().waitForChunksRender();
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamemode creative @a");
            server.runCommand("tp @a 7 -60 12");

            server.runCommand("fill 5 -60 5 9 -58 9 stone");
            server.runCommand("fill 6 -59 6 8 -58 8 air");
            server.runCommand("fill 5 -60 15 8 -59 18 oak_planks");
            server.runCommand("fill 15 -60 5 19 -58 9 cobblestone");
            server.runCommand("fill 16 -59 6 18 -58 8 air");
            context.waitTicks(20);

            SchematicPlacement a = context.computeOnClient(client -> GameTestSupport.createPlacement(client, "agent-a", A_MIN, A_MAX));
            server.runCommand("fill 5 -60 5 9 -58 9 air");
            context.waitTicks(10);

            // Session 1: start, then pause in the middle like pressing ESC in the lock screen.
            context.runOnClient(client -> AgentManager.get().startNew());
            context.waitTicks(5);
            int sessionA = context.computeOnClient(client -> AgentManager.get().sessions().get(0).id);
            context.runOnClient(client -> AgentManager.get().begin(sessionA));
            context.waitTicks(80);
            context.runOnClient(client -> client.currentScreen.close());
            context.waitTicks(5);
            assertState(context, sessionA, SessionState.PAUSED);
            boolean inputReleased = context.computeOnClient(client -> !InputController.isActive());
            if (!inputReleased) {
                throw new AssertionError("Input must be released after pausing");
            }
            context.takeScreenshot("session-01-paused");

            // Session 2 on another schematic while session 1 stays paused.
            SchematicPlacement b = context.computeOnClient(client -> GameTestSupport.createPlacement(client, "agent-b", B_MIN, B_MAX));
            server.runCommand("fill 5 -60 15 8 -59 18 air");
            context.waitTicks(10);
            context.runOnClient(client -> AgentManager.get().startNew());
            context.waitTicks(5);
            int sessionB = context.computeOnClient(client -> AgentManager.get().sessions().get(1).id);
            context.runOnClient(client -> AgentManager.get().begin(sessionB));
            expectCompleted(context, sessionB);
            GameTestSupport.assertBuilt(context, b, "session-b");
            assertState(context, sessionA, SessionState.PAUSED);

            // Back to session 1 with /agent start <id>.
            context.runOnClient(client -> AgentManager.get().resume(sessionA));
            expectCompleted(context, sessionA);
            GameTestSupport.assertBuilt(context, a, "session-a");

            // Session 3 is interrupted by leaving the world.
            SchematicPlacement c = context.computeOnClient(client -> GameTestSupport.createFilePlacement(client, "agent-c", C_MIN, C_MAX));
            server.runCommand("fill 15 -60 5 19 -58 9 air");
            context.waitTicks(10);
            context.runOnClient(client -> AgentManager.get().startNew());
            context.waitTicks(5);
            sessionC = context.computeOnClient(client -> AgentManager.get().sessions().get(0).id);
            context.runOnClient(client -> AgentManager.get().begin(sessionC));
            context.waitTicks(60);
        }

        try (TestSingleplayerContext singleplayer = save.open()) {
            singleplayer.getClientWorld().waitForChunksRender();
            context.waitTicks(60);
            assertState(context, sessionC, SessionState.PAUSED);
            String reason = context.computeOnClient(client -> AgentManager.get().findSession(sessionC).pauseReason);
            if (!"pause.game_left".equals(reason)) {
                throw new AssertionError("Unexpected pause reason after rejoin: " + reason);
            }
            context.takeScreenshot("session-02-rejoined");
            context.runOnClient(client -> AgentManager.get().resume(sessionC));
            expectCompleted(context, sessionC);
            SchematicPlacement c = context.computeOnClient(client -> fi.dy.masa.litematica.data.DataManager
                    .getSchematicPlacementManager().getSelectedSchematicPlacement());
            if (c != null) {
                GameTestSupport.assertBuilt(context, c, "session-c");
            }
            GameTestSupport.assertNoRotationMismatches("sessions");

            // Cancelling needs a confirmation.
            context.runOnClient(client -> AgentManager.get().startNew());
            context.waitTicks(5);
            int sessionD = context.computeOnClient(client -> AgentManager.get().sessions().isEmpty() ? -1
                    : AgentManager.get().sessions().get(0).id);
            if (sessionD > 0) {
                // A chest scanned for this session: its markings must go with the last session.
                context.runOnClient(client -> AgentManager.get().storage().put(new ContainerRecord("minecraft:overworld", 1, -60, 1)));
                context.runOnClient(client -> AgentManager.get().cancel(sessionD, false));
                assertState(context, sessionD, SessionState.READY);
                context.runOnClient(client -> AgentManager.get().cancel(sessionD, true));
                boolean gone = context.computeOnClient(client -> AgentManager.get().findSession(sessionD) == null);
                if (!gone) {
                    throw new AssertionError("Session must be deleted after confirmed cancel");
                }
                boolean storageCleared = context.computeOnClient(client ->
                        !AgentManager.get().sessions().isEmpty() || AgentManager.get().storage().isEmpty());
                if (!storageCleared) {
                    throw new AssertionError("The scanned chests must be forgotten when the last session is cancelled");
                }
            }
        }
    }

    private static void expectCompleted(ClientGameTestContext context, int sessionId) {
        AgentSession paused = GameTestSupport.waitForAgent(context, sessionId, 20 * 60 * 4);
        if (paused != null) {
            throw new AssertionError("Session " + sessionId + " paused: " + paused.pauseReason + " " + paused.pauseArgs);
        }
    }

    private static void assertState(ClientGameTestContext context, int sessionId, SessionState expected) {
        SessionState state = context.computeOnClient(client -> {
            AgentSession session = AgentManager.get().findSession(sessionId);
            return session == null ? null : session.state;
        });
        if (state != expected) {
            throw new AssertionError("Session " + sessionId + " expected " + expected + " but was " + state);
        }
    }
}

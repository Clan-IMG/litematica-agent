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
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * A stone island floating on the surface of a three blocks deep pond, three cells away from the rim, like the shell of
 * a turtle standing in water. Nothing is there to build it against: the agent needs a scaffold from the rim across the
 * surface water cells, which are not poured yet at that point, and afterwards the water where the scaffold was.
 */
public final class FloatingIslandGameTest implements FabricClientGameTest {
    private static final BlockPos MIN = new BlockPos(6, -60, 6);
    private static final BlockPos MAX = new BlockPos(16, -56, 16);
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
            server.runCommand("give @a iron_pickaxe");
            server.runCommand("tp @a 2 -60 -2 180 30");
            server.runCommand("setblock 0 -60 -4 chest[facing=south]{Items:["
                    + "{Slot:0b,id:\"minecraft:stone\",count:64},{Slot:1b,id:\"minecraft:stone\",count:64},"
                    + "{Slot:2b,id:\"minecraft:stone\",count:64},{Slot:3b,id:\"minecraft:stone\",count:64},"
                    + "{Slot:4b,id:\"minecraft:stone_slab\",count:16}]}");
            server.runCommand("setblock 2 -60 -4 chest[facing=south]{Items:["
                    + "{Slot:0b,id:\"minecraft:water_bucket\",count:1},{Slot:1b,id:\"minecraft:water_bucket\",count:1},"
                    + "{Slot:2b,id:\"minecraft:water_bucket\",count:1},{Slot:3b,id:\"minecraft:water_bucket\",count:1},"
                    + "{Slot:4b,id:\"minecraft:bread\",count:16},{Slot:5b,id:\"minecraft:cobblestone\",count:64},"
                    + "{Slot:6b,id:\"minecraft:cobblestone\",count:64}]}");

            server.runCommand("fill 6 -60 6 16 -60 16 stone");
            server.runCommand("fill 6 -59 6 16 -57 16 stone hollow");
            server.runCommand("fill 7 -59 7 15 -57 15 water");
            server.runCommand("fill 10 -57 10 12 -57 12 stone");
            server.runCommand("fill 10 -56 10 12 -56 12 stone_slab[type=bottom]");
            context.waitTicks(20);

            SchematicPlacement placement = context.computeOnClient(client ->
                    GameTestSupport.createPlacement(client, "agent-island", MIN, MAX));
            server.runCommand("fill 6 -60 6 16 -56 16 air");
            context.waitFor(client -> client.world.getBlockState(new BlockPos(11, -57, 11)).isAir()
                    && client.world.getBlockState(MIN).isAir(), 100);
            context.waitTicks(10);

            context.runOnClient(client -> {
                AgentManager.get().config().verboseLogging = true;
                AgentManager.get().config().wrongBlockMode = AgentConfig.WrongBlockMode.SKIP;
                // The island must come from a scaffold here, not from clicks into the air.
                AgentManager.get().config().airPlacement = false;
                AgentManager.get().worldData().storageHomeCommand = "";
                AgentManager.get().worldData().buildHomeCommand = "";
                AgentManager.get().startNew();
            });
            GameTestSupport.awaitLoading(context);
            context.waitTicks(5);
            int sessionId = context.computeOnClient(client -> AgentManager.get().sessions().get(0).id);
            context.waitFor(client -> AgentManager.get().findSession(sessionId).state == SessionState.SCANNING_STORAGE, 200);
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

            context.runOnClient(client -> AgentManager.get().begin(sessionId));
            GameTestSupport.awaitLoading(context);
            AgentSession paused = GameTestSupport.waitForAgent(context, sessionId, 20 * 60 * 12);
            context.takeScreenshot("island-01-finished");
            if (paused != null) {
                throw new AssertionError("Agent paused: " + paused.pauseReason + " " + paused.pauseArgs);
            }
            GameTestSupport.assertBuilt(context, placement, "island");
            int leftover = context.computeOnClient(client -> {
                int count = 0;
                for (BlockPos pos : BlockPos.iterate(0, -60, 0, 22, -50, 22)) {
                    if (client.world.getBlockState(pos).isOf(Blocks.COBBLESTONE)) {
                        count++;
                    }
                }
                return count;
            });
            if (leftover != 0) {
                throw new AssertionError(leftover + " helper blocks were left standing");
            }
            GameTestSupport.LOGGER.info("[island] PASS: floating island built over the pond from a scaffold, scaffold removed, water complete");
            context.runOnClient(client -> GameTestSupport.removePlacement(client, placement));
        }
        GameTestSupport.logMemory("after " + getClass().getSimpleName());
    }
}

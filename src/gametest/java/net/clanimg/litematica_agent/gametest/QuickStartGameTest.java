package net.clanimg.litematica_agent.gametest;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.clanimg.litematica_agent.agent.AgentManager;
import net.clanimg.litematica_agent.agent.AgentSession;
import net.clanimg.litematica_agent.agent.BuildStrategy;
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
 * A house standing on the ground, built "layer by layer from the top" with only its stone selected and "skip all":
 * the roof has nothing to hold it yet, so the agent has to start at the bottom right away instead of trying the roof
 * over and over; the glass it was not asked for stays out; nothing pauses. Then the same build continues around the
 * player. Measures how long the first block takes.
 */
public final class QuickStartGameTest implements FabricClientGameTest {
    private static final BlockPos MIN = new BlockPos(6, -60, 6);
    private static final BlockPos MAX = new BlockPos(21, -51, 21);
    private static final BlockPos CHEST = new BlockPos(0, -60, -4);
    /** Generous for walking over and aiming; the old behaviour did not place anything in minutes. */
    private static final int FIRST_BLOCK_LIMIT_TICKS = 20 * 20;

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
            StringBuilder items = new StringBuilder();
            for (int slot = 0; slot < 10; slot++) {
                items.append("{Slot:").append(slot).append("b,id:\"minecraft:stone\",count:64},");
            }
            items.append("{Slot:10b,id:\"minecraft:cobblestone\",count:64},{Slot:11b,id:\"minecraft:bread\",count:16}");
            server.runCommand("setblock " + CHEST.getX() + " " + CHEST.getY() + " " + CHEST.getZ() + " chest[facing=south]{Items:[" + items + "]}");

            // The house: stone floor, walls and roof, glass windows the storage has nothing for.
            server.runCommand("fill 6 -60 6 21 -51 21 stone hollow");
            server.runCommand("fill 6 -57 10 6 -56 13 glass");
            server.runCommand("fill 21 -57 10 21 -56 13 glass");
            context.waitTicks(20);
            SchematicPlacement placement = context.computeOnClient(client ->
                    GameTestSupport.createPlacement(client, "agent-house", MIN, MAX));
            server.runCommand("fill 6 -60 6 21 -51 21 air");
            context.waitFor(client -> client.world.getBlockState(MIN).isAir()
                    && client.world.getBlockState(new BlockPos(6, -57, 10)).isAir(), 100);
            context.waitTicks(10);

            context.runOnClient(client -> {
                AgentConfig config = AgentManager.get().config();
                config.verboseLogging = true;
                config.wrongBlockMode = AgentConfig.WrongBlockMode.SKIP;
                // This test is about support: from below because the roof has nothing to hold it yet.
                config.airPlacement = false;
                AgentManager.get().worldData().storageHomeCommand = "";
                AgentManager.get().worldData().buildHomeCommand = "";
                AgentManager.get().startNew();
            });
            GameTestSupport.awaitLoading(context);
            context.waitTicks(5);
            int id = context.computeOnClient(client -> AgentManager.get().sessions().get(0).id);
            context.waitFor(client -> AgentManager.get().findSession(id).state == SessionState.SCANNING_STORAGE, 200);
            context.runOnClient(client -> client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND,
                    new BlockHitResult(Vec3d.ofCenter(CHEST).add(0.0, 0.5, 0.0), Direction.UP, CHEST, false)));
            context.waitForScreen(GenericContainerScreen.class);
            context.waitTicks(5);
            GameTestSupport.clickButton(context, "scan.all");
            context.waitTicks(20);
            context.runOnClient(client -> client.player.closeHandledScreen());
            context.waitTicks(10);
            // The glass is nowhere in the storage, so the session stays in the storage scan - starting anyway, like
            // the player does with a huge schematic and only part of its materials.
            if (context.computeOnClient(client -> AgentManager.get().storage().isEmpty())) {
                throw new AssertionError("The chest was not scanned");
            }

            context.runOnClient(client -> {
                AgentSession session = AgentManager.get().findSession(id);
                session.strategy = BuildStrategy.LAYERS_TOP_DOWN;
                session.blockQueue.clear();
                session.blockQueue.add("minecraft:stone");
            });
            GameTestSupport.resetRotationCheck();
            context.runOnClient(client -> AgentManager.get().begin(id));
            GameTestSupport.awaitLoading(context);
            int firstBlockTicks = context.waitFor(client -> AgentManager.get().findSession(id).doneBlocks > 0,
                    FIRST_BLOCK_LIMIT_TICKS);
            GameTestSupport.LOGGER.info("[quickstart] first block after {} ticks ({} s)", firstBlockTicks, firstBlockTicks / 20.0);

            context.waitTicks(20 * 60);
            context.takeScreenshot("quickstart-01-top-down");
            AgentSession afterTopDown = context.computeOnClient(client -> AgentManager.get().findSession(id));
            if (afterTopDown.state == SessionState.PAUSED) {
                throw new AssertionError("Paused while building: " + afterTopDown.pauseReason + " " + afterTopDown.pauseArgs);
            }
            int[] counts = context.computeOnClient(client -> {
                int stone = 0;
                int glass = 0;
                int roof = 0;
                for (BlockPos pos : BlockPos.iterate(MIN, MAX)) {
                    if (client.world.getBlockState(pos).isOf(Blocks.STONE)) {
                        stone++;
                        if (pos.getY() == MAX.getY()) {
                            roof++;
                        }
                    } else if (client.world.getBlockState(pos).isOf(Blocks.GLASS)) {
                        glass++;
                    }
                }
                return new int[]{stone, glass, roof};
            });
            GameTestSupport.LOGGER.info("[quickstart] after 60 s from the top: {} stone, {} glass, {} on the roof",
                    counts[0], counts[1], counts[2]);
            if (counts[0] < 20) {
                throw new AssertionError("Only " + counts[0] + " stone blocks in the first minute");
            }
            if (counts[1] != 0) {
                throw new AssertionError(counts[1] + " glass blocks placed although only stone was selected");
            }
            if (counts[2] != 0) {
                throw new AssertionError(counts[2] + " roof blocks placed before there was anything to hold them");
            }

            int before = afterTopDown.doneBlocks;
            context.runOnClient(client -> AgentManager.get().findSession(id).strategy = BuildStrategy.PROXIMITY);
            context.waitTicks(20 * 30);
            AgentSession afterNear = context.computeOnClient(client -> AgentManager.get().findSession(id));
            context.takeScreenshot("quickstart-02-around-player");
            if (afterNear.state == SessionState.PAUSED) {
                throw new AssertionError("Paused around the player: " + afterNear.pauseReason + " " + afterNear.pauseArgs);
            }
            if (afterNear.doneBlocks <= before) {
                throw new AssertionError("No progress around the player: " + before + " -> " + afterNear.doneBlocks);
            }
            GameTestSupport.LOGGER.info("[quickstart] around the player: {} -> {} blocks", before, afterNear.doneBlocks);
            GameTestSupport.assertNoRotationMismatches("quickstart");
            GameTestSupport.LOGGER.info("[quickstart] PASS: first block after {} s, stone only, from below, no pause",
                    firstBlockTicks / 20.0);

            context.runOnClient(client -> AgentManager.get().pauseActive());
            context.waitTicks(5);
            context.runOnClient(client -> GameTestSupport.removePlacement(client, placement));
        }
        GameTestSupport.logMemory("after " + getClass().getSimpleName());
    }
}

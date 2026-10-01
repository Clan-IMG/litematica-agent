package net.clanimg.litematica_agent.gametest;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.clanimg.litematica_agent.agent.AgentManager;
import net.clanimg.litematica_agent.config.AgentConfig;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * /agent stock builds a tower of double chests and fills them with exactly the materials a survival session of the
 * schematic needs. The schematic needs 328 stacks, so the tower has seven levels: the upper chests stand on the lower
 * ones, where a careless click would leave them as two single chests, and the top ones can only be reached by flying.
 * Its bottom layer is grass under stone, which turns into dirt in survival: the chests have to hold dirt.
 */
public class StockGameTest implements FabricClientGameTest {
    private static final BlockPos MIN = new BlockPos(10, -60, 10);
    private static final BlockPos MAX = new BlockPos(35, -30, 35);
    private static final int STONE = 26 * 30 * 26;
    private static final int DIRT = 26 * 26;
    private static final int LEVELS = 7;

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSupport.logMemory("before " + getClass().getSimpleName());
        GameTestSupport.resetRotationCheck();
        context.runOnClient(client -> AgentManager.get().config().speed = AgentConfig.FASTEST_SPEED);
        try {
            this.stockAndCheck(context);
        } finally {
            context.runOnClient(client -> AgentManager.get().config().speed = AgentConfig.DEFAULT_SPEED);
        }
    }

    private void stockAndCheck(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientWorld().waitForChunksRender();
            TestServerContext server = singleplayer.getServer();
            server.runCommand("gamemode creative @a");
            server.runCommand("fill 10 -60 10 35 -30 35 stone");
            server.runCommand("fill 10 -60 10 35 -60 35 grass_block");
            context.waitTicks(20);
            SchematicPlacement placement = context.computeOnClient(client ->
                    GameTestSupport.createPlacement(client, "agent-stock", MIN, MAX));
            server.runCommand("fill 10 -60 10 35 -30 35 air");
            // Facing south: the tower goes up two blocks in front of the player.
            server.runCommand("tp @a 0 -60 0 0 0");
            context.waitTicks(20);

            context.runOnClient(client -> AgentManager.get().startStocking());

            GameTestSupport.awaitLoading(context);
            context.waitTicks(5);
            if (!context.computeOnClient(client -> AgentManager.get().stockAgent() != null)) {
                throw new AssertionError("Stocking did not start");
            }
            context.waitFor(client -> AgentManager.get().stockAgent() == null || AgentManager.get().stockAgent().isPaused(), 20 * 300);
            context.takeScreenshot("stock-01-tower");
            String pauseReason = context.computeOnClient(client ->
                    AgentManager.get().stockAgent() == null ? null : AgentManager.get().stockAgent().pauseReason());
            if (pauseReason != null) {
                throw new AssertionError("Stocking paused: " + pauseReason);
            }

            int[] found = server.computeOnServer(minecraftServer -> {
                ServerWorld world = minecraftServer.getOverworld();
                int chests = 0;
                int singles = 0;
                int stone = 0;
                int dirt = 0;
                int grass = 0;
                for (BlockPos pos : BlockPos.iterate(-5, -60, -3, 5, -50, 6)) {
                    BlockState state = world.getBlockState(pos);
                    if (!state.isOf(Blocks.CHEST)) {
                        continue;
                    }
                    chests++;
                    if (state.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE) {
                        singles++;
                    }
                    if (world.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
                        for (int slot = 0; slot < chest.size(); slot++) {
                            ItemStack stack = chest.getStack(slot);
                            if (stack.isOf(Items.STONE)) {
                                stone += stack.getCount();
                            } else if (stack.isOf(Items.DIRT)) {
                                dirt += stack.getCount();
                            } else if (stack.isOf(Items.GRASS_BLOCK)) {
                                grass += stack.getCount();
                            }
                        }
                    }
                }
                GameTestSupport.LOGGER.info("Chests: {} blocks, {} single, {} stone, {} dirt, {} grass", chests, singles, stone, dirt, grass);
                return new int[]{chests, singles, stone, dirt, grass};
            });
            if (found[0] != LEVELS * 2 || found[1] != 0) {
                throw new AssertionError("Expected " + LEVELS * 2 + " chest blocks forming " + LEVELS + " double chests, found "
                        + found[0] + " chest blocks, " + found[1] + " of them single");
            }
            if (found[2] != STONE || found[3] != DIRT || found[4] != 0) {
                throw new AssertionError("Expected " + STONE + " stone and " + DIRT + " dirt in the chests, found "
                        + found[2] + " stone, " + found[3] + " dirt and " + found[4] + " grass");
            }
            GameTestSupport.assertNoRotationMismatches("stock");
            context.runOnClient(client -> GameTestSupport.removePlacement(client, placement));
        }
    }
}

package net.clanimg.litematica_agent.gametest;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.clanimg.litematica_agent.agent.AgentManager;
import net.clanimg.litematica_agent.agent.AgentSession;
import net.clanimg.litematica_agent.agent.SessionState;
import net.clanimg.litematica_agent.config.AgentConfig;
import net.clanimg.litematica_agent.inventory.InventoryHelper;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** Real walking, closed warehouse door, an obstructing wall, withdrawal, return and a second storage trip. */
public final class WarehouseGameTest implements FabricClientGameTest {
    private static final BlockPos MIN = new BlockPos(6, -60, 6);
    private static final BlockPos MAX = new BlockPos(14, -60, 14);
    private static final BlockPos DOOR = new BlockPos(-24, -60, 8);
    private static final BlockPos[] CHESTS = {
            new BlockPos(-34, -60, 0), new BlockPos(-34, -60, 6), new BlockPos(-34, -60, 12)};

    @Override
    public void runTest(ClientGameTestContext context) {
        GameTestSupport.logMemory("warehouse-before");
        try (var singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientWorld().waitForChunksRender();
            var server = singleplayer.getServer();
            server.runCommand("difficulty peaceful");
            server.runCommand("gamemode survival @a");
            server.runCommand("clear @a");
            server.runCommand("tp @a 3 -60 8");
            server.runCommand("give @a iron_pickaxe");
            // A closed, roofed building 30-50 blocks from the plot; all three chests share one aisle.
            server.runCommand("fill -38 -60 -4 -24 -56 16 stone_bricks hollow");
            server.runCommand("fill -37 -60 -3 -25 -57 15 air");
            server.runCommand("fill -24 -60 8 -24 -59 8 air");
            server.runCommand("setblock -24 -60 8 oak_door[facing=east,half=lower,hinge=left]");
            server.runCommand("setblock -24 -59 8 oak_door[facing=east,half=upper,hinge=left]");
            server.runCommand("fill -14 -60 0 -14 -57 10 bricks");
            server.runCommand("setblock -34 -60 0 chest[facing=east]{Items:[{Slot:0b,id:\"minecraft:stone_bricks\",count:64}]}");
            server.runCommand("setblock -34 -60 6 chest[facing=east]{Items:[{Slot:0b,id:\"minecraft:stone_bricks\",count:64}]}");
            server.runCommand("setblock -34 -60 12 chest[facing=east]{Items:[{Slot:0b,id:\"minecraft:bread\",count:64},{Slot:1b,id:\"minecraft:cobblestone\",count:64}]}");
            server.runCommand("fill 6 -60 6 14 -60 14 stone_bricks");
            context.waitTicks(30);
            SchematicPlacement placement = context.computeOnClient(client ->
                    GameTestSupport.createPlacement(client, "warehouse-preflight", MIN, MAX));
            server.runCommand("fill 6 -60 6 14 -60 14 air");
            // The session reads the area on the client: it must already see the cleared floor, or every block counts
            // as done and the storage scan selects none of the materials.
            context.waitFor(client -> client.world.getBlockState(MIN).isAir() && client.world.getBlockState(MAX).isAir(), 100);
            context.waitTicks(5);
            context.runOnClient(client -> {
                AgentManager.get().config().verboseLogging = true;
                AgentManager.get().config().wrongBlockMode = AgentConfig.WrongBlockMode.SKIP;
                AgentManager.get().worldData().storageHomeCommand = "";
                AgentManager.get().worldData().buildHomeCommand = "";
                AgentManager.get().startNew();
            });
            GameTestSupport.awaitLoading(context);
            context.waitFor(client -> AgentManager.get().focused() != null
                    && AgentManager.get().focused().session().state == SessionState.SCANNING_STORAGE, 200);
            int id = context.computeOnClient(client -> AgentManager.get().focused().session().id);
            for (BlockPos chest : CHESTS) scan(context, server, chest);
            // Teleports are fixture setup only. No teleport, item grant or block write during agent operation.
            server.runCommand("tp @a 3 -60 8");
            context.waitFor(client -> AgentManager.get().findSession(id).state == SessionState.READY, 200);
            context.waitTicks(10);
            GameTestSupport.resetRotationCheck();
            context.takeScreenshot("warehouse-01-ready");
            context.runOnClient(client -> AgentManager.get().begin(id));
            GameTestSupport.awaitLoading(context);
            AgentSession paused = GameTestSupport.waitForAgent(context, id, 20 * 60 * 12);
            context.takeScreenshot("warehouse-02-result");
            if (paused != null) throw new AssertionError("Warehouse roundtrip paused: " + paused.pauseReason + " " + paused.pauseArgs);
            GameTestSupport.assertBuilt(context, placement, "warehouse-roundtrip");
            if (!context.computeOnClient(client -> client.world.getBlockState(DOOR).get(Properties.OPEN))) {
                throw new AssertionError("Warehouse door was not opened by the agent");
            }
            if (!context.computeOnClient(client -> client.interactionManager.getCurrentGameMode() == net.minecraft.world.GameMode.SURVIVAL
                    && !client.player.getAbilities().allowFlying)) {
                throw new AssertionError("Warehouse roundtrip did not stay in survival without flight");
            }
            // Close the door before depositing to require a second independent door passage.
            server.runCommand("setblock -24 -60 8 oak_door[facing=east,half=lower,hinge=left,open=false]");
            server.runCommand("setblock -24 -59 8 oak_door[facing=east,half=upper,hinge=left,open=false]");
            context.runOnClient(client -> AgentManager.get().deposit());
            context.waitFor(client -> AgentManager.get().activeAgent() == null
                    || (AgentManager.get().activeSession() != null && AgentManager.get().activeSession().state == SessionState.PAUSED), 20 * 180);
            int left = context.computeOnClient(client -> InventoryHelper.count(client.player.getInventory(), Items.STONE_BRICKS)
                    + InventoryHelper.count(client.player.getInventory(), Items.COBBLESTONE));
            if (left != 0) throw new AssertionError("Deposit left " + left + " building items in inventory");
            int builtOnServer = server.computeOnServer(s -> {
                int count = 0;
                for (BlockPos pos : BlockPos.iterate(MIN, MAX)) if (s.getOverworld().getBlockState(pos).isOf(Blocks.STONE_BRICKS)) count++;
                return count;
            });
            if (builtOnServer != 81) throw new AssertionError("Server verified only " + builtOnServer + "/81 blocks");
            GameTestSupport.assertNoRotationMismatches("warehouse");
            GameTestSupport.LOGGER.info("[warehouse] PASS: 81/81 server blocks, closed door, obstacle detour, 3 reachable chests, withdrawal/return/deposit, survival, no home");
            context.takeScreenshot("warehouse-03-deposited");
            context.runOnClient(client -> GameTestSupport.removePlacement(client, placement));
        }
        GameTestSupport.logMemory("warehouse-after");
    }

    private static void scan(ClientGameTestContext context, TestServerContext server, BlockPos chest) {
        server.runCommand("tp @a " + (chest.getX() + 2.5) + " -60 " + (chest.getZ() + 0.5) + " 90 25");
        context.waitTicks(15);
        context.runOnClient(client -> client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND,
                new BlockHitResult(Vec3d.ofCenter(chest).add(0.5, 0, 0), Direction.EAST, chest, false)));
        context.waitForScreen(GenericContainerScreen.class);
        context.waitTicks(5);
        GameTestSupport.clickButton(context, "scan.all");
        context.waitTicks(10);
        context.runOnClient(client -> client.player.closeHandledScreen());
        context.waitTicks(5);
    }
}

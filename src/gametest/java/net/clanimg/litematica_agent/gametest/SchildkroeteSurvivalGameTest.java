package net.clanimg.litematica_agent.gametest;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.clanimg.litematica_agent.agent.AgentManager;
import net.clanimg.litematica_agent.agent.AgentSession;
import net.clanimg.litematica_agent.agent.SessionRuntime;
import net.clanimg.litematica_agent.agent.SessionState;
import net.clanimg.litematica_agent.config.AgentConfig;
import net.clanimg.litematica_agent.placement.StateMatcher;
import net.clanimg.litematica_agent.placement.WaterPlacement;
import net.clanimg.litematica_agent.schematic.BuildTarget;
import net.clanimg.litematica_agent.schematic.SchematicAccess;
import net.clanimg.litematica_agent.schematic.SurvivalCheck;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Full survival acceptance run of a real schematic file, like a night on a CityBuild server: the storage is a closed
 * warehouse on the other side of a wall, reached through a door and a detour; the storage holds what the materials
 * list asks for (plus some spare), scanned chest by chest like a player does; the agent builds everything with
 * "approve all", without teleports, item grants or block edits by the test; afterwards every executable block is
 * compared with the world, and blocks that cannot exist in vanilla survival are listed with their reason.
 * <p>
 * Opt-in: set LITEMATICA_TEST_SCHEMATIC to the .litematic file and run with -PagentGameTest=SchildkroeteSurvivalGameTest.
 * The realistic storage roundtrip ({@link WarehouseGameTest}) has to pass first, with the same binary.
 */
public final class SchildkroeteSurvivalGameTest implements FabricClientGameTest {
    private static final BlockPos ORIGIN = new BlockPos(20, -60, 20);
    private static final BlockPos START = new BlockPos(16, -60, 28);
    /** Warehouse: closed stone brick building west of a wall, door facing the plot. */
    private static final BlockPos DOOR = new BlockPos(-10, -60, 28);
    private static final int[] CHEST_ROWS = {21, 24, 27, 30, 33};
    private static final int CHEST_X = -25;
    private static final int SLOTS_PER_HALF = 27;
    private static final int MAX_BUILD_MINUTES = 150;
    private static final Path REPORT = Path.of("full-schematic-report.txt");

    private final List<String> report = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext context) {
        String file = System.getenv("LITEMATICA_TEST_SCHEMATIC");
        if (file == null || file.isBlank()) {
            GameTestSupport.LOGGER.info("[full-schematic] Not requested; set LITEMATICA_TEST_SCHEMATIC to opt in");
            return;
        }
        Path path = Path.of(file).toAbsolutePath();
        if (!Files.isRegularFile(path)) {
            throw new AssertionError("Missing test schematic: " + path);
        }
        // The storage logic has to work first: walking there, a closed door, withdrawal, return, a second trip.
        new WarehouseGameTest().runTest(context);
        this.line("storage gate: WarehouseGameTest passed with this binary");

        try (var singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientWorld().waitForChunksRender();
            TestServerContext server = singleplayer.getServer();
            for (String command : List.of("difficulty peaceful", "gamemode survival @a", "clear @a",
                    "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "time set noon",
                    "give @a iron_pickaxe", "give @a bread 16")) {
                server.runCommand(command);
            }
            this.buildSurroundings(server);
            server.runCommand("tp @a " + START.getX() + " " + START.getY() + " " + START.getZ() + " -90 20");
            context.waitTicks(40);

            SchematicPlacement placement = context.computeOnClient(client -> {
                LitematicaSchematic schematic = LitematicaSchematic.createFromFile(path.getParent(), path.getFileName().toString());
                if (schematic == null) {
                    throw new AssertionError("Litematica failed to load " + path);
                }
                SchematicPlacement result = SchematicPlacement.createFor(schematic, ORIGIN, "schildkroete-survival", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(result, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(result);
                return result;
            });
            context.waitTicks(20);
            this.containWater(context, server, placement);

            context.runOnClient(client -> {
                AgentConfig config = AgentManager.get().config();
                config.verboseLogging = true;
                config.wrongBlockMode = AgentConfig.WrongBlockMode.SKIP;
                config.speed = AgentConfig.FASTEST_SPEED;
                AgentManager.get().worldData().storageHomeCommand = "";
                AgentManager.get().worldData().buildHomeCommand = "";
                AgentManager.get().startNew();
            });
            GameTestSupport.awaitLoading(context);
            context.waitTicks(20);
            SessionRuntime runtime = context.computeOnClient(client -> AgentManager.get().focused());
            if (runtime == null) {
                throw new AssertionError("Full schematic did not load");
            }
            int id = context.computeOnClient(client -> runtime.session().id);
            this.preflight(context, placement, runtime);

            // What the materials list asks for goes into the warehouse, with some spare like a real storage.
            Map<Item, Integer> materials = context.computeOnClient(client -> new LinkedHashMap<>(runtime.remainingMaterials()));
            this.stockWarehouse(server, materials);
            if (context.computeOnClient(client -> AgentManager.get().findSession(id).state) == SessionState.CONFIRM_SKIPS) {
                // The player accepts once, before the night, that survival-impossible blocks are left out.
                context.runOnClient(client -> AgentManager.get().acceptSkips(id));
            }
            context.waitFor(client -> AgentManager.get().findSession(id).state == SessionState.SCANNING_STORAGE, 400);
            this.scanWarehouse(context, server);
            server.runCommand("tp @a " + START.getX() + " " + START.getY() + " " + START.getZ() + " -90 20");
            context.waitTicks(40);
            context.waitFor(client -> AgentManager.get().findSession(id).state == SessionState.READY, 400);
            context.takeScreenshot("schildkroete-01-ready");

            long started = System.currentTimeMillis();
            GameTestSupport.resetRotationCheck();
            context.runOnClient(client -> AgentManager.get().begin(id));
            GameTestSupport.awaitLoading(context);
            AgentSession paused = this.watchBuild(context, server, id, started);
            long minutes = (System.currentTimeMillis() - started) / 60_000L;
            context.takeScreenshot("schildkroete-02-result");
            if (paused != null) {
                this.line("PAUSED after " + minutes + " min: " + paused.pauseReason + " " + paused.pauseArgs);
                this.writeReport();
                throw new AssertionError("Full build paused: " + paused.pauseReason + " " + paused.pauseArgs);
            }
            this.line("completed in " + minutes + " min (wall clock)");
            this.verify(context, placement);
            this.line("rotation mismatches (anti-cheat relevant): " + GameTestSupport.rotationMismatches());
            this.writeReport();
            GameTestSupport.assertNoRotationMismatches("schildkroete");
        }
    }

    /** A closed warehouse with a door on the far side of a wall that has to be walked around. */
    private void buildSurroundings(TestServerContext server) {
        server.runCommand("fill -27 -60 18 -10 -55 37 stone_bricks hollow");
        server.runCommand("fill -26 -60 19 -11 -56 36 air");
        server.runCommand("setblock " + DOOR.getX() + " -60 " + DOOR.getZ() + " oak_door[facing=east,half=lower,hinge=left]");
        server.runCommand("setblock " + DOOR.getX() + " -59 " + DOOR.getZ() + " oak_door[facing=east,half=upper,hinge=left]");
        // A neighbour's wall between warehouse and plot, open only far to the south.
        server.runCommand("fill 3 -60 0 3 -57 44 bricks");
        server.runCommand("fill 3 -60 45 3 -57 46 air");
        server.runCommand("fill 3 -60 47 3 -57 55 bricks");
    }

    /**
     * The file is a cut-out of a landscape: some pond water touches the region border, where the original world had
     * more ground. A block of stone just outside keeps the water in, like the neighbouring terrain did.
     */
    private void containWater(ClientGameTestContext context, TestServerContext server, SchematicPlacement placement) {
        List<BlockPos> border = context.computeOnClient(client -> {
            var blocks = SchematicAccess.readBlocks(placement).states();
            BlockBox box = null;
            for (Long2ObjectMap.Entry<BlockState> entry : blocks.long2ObjectEntrySet()) {
                BlockPos pos = BlockPos.fromLong(entry.getLongKey());
                box = box == null ? new BlockBox(pos) : box.encompass(pos);
            }
            List<BlockPos> result = new ArrayList<>();
            for (Long2ObjectMap.Entry<BlockState> entry : blocks.long2ObjectEntrySet()) {
                if (!entry.getValue().getFluidState().isStill()) {
                    continue;
                }
                BlockPos pos = BlockPos.fromLong(entry.getLongKey());
                for (Direction direction : Direction.Type.HORIZONTAL) {
                    BlockPos outside = pos.offset(direction);
                    if (!box.contains(outside)) {
                        result.add(outside);
                    }
                }
            }
            return result;
        });
        for (BlockPos pos : border) {
            server.runCommand("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " stone");
        }
        this.line("terrain: " + border.size() + " stone blocks outside the region keep border water in");
    }

    private void preflight(ClientGameTestContext context, SchematicPlacement placement, SessionRuntime runtime) {
        context.runOnClient(client -> {
            var raw = SchematicAccess.readTargets(placement);
            this.line("file: " + System.getenv("LITEMATICA_TEST_SCHEMATIC"));
            this.line("non-air states in file: " + raw.states().size() + ", executable targets: " + runtime.plan().size());
            Map<String, Integer> reasons = new TreeMap<>();
            Map<String, Integer> kinds = new TreeMap<>();
            for (var block : runtime.unsupported()) {
                reasons.merge(block.reason().name(), 1, Integer::sum);
                kinds.merge(block.reason().name() + " " + Registries.BLOCK.getId(block.state().getBlock()), 1, Integer::sum);
            }
            this.line("left out before building: " + runtime.unsupported().size() + " " + reasons);
            kinds.forEach((kind, count) -> this.line("  " + count + " x " + kind));
            int water = 0;
            for (int i = 0; i < runtime.plan().size(); i++) {
                if (WaterPlacement.involvesWater(runtime.plan().get(i).state())) {
                    water++;
                }
            }
            this.line("targets with water work (sources, waterlogged, aquatic): " + water);
        });
    }

    /** Fills double chests along the warehouse wall, 27 stacks per half. */
    private void stockWarehouse(TestServerContext server, Map<Item, Integer> materials) {
        List<String> stacks = new ArrayList<>();
        int items = 0;
        for (Map.Entry<Item, Integer> entry : materials.entrySet()) {
            Item item = entry.getKey();
            if (item == Items.PLAYER_HEAD) {
                // Heads from a head shop, with the textures the schematic shows.
                stacks.add("id:\"minecraft:player_head\",count:2,components:{\"minecraft:profile\":{id:[I;-435701202,1216564726,"
                        + "-2107496026,-469911573],properties:[{name:\"textures\",value:\"eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjZjNWVjYWM5NDJjNzdiOTVhYjQ2MjBkZjViODVlMzgwNjRjOTc0ZjljNWM1NzZiODQzNjIyODA2YTQ1NTcifX19\"}]}}");
                stacks.add("id:\"minecraft:player_head\",count:1,components:{\"minecraft:profile\":{id:[I;687207631,-393264050,"
                        + "-1741829271,-1304483078],properties:[{name:\"textures\",value:\"eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHBzOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlL2FlZjY4OThjY2RkM2U5YjkwYmVmMjQwOTkzOGIwYzJkYWY0MWUyNGQyYjI3NjZkMjBhNWI4ZTAxNzk5ZjEwZjEifX19\"}]}}");
                items += 3;
                continue;
            }
            int max = item.getMaxCount();
            // 10 % spare, at least one; water buckets: two more than the agent carries.
            int amount = item == Items.WATER_BUCKET ? entry.getValue() + 2 : entry.getValue() + Math.max(1, entry.getValue() / 10);
            String id = Registries.ITEM.getId(item).toString();
            for (int left = amount; left > 0; left -= max) {
                stacks.add("id:\"" + id + "\",count:" + Math.min(left, max));
            }
            items += amount;
        }
        for (String extra : List.of("id:\"minecraft:cobblestone\",count:64", "id:\"minecraft:cobblestone\",count:64",
                "id:\"minecraft:bread\",count:64", "id:\"minecraft:iron_pickaxe\",count:1")) {
            stacks.add(extra);
        }
        int halves = CHEST_ROWS.length * 2;
        if (stacks.size() > halves * SLOTS_PER_HALF) {
            throw new AssertionError("Warehouse too small for " + stacks.size() + " stacks");
        }
        for (int half = 0; half < halves; half++) {
            int row = CHEST_ROWS[half / 2];
            boolean left = half % 2 == 0;
            StringBuilder nbt = new StringBuilder("{Items:[");
            int from = half * SLOTS_PER_HALF;
            for (int slot = 0; slot < SLOTS_PER_HALF && from + slot < stacks.size(); slot++) {
                if (slot > 0) {
                    nbt.append(',');
                }
                nbt.append("{Slot:").append(slot).append("b,").append(stacks.get(from + slot)).append('}');
            }
            nbt.append("]}");
            // Facing east, a left half has its partner to the south.
            server.runCommand("setblock " + CHEST_X + " -60 " + (left ? row : row + 1) + " chest[facing=east,type="
                    + (left ? "left" : "right") + "]" + nbt);
        }
        this.line("warehouse: " + stacks.size() + " stacks (" + items + " items incl. spare) in " + CHEST_ROWS.length
                + " double chests, ~" + (Math.abs(START.getX() - CHEST_X) + Math.abs(46 - START.getZ())) + " blocks walk incl. detour");
    }

    /** Opens every double chest once and records it with the chest menu's own "scan all" button. */
    private void scanWarehouse(ClientGameTestContext context, TestServerContext server) {
        for (int row : CHEST_ROWS) {
            BlockPos chest = new BlockPos(CHEST_X, -60, row);
            server.runCommand("tp @a " + (CHEST_X + 2.5) + " -60 " + (row + 1.0) + " 90 30");
            context.waitTicks(15);
            context.runOnClient(client -> client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND,
                    new BlockHitResult(Vec3d.ofCenter(chest).add(0.5, 0.0, 0.0), Direction.EAST, chest, false)));
            context.waitForScreen(GenericContainerScreen.class);
            context.waitTicks(5);
            GameTestSupport.clickButton(context, "scan.all");
            context.waitTicks(10);
            context.runOnClient(client -> client.player.closeHandledScreen());
            context.waitTicks(5);
        }
        this.line("scanned " + CHEST_ROWS.length + " double chests");
    }

    /**
     * Waits for the end of the build and logs progress, speed, memory and server tick time every minute. The test
     * does nothing to the world meanwhile.
     */
    private AgentSession watchBuild(ClientGameTestContext context, TestServerContext server, int id, long started) {
        int lastDone = -1;
        int minutesWithoutProgress = 0;
        for (int minute = 1; minute <= MAX_BUILD_MINUTES; minute++) {
            for (int step = 0; step < 12; step++) {
                context.waitTicks(100);
                boolean over = context.computeOnClient(client -> {
                    AgentSession session = AgentManager.get().findSession(id);
                    return session == null || session.state == SessionState.PAUSED;
                });
                if (over) {
                    return context.computeOnClient(client -> AgentManager.get().findSession(id));
                }
            }
            String summary = context.computeOnClient(client -> {
                var agent = AgentManager.get().activeAgent();
                return agent == null ? "no agent" : agent.debugSummary();
            });
            int done = context.computeOnClient(client -> {
                AgentSession session = AgentManager.get().findSession(id);
                return session == null ? -1 : session.doneBlocks;
            });
            float mspt = server.computeOnServer(s -> s.getAverageTickTime());
            Runtime heap = Runtime.getRuntime();
            long usedMb = (heap.totalMemory() - heap.freeMemory()) / (1024 * 1024);
            int fps = context.computeOnClient(client -> client.getCurrentFps());
            String line = String.format(Locale.ROOT, "t=%dmin done=%d (+%d) heap=%dMB mspt=%.1f fps=%d | %s",
                    minute, done, lastDone < 0 ? 0 : done - lastDone, usedMb, mspt, fps, summary);
            GameTestSupport.LOGGER.info("[full-schematic] {}", line);
            if (minute % 5 == 0) {
                this.line(line);
                context.takeScreenshot(String.format(Locale.ROOT, "schildkroete-progress-%03d", minute));
            }
            minutesWithoutProgress = done == lastDone ? minutesWithoutProgress + 1 : 0;
            if (minutesWithoutProgress == 10) {
                this.line("WARNING: no finished block for 10 minutes: " + summary);
            }
            lastDone = done;
        }
        this.line("TIMEOUT after " + MAX_BUILD_MINUTES + " min");
        this.writeReport();
        throw new AssertionError("Full build did not finish in " + MAX_BUILD_MINUTES + " minutes");
    }

    /** Every executable block against the world; the left-out ones listed separately. */
    private void verify(ClientGameTestContext context, SchematicPlacement placement) {
        List<String> mismatches = context.computeOnClient(client -> {
            var read = SchematicAccess.readTargets(placement);
            var checked = SurvivalCheck.apply(read, client.world);
            List<String> result = new ArrayList<>();
            for (BuildTarget target : checked.targets()) {
                BlockState state = client.world.getBlockState(target.pos());
                if (!StateMatcher.isComplete(state, target.state())) {
                    result.add(target.pos().toShortString() + " expected " + target.state() + " found " + state);
                }
            }
            this.line("verified " + (checked.targets().size() - result.size()) + " of " + checked.targets().size()
                    + " executable blocks; left out (survival-impossible): " + checked.unsupported().size());
            return result;
        });
        mismatches.stream().limit(50).forEach(line -> this.line("  mismatch: " + line));
        if (!mismatches.isEmpty()) {
            this.writeReport();
            throw new AssertionError(mismatches.size() + " executable blocks do not match, first: " + mismatches.get(0));
        }
    }

    private void line(String text) {
        this.report.add(text);
        GameTestSupport.LOGGER.info("[full-schematic] {}", text);
    }

    private void writeReport() {
        try {
            Files.write(REPORT, this.report);
            GameTestSupport.LOGGER.info("[full-schematic] report: {}", REPORT.toAbsolutePath());
        } catch (IOException e) {
            throw new AssertionError("Could not write the report", e);
        }
    }
}

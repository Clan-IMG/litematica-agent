package net.clanimg.litematica_agent.gametest;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.clanimg.litematica_agent.agent.AgentManager;
import net.clanimg.litematica_agent.agent.AgentSession;
import net.clanimg.litematica_agent.agent.BuildAgent;
import net.clanimg.litematica_agent.agent.BuildStrategy;
import net.clanimg.litematica_agent.agent.SessionState;
import net.clanimg.litematica_agent.config.AgentConfig;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * A player's situation with a real, huge schematic (tens of millions of blocks): the plot is partly built already,
 * some of it wrong, the storage holds a few double chests of the main material and nothing else, "skip all" is on,
 * and the player presses start. The agent has to start building within seconds and keep going for minutes without a
 * pause, quietly skipping what it cannot do. Everything the agent does is recorded in a report for the analysis.
 * <p>
 * Opt-in: set LITEMATICA_TEST_SCHEMATIC to the .litematic file and run
 * {@code ./gradlew runClientGameTest -PagentGameTest=HugeSchematicGameTest -PagentTestHeap=12G}.
 */
public final class HugeSchematicGameTest implements FabricClientGameTest {
    /** The placement's west edge; the player starts and the storage stands just west of it, like a lager next to the plot. */
    private static final int EDGE = 6;
    private static final BlockPos ORIGIN = new BlockPos(EDGE, -60, -313);
    private static final BlockPos START = new BlockPos(2, -60, 0);
    /** Left halves of three double chests facing east; the right half is one block south. */
    private static final int[] CHEST_ROWS = {-5, -1, 3};
    private static final int CHEST_X = -3;
    private static final int WATCH_SECONDS = 300;
    private static final int SAMPLE_SECONDS = 15;
    private static final int TARGET_BLOCKS = 100;

    private final List<String> report = new ArrayList<>();
    /** Next to the schematic file (the game's working directory is not the project). */
    private Path reportFile = Path.of("huge-schematic-report.txt");

    @Override
    public void runTest(ClientGameTestContext context) {
        String file = System.getenv("LITEMATICA_TEST_SCHEMATIC");
        if (file == null || file.isBlank()) {
            GameTestSupport.LOGGER.info("[huge] Not requested; set LITEMATICA_TEST_SCHEMATIC to opt in");
            return;
        }
        Path path = Path.of(file).toAbsolutePath();
        if (!Files.isRegularFile(path)) {
            throw new AssertionError("Missing test schematic: " + path);
        }
        this.reportFile = path.getParent().resolve("huge-schematic-report.txt");
        GameTestSupport.logMemory("before " + getClass().getSimpleName());
        try (var singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientWorld().waitForChunksRender();
            TestServerContext server = singleplayer.getServer();
            for (String command : List.of("difficulty peaceful", "gamemode survival @a", "clear @a",
                    "gamerule doDaylightCycle false", "gamerule doWeatherCycle false", "time set noon",
                    "give @a iron_pickaxe")) {
                server.runCommand(command);
            }
            this.buildSurroundings(server);
            // LITEMATICA_TEST_VOID=true: the plot as on a plot server - a platform with nothing underneath. The file
            // is placed two layers lower, so its first layers hang in that void; only the platform gives a hold.
            boolean voidBelow = "true".equalsIgnoreCase(System.getenv("LITEMATICA_TEST_VOID"));
            BlockPos origin = ORIGIN;
            if (voidBelow) {
                origin = ORIGIN.down(2);
                this.digVoid(server);
                // The plan compares the file with the client's world: that world must have the void before, or the
                // ground still counts as done and is reopened as soon as the chunk updates arrive.
                BlockPos voidProbe = new BlockPos(EDGE - 2, -62, 0);
                context.waitFor(client -> client.world.getBlockState(voidProbe).isAir(), 20 * 120);
            }
            // LITEMATICA_TEST_FLY=true: survival flight as a server's /fly grants it.
            if ("true".equalsIgnoreCase(System.getenv("LITEMATICA_TEST_FLY"))) {
                server.computeOnServer(s -> {
                    var player = s.getPlayerManager().getPlayerList().get(0);
                    player.getAbilities().allowFlying = true;
                    player.sendAbilitiesUpdate();
                    return null;
                });
            }
            server.runCommand("tp @a " + START.getX() + " " + START.getY() + " " + START.getZ() + " -90 20");
            context.waitTicks(40);

            long loadStart = System.currentTimeMillis();
            BlockPos placementOrigin = origin;
            SchematicPlacement placement = context.computeOnClient(client -> {
                LitematicaSchematic schematic = LitematicaSchematic.createFromFile(path.getParent(), path.getFileName().toString());
                if (schematic == null) {
                    throw new AssertionError("Litematica failed to load " + path);
                }
                SchematicPlacement result = SchematicPlacement.createFor(schematic, placementOrigin, "huge-schematic", true, true);
                DataManager.getSchematicPlacementManager().addSchematicPlacement(result, false);
                DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(result);
                return result;
            });
            this.line("file: " + path + " (Litematica load " + (System.currentTimeMillis() - loadStart) / 1000.0 + " s)");
            context.waitTicks(20);

            context.runOnClient(client -> {
                AgentConfig config = AgentManager.get().config();
                config.verboseLogging = true;
                config.wrongBlockMode = AgentConfig.WrongBlockMode.SKIP;
                config.speed = AgentConfig.DEFAULT_SPEED;
                config.airPlacement = "true".equalsIgnoreCase(System.getenv("LITEMATICA_TEST_AIR_PLACEMENT"));
                AgentManager.get().worldData().storageHomeCommand = "";
                AgentManager.get().worldData().buildHomeCommand = "";
                AgentManager.get().startNew();
            });
            long readStart = System.currentTimeMillis();
            context.waitFor(client -> !AgentManager.get().isLoading(), 20 * 600);
            this.line("agent read + plan: " + (System.currentTimeMillis() - readStart) / 1000.0 + " s");
            this.line("heap after load: " + usedHeapMb() + " MB");
            context.waitTicks(20);
            int id = context.computeOnClient(client -> AgentManager.get().sessions().get(0).id);
            context.waitFor(client -> {
                SessionState state = state(id);
                return state == SessionState.CONFIRM_SKIPS || state == SessionState.SCANNING_STORAGE
                        || state == SessionState.CHECK_INVENTORY;
            }, 600);
            AgentSession created = context.computeOnClient(client -> AgentManager.get().findSession(id));
            this.line("session: " + created.totalBlocks + " targets, " + created.doneBlocks + " already done, state " + created.state);
            if (state(context, id) == SessionState.CONFIRM_SKIPS) {
                int unsupported = context.computeOnClient(client -> AgentManager.get().focused().unsupported().size());
                Map<String, Integer> reasons = context.computeOnClient(client -> {
                    Map<String, Integer> counts = new TreeMap<>();
                    for (var block : AgentManager.get().focused().unsupported()) {
                        counts.merge(block.reason().name(), 1, Integer::sum);
                    }
                    return counts;
                });
                this.line("left out before building: " + unsupported + " " + reasons);
                context.runOnClient(client -> AgentManager.get().acceptSkips(id));
            }
            context.waitFor(client -> state(id) == SessionState.SCANNING_STORAGE, 600);
            this.scanStorage(context, server);
            server.runCommand("tp @a " + START.getX() + " " + START.getY() + " " + START.getZ() + " -90 20");
            context.waitTicks(20);
            this.line("state before start: " + state(context, id) + " (materials incomplete on purpose)");
            // The player's own settings, e.g. LITEMATICA_TEST_STRATEGY=LAYERS_TOP_DOWN LITEMATICA_TEST_SELECTION=minecraft:stone
            String strategy = System.getenv("LITEMATICA_TEST_STRATEGY");
            String selection = System.getenv("LITEMATICA_TEST_SELECTION");
            context.runOnClient(client -> {
                AgentSession session = AgentManager.get().findSession(id);
                if (strategy != null && !strategy.isBlank()) {
                    session.strategy = BuildStrategy.valueOf(strategy.trim().toUpperCase(Locale.ROOT));
                }
                session.blockQueue.clear();
                if (selection != null && !selection.isBlank()) {
                    for (String item : selection.split(",")) {
                        session.blockQueue.add(item.trim());
                    }
                }
            });
            this.line("strategy: " + context.computeOnClient(client -> AgentManager.get().findSession(id).strategy)
                    + ", selection: " + context.computeOnClient(client -> AgentManager.get().findSession(id).blockQueue)
                    + ", void below: " + voidBelow + ", flight: " + context.computeOnClient(client -> client.player.getAbilities().allowFlying)
                    + ", air placement: " + context.computeOnClient(client -> AgentManager.get().config().airPlacement));
            context.takeScreenshot("huge-01-ready");

            long started = System.currentTimeMillis();
            int doneAtStart = doneBlocks(context, id);
            this.line("done at start: " + doneAtStart);
            GameTestSupport.resetRotationCheck();
            context.runOnClient(client -> AgentManager.get().begin(id));
            GameTestSupport.awaitLoading(context);
            int firstBlockTicks = -1;
            int firstBlockLimit = Integer.parseInt(System.getenv().getOrDefault("LITEMATICA_TEST_FIRST_BLOCK_SECONDS", "90"));
            try {
                // Blocks that stood already count as done from the start; the first one the agent places is what matters.
                firstBlockTicks = context.waitFor(client -> placedBlocks(id) > 0, 20 * firstBlockLimit);
            } catch (AssertionError e) {
                this.line("NO BLOCK within " + firstBlockLimit + " s: " + context.computeOnClient(client -> summary()));
            }
            this.line("first block after " + (firstBlockTicks < 0 ? "never" : firstBlockTicks / 20.0 + " s (" + firstBlockTicks + " ticks)"));

            int pauses = this.watch(context, id, started);
            context.takeScreenshot("huge-02-after-watch");
            AgentSession session = context.computeOnClient(client -> AgentManager.get().findSession(id));
            int done = session == null ? -1 : session.placedBlocks;
            this.line("after " + WATCH_SECONDS + " s: placed by the agent=" + done + " (done "
                    + (session == null ? -1 : session.doneBlocks - doneAtStart) + " vs. start) pauses=" + pauses
                    + " state=" + (session == null ? "gone" : session.state));
            this.line("rotation mismatches (anti-cheat relevant): " + GameTestSupport.rotationMismatches());
            this.line("failures by reason: " + context.computeOnClient(client -> failuresByReason()));
            this.line("planks kept: " + context.computeOnClient(client -> countPlanks(client.world)) + " of 66 wrong blocks left standing");
            this.line("heap at the end: " + usedHeapMb() + " MB");
            this.writeReport();

            context.runOnClient(client -> AgentManager.get().pauseActive());
            context.waitTicks(5);
            if (done < TARGET_BLOCKS) {
                throw new AssertionError("Only " + done + " blocks built in " + WATCH_SECONDS + " s, see " + this.reportFile);
            }
            if (pauses > 0) {
                throw new AssertionError(pauses + " pauses in skip-all mode, see " + this.reportFile);
            }
            context.runOnClient(client -> GameTestSupport.removePlacement(client, placement));
        }
        GameTestSupport.logMemory("after " + getClass().getSimpleName());
    }

    /**
     * The plot as another agent (or the player) left it: a stretch of the first two layers already built - the file's
     * first layer is nearly all stone, so a stone fill is right in most places and wrong in a few (cobblestone, dirt)
     * - and a patch of planks that is plain wrong. The storage: two double chests of stone, one with odds and ends.
     */
    private void buildSurroundings(TestServerContext server) {
        server.runCommand("fill " + EDGE + " -60 -20 " + (EDGE + 34) + " -60 20 stone");
        server.runCommand("fill " + EDGE + " -59 -10 " + (EDGE + 19) + " -59 10 stone");
        server.runCommand("fill " + (EDGE + 4) + " -60 25 " + (EDGE + 14) + " -60 30 oak_planks");
        server.runCommand("fill " + (EDGE + 2) + " -59 -3 " + (EDGE + 2) + " -58 3 oak_planks");
        StringBuilder stone = new StringBuilder();
        for (int slot = 0; slot < 27; slot++) {
            stone.append(slot > 0 ? "," : "").append("{Slot:").append(slot).append("b,id:\"minecraft:stone\",count:64}");
        }
        String mixed = "{Slot:0b,id:\"minecraft:cobblestone\",count:64},{Slot:1b,id:\"minecraft:cobblestone\",count:64},"
                + "{Slot:2b,id:\"minecraft:dirt\",count:64},{Slot:3b,id:\"minecraft:dirt\",count:64},"
                + "{Slot:4b,id:\"minecraft:gravel\",count:64},{Slot:5b,id:\"minecraft:mossy_cobblestone\",count:64},"
                + "{Slot:6b,id:\"minecraft:andesite\",count:64},{Slot:7b,id:\"minecraft:bread\",count:64},"
                + "{Slot:8b,id:\"minecraft:sand\",count:64},{Slot:9b,id:\"minecraft:oak_log\",count:32}";
        // LITEMATICA_TEST_STONE_ONLY=true: nothing but stone, like the player's "a few double chests of stone".
        boolean stoneOnly = "true".equalsIgnoreCase(System.getenv("LITEMATICA_TEST_STONE_ONLY"));
        for (int i = 0; i < CHEST_ROWS.length; i++) {
            int row = CHEST_ROWS[i];
            String left = i < 2 || stoneOnly ? stone.toString() : mixed;
            String right = i < 2 || stoneOnly ? stone.toString() : "{Slot:0b,id:\"minecraft:stone\",count:64}";
            server.runCommand("setblock " + CHEST_X + " -60 " + row + " chest[facing=east,type=left]{Items:[" + left + "]}");
            server.runCommand("setblock " + CHEST_X + " -60 " + (row + 1) + " chest[facing=east,type=right]{Items:[" + right + "]}");
        }
        this.line("plot: 35x41 of layer 0 and 20x21 of layer 1 already stone, 66 + 14 planks wrong; storage: "
                + (stoneOnly ? "3 double chests stone (10368), nothing else" : "2 double chests stone (6912), one mixed"));
    }

    /**
     * Removes the ground under the placement except for the platform the player starts on (the pre-built stretch of
     * the plot, see {@link #buildSurroundings}), down to the bedrock two blocks below the file's first layer: like the
     * void under a plot on a plot server. 32768 blocks per fill command, so it goes in strips.
     */
    private void digVoid(TestServerContext server) {
        int platformMinX = EDGE;
        int platformMaxX = EDGE + 34;
        int platformMinZ = -20;
        int platformMaxZ = 20;
        for (int x = EDGE - 8; x < EDGE + 200; x += 25) {
            for (int z = -120; z < 120; z += 60) {
                int x2 = x + 24;
                int z2 = z + 59;
                // Strips that cross the platform are dug around it.
                if (x2 >= platformMinX && x <= platformMaxX && z2 >= platformMinZ && z <= platformMaxZ) {
                    if (z < platformMinZ) {
                        server.runCommand("fill " + x + " -63 " + z + " " + x2 + " -61 " + (platformMinZ - 1) + " air");
                    }
                    if (z2 > platformMaxZ) {
                        server.runCommand("fill " + x + " -63 " + (platformMaxZ + 1) + " " + x2 + " -61 " + z2 + " air");
                    }
                    if (x < platformMinX) {
                        server.runCommand("fill " + x + " -63 " + Math.max(z, platformMinZ) + " " + (platformMinX - 1) + " -61 "
                                + Math.min(z2, platformMaxZ) + " air");
                    }
                    if (x2 > platformMaxX) {
                        server.runCommand("fill " + (platformMaxX + 1) + " -63 " + Math.max(z, platformMinZ) + " " + x2 + " -61 "
                                + Math.min(z2, platformMaxZ) + " air");
                    }
                    continue;
                }
                server.runCommand("fill " + x + " -63 " + z + " " + x2 + " -61 " + z2 + " air");
            }
        }
        this.line("void: ground removed under the placement (x " + (EDGE - 8) + ".." + (EDGE + 200) + ", z -120..120) except the platform");
    }

    private void scanStorage(ClientGameTestContext context, TestServerContext server) {
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

    /** Samples the agent for {@link #WATCH_SECONDS}; a pause is recorded and lifted like a player pressing Resume. */
    private int watch(ClientGameTestContext context, int id, long started) {
        int pauses = 0;
        int lastDone = 0;
        for (int elapsed = SAMPLE_SECONDS; elapsed <= WATCH_SECONDS; elapsed += SAMPLE_SECONDS) {
            for (int tick = 0; tick < SAMPLE_SECONDS * 20; tick += 20) {
                context.waitTicks(20);
                AgentSession session = context.computeOnClient(client -> AgentManager.get().findSession(id));
                if (session == null) {
                    this.line("session gone (completed?) after " + (System.currentTimeMillis() - started) / 1000 + " s");
                    return pauses;
                }
                if (session.state == SessionState.PAUSED) {
                    pauses++;
                    if (pauses <= 5) {
                        this.line("PAUSE #" + pauses + " after " + (System.currentTimeMillis() - started) / 1000 + " s: "
                                + session.pauseReason + " " + session.pauseArgs);
                        this.line("  " + context.computeOnClient(client -> summary()));
                    }
                    // Lifted like a player pressing Resume - a few times; a problem that comes straight back is not
                    // solved by pressing again, the report just counts it.
                    if (pauses <= 5) {
                        context.runOnClient(client -> AgentManager.get().resume(id));
                        GameTestSupport.awaitLoading(context);
                    }
                }
            }
            int done = context.computeOnClient(client -> placedBlocks(id));
            String summary = context.computeOnClient(client -> summary());
            this.line(String.format(Locale.ROOT, "%3d s: placed=%d (+%d) heap=%d MB %s", elapsed, done, done - lastDone,
                    usedHeapMb(), summary.split("\\R")[0]));
            lastDone = done;
            if (elapsed % 60 == 0) {
                context.takeScreenshot("huge-watch-" + elapsed);
                this.line("  failures so far: " + context.computeOnClient(client -> failuresByReason()));
            }
        }
        return pauses;
    }

    private static String summary() {
        BuildAgent agent = AgentManager.get().activeAgent();
        return agent == null ? "no active agent" : agent.debugSummary();
    }

    private static Map<String, Integer> failuresByReason() {
        Map<String, Integer> counts = new TreeMap<>();
        BuildAgent agent = AgentManager.get().activeAgent();
        if (agent != null) {
            for (String reason : agent.failures().values()) {
                counts.merge(reason, 1, Integer::sum);
            }
        }
        return counts;
    }

    private static int countPlanks(net.minecraft.world.World world) {
        int count = 0;
        for (BlockPos pos : BlockPos.iterate(EDGE + 4, -60, 25, EDGE + 14, -60, 30)) {
            if (world.getBlockState(pos).isOf(Blocks.OAK_PLANKS)) {
                count++;
            }
        }
        return count;
    }

    private static SessionState state(int id) {
        AgentSession session = AgentManager.get().findSession(id);
        return session == null ? null : session.state;
    }

    private static SessionState state(ClientGameTestContext context, int id) {
        return context.computeOnClient(client -> state(id));
    }

    /** Blocks the agent itself placed in this session (pre-existing blocks and rescans do not count). */
    private static int placedBlocks(int id) {
        AgentSession session = AgentManager.get().findSession(id);
        return session == null ? -1 : session.placedBlocks;
    }

    private static int doneBlocks(int id) {
        AgentSession session = AgentManager.get().findSession(id);
        return session == null ? -1 : session.doneBlocks;
    }

    private static int doneBlocks(ClientGameTestContext context, int id) {
        return context.computeOnClient(client -> doneBlocks(id));
    }

    private static long usedHeapMb() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    }

    private void line(String text) {
        this.report.add(text);
        GameTestSupport.LOGGER.info("[huge] {}", text);
    }

    private void writeReport() {
        try {
            Files.createDirectories(this.reportFile.toAbsolutePath().getParent());
            Files.write(this.reportFile, this.report);
        } catch (IOException e) {
            GameTestSupport.LOGGER.error("Could not write {}", this.reportFile, e);
        }
    }
}

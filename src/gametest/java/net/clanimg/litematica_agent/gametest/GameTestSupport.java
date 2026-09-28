package net.clanimg.litematica_agent.gametest;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import net.clanimg.litematica_agent.agent.AgentManager;
import net.clanimg.litematica_agent.agent.AgentSession;
import net.clanimg.litematica_agent.placement.StateMatcher;
import net.clanimg.litematica_agent.schematic.BuildTarget;
import net.clanimg.litematica_agent.schematic.SchematicAccess;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

final class GameTestSupport {
    static final Logger LOGGER = LoggerFactory.getLogger("litematica_agent_gametest");

    private GameTestSupport() {
    }

    /**
     * Turns the blocks currently in the area into a Litematica schematic and places it at the same position.
     */
    static SchematicPlacement createPlacement(MinecraftClient client, String name, BlockPos min, BlockPos max) {
        AreaSelection area = new AreaSelection();
        area.setName(name);
        area.addSubRegionBox(new Box(min, max, "main"), false);
        area.setExplicitOrigin(min);
        LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(client.world, area,
                new LitematicaSchematic.SchematicSaveInfo(false, true), "gametest", LOGGER::info);
        if (schematic == null) {
            throw new AssertionError("Litematica could not create the schematic");
        }
        SchematicPlacement placement = SchematicPlacement.createFor(schematic, min, name, true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
        DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(placement);
        return placement;
    }

    /**
     * Like {@link #createPlacement} but backed by a .litematic file, so Litematica can restore the placement after
     * the world is left and joined again.
     */
    static SchematicPlacement createFilePlacement(MinecraftClient client, String name, BlockPos min, BlockPos max) {
        AreaSelection area = new AreaSelection();
        area.setName(name);
        area.addSubRegionBox(new Box(min, max, "main"), false);
        area.setExplicitOrigin(min);
        LitematicaSchematic schematic = LitematicaSchematic.createFromWorld(client.world, area,
                new LitematicaSchematic.SchematicSaveInfo(false, true), "gametest", LOGGER::info);
        java.nio.file.Path dir = DataManager.getSchematicsBaseDirectory();
        if (schematic == null || !schematic.writeToFile(dir, name, true)) {
            throw new AssertionError("Could not write schematic " + name);
        }
        LitematicaSchematic loaded = LitematicaSchematic.createFromFile(dir, name + ".litematic");
        if (loaded == null) {
            throw new AssertionError("Could not load schematic " + name);
        }
        SchematicPlacement placement = SchematicPlacement.createFor(loaded, min, name, true, true);
        DataManager.getSchematicPlacementManager().addSchematicPlacement(placement, false);
        DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(placement);
        return placement;
    }

    /**
     * Logs heap usage after a full GC, to spot leaks between test worlds.
     */
    static void logMemory(String label) {
        System.gc();
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        LOGGER.info("[memory] {}: {} MB used of {} MB", label, usedMb, runtime.maxMemory() / (1024 * 1024));
    }

    static void removePlacement(MinecraftClient client, SchematicPlacement placement) {
        DataManager.getSchematicPlacementManager().removeSchematicPlacement(placement);
    }

    /**
     * Waits until the session is completed (removed) or paused. Returns the paused session or null when completed.
     */
    static AgentSession waitForAgent(ClientGameTestContext context, int sessionId, int timeoutTicks) {
        int waited = 0;
        while (waited < timeoutTicks) {
            boolean done = context.computeOnClient(client -> {
                AgentSession session = AgentManager.get().findSession(sessionId);
                return session == null || session.state == net.clanimg.litematica_agent.agent.SessionState.PAUSED;
            });
            if (done) {
                return context.computeOnClient(client -> AgentManager.get().findSession(sessionId));
            }
            context.waitTicks(100);
            waited += 100;
            if (waited % 1200 == 0) {
                LOGGER.info("agent after {}s: {}", waited / 20, context.computeOnClient(client -> summary()));
            }
        }
        context.takeScreenshot("timeout");
        String summary = context.computeOnClient(client -> summary());
        LOGGER.error("agent timed out: {}", summary);
        throw new AssertionError("Agent did not finish in time: " + summary);
    }

    private static String summary() {
        var agent = AgentManager.get().activeAgent();
        return agent == null ? "no active agent" : agent.debugSummary();
    }

    /**
     * Lists every schematic block that does not match the world.
     */
    static List<String> mismatches(MinecraftClient client, SchematicPlacement placement) {
        List<String> result = new ArrayList<>();
        for (BuildTarget target : SchematicAccess.readTargets(placement).targets()) {
            BlockState state = client.world.getBlockState(target.pos());
            if (!StateMatcher.isComplete(state, target.state())) {
                result.add(target.pos().toShortString() + " expected " + target.state() + " but found " + state);
            }
        }
        return result;
    }

    static void assertBuilt(ClientGameTestContext context, SchematicPlacement placement, String label) {
        List<String> mismatches = context.computeOnClient(client -> mismatches(client, placement));
        int total = context.computeOnClient(client -> SchematicAccess.readTargets(placement).targets().size());
        LOGGER.info("[{}] {} of {} schematic blocks match", label, total - mismatches.size(), total);
        if (!mismatches.isEmpty()) {
            mismatches.forEach(line -> LOGGER.error("[{}] mismatch: {}", label, line));
            throw new AssertionError(label + ": " + mismatches.size() + " of " + total + " blocks do not match, first: " + mismatches.get(0));
        }
    }
}

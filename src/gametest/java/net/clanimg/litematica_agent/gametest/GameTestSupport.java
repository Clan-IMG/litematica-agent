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
import net.clanimg.litematica_agent.ui.Chat;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

final class GameTestSupport {
    static final Logger LOGGER = LoggerFactory.getLogger("litematica_agent_gametest");
    /**
     * Right-clicks on blocks the server received while the view direction it knew did not point at the clicked block.
     * Anti-cheats such as Grim (RotationPlace) flag exactly that, so every test expects none.
     */
    private static final AtomicInteger ROTATION_MISMATCHES = new AtomicInteger();

    static {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClient() && !viewPointsAt(player, hit.getBlockPos())) {
                ROTATION_MISMATCHES.incrementAndGet();
                LOGGER.warn("Right-click on {} while the server's view direction does not point at it (yaw {}, pitch {}, eye {})",
                        hit.getBlockPos().toShortString(), player.getYaw(), player.getPitch(), player.getEyePos());
            }
            return ActionResult.PASS;
        });
    }

    private GameTestSupport() {
    }

    /** Like Grim: a ray along the view direction, from the standing or the crouching eye, reaches the clicked block. */
    private static boolean viewPointsAt(PlayerEntity player, BlockPos pos) {
        net.minecraft.util.math.Box block = new net.minecraft.util.math.Box(pos);
        Vec3d direction = player.getRotationVector().multiply(player.getBlockInteractionRange() + 1.0);
        for (float eyeHeight : new float[]{player.getEyeHeight(EntityPose.STANDING), player.getEyeHeight(EntityPose.CROUCHING)}) {
            Vec3d eye = player.getEntityPos().add(0.0, eyeHeight, 0.0);
            if (block.contains(eye) || block.raycast(eye, eye.add(direction)).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** Start, resume and stock read the schematic in the background; waits until that is done. */
    static void awaitLoading(ClientGameTestContext context) {
        context.waitFor(client -> !AgentManager.get().isLoading(), 20 * 120);
    }

    /** Starts counting right-clicks with a view direction that does not fit (see {@link #ROTATION_MISMATCHES}). */
    static void resetRotationCheck() {
        ROTATION_MISMATCHES.set(0);
    }

    static int rotationMismatches() {
        return ROTATION_MISMATCHES.get();
    }

    static void assertNoRotationMismatches(String what) {
        int mismatches = ROTATION_MISMATCHES.get();
        if (mismatches > 0) {
            throw new AssertionError(what + ": " + mismatches + " right-clicks with a view direction an anti-cheat would flag");
        }
    }

    /**
     * Clicks the button showing the mod text {@code messageKey}. The texts come from the mod's message files, not from
     * the game's language files, so the button is looked up by the text it shows (a key unknown to the game's language
     * translates to itself).
     */
    static void clickButton(ClientGameTestContext context, String messageKey) {
        String label = context.computeOnClient(client -> Chat.tr(messageKey).getString());
        context.clickScreenButton(label);
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

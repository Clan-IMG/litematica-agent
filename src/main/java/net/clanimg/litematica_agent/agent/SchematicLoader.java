package net.clanimg.litematica_agent.agent;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.clanimg.litematica_agent.schematic.SchematicAccess;
import net.clanimg.litematica_agent.schematic.SurvivalCheck;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.world.World;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Reads schematics away from the render thread, so even huge schematics never freeze the game. The blocks, the
 * survival check and the build plan are prepared on background threads; only the item lookup per block type runs on
 * the render thread, because Litematica's material cache is not made for other threads, and there are only as many
 * block types as the schematic uses.
 */
final class SchematicLoader {
    private static final ExecutorService READER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Litematica Agent schematic reader");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private record Read(SchematicAccess.SchematicBlocks blocks, Map<BlockState, SchematicAccess.Requirement> requirements) {
    }

    private SchematicLoader() {
    }

    /**
     * @param finish turns the read schematic into the result; runs on the background thread as well
     */
    static <T> CompletableFuture<T> load(MinecraftClient client, SchematicPlacement placement, World world,
                                         Function<SchematicAccess.SchematicReadResult, T> finish) {
        return CompletableFuture.supplyAsync(() -> SchematicAccess.readBlocks(placement), READER)
                .thenApplyAsync(blocks -> new Read(blocks, SchematicAccess.requirementsFor(blocks.distinct())), client)
                .thenApplyAsync(read -> finish.apply(SurvivalCheck.apply(
                        SchematicAccess.toResult(read.blocks().states(), read.requirements()), world)), READER);
    }
}

package net.clanimg.litematica_agent.schematic;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.clanimg.litematica_agent.LitematicaAgentClient;
import net.clanimg.litematica_agent.placement.WaterPlacement;
import net.clanimg.litematica_agent.schematic.SchematicAccess.UnsupportedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.SpreadableBlock;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;
import net.minecraft.world.attribute.EnvironmentAttributes;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

/**
 * Finds schematic blocks that cannot exist in survival next to the neighbours the schematic gives them: blocks that
 * would pop off (a candle on a lever, a torch without a wall), fall down, or turn into another block (grass under a
 * block becomes dirt). Such schematics are usually edited with block updates turned off.
 * <p>
 * The world is only read, never changed, so the check may run on background threads, spread over several cores.
 */
public final class SurvivalCheck {
    /** Half of the cores: the check is fast, and the game keeps running smoothly next to it. */
    private static final ForkJoinPool POOL = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors() / 2),
            pool -> {
                var thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                thread.setName("Litematica Agent survival check " + thread.getPoolIndex());
                thread.setDaemon(true);
                return thread;
            }, null, false);

    private enum Outcome {
        KEEP,
        DIRT,
        MISSING_WATER,
        DROP,
        /** Below the bottom or above the top of the world: no block can ever be placed there. */
        OUTSIDE_WORLD
    }

    private SurvivalCheck() {
    }

    public static SchematicAccess.SchematicReadResult apply(SchematicAccess.SchematicReadResult read, World world) {
        List<BuildTarget> input = read.targets();
        WorldView finished = finishedWorld(world, read.states());
        Outcome[] outcomes;
        try {
            outcomes = POOL.submit(() -> IntStream.range(0, input.size()).parallel()
                    .mapToObj(i -> check(input.get(i), world, finished, read.states()))
                    .toArray(Outcome[]::new)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Survival check interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Survival check failed", e.getCause());
        }

        List<BuildTarget> targets = new ArrayList<>(input.size());
        List<UnsupportedBlock> unsupported = new ArrayList<>(read.unsupported());
        for (int i = 0; i < input.size(); i++) {
            BuildTarget target = input.get(i);
            switch (outcomes[i]) {
                case KEEP -> targets.add(target);
                case DIRT -> {
                    unsupported.add(new UnsupportedBlock(target.pos(), target.state(), UnsupportedBlock.Reason.BECOMES_DIRT));
                    targets.add(new BuildTarget(target.pos(), Blocks.DIRT.getDefaultState(), Items.DIRT, 1));
                }
                case DROP -> unsupported.add(new UnsupportedBlock(target.pos(), target.state(), UnsupportedBlock.Reason.NO_SUPPORT));
                case MISSING_WATER -> unsupported.add(new UnsupportedBlock(target.pos(), target.state(),
                        UnsupportedBlock.Reason.MISSING_WATER));
                case OUTSIDE_WORLD -> unsupported.add(new UnsupportedBlock(target.pos(), target.state(),
                        UnsupportedBlock.Reason.OUTSIDE_WORLD));
            }
        }
        return new SchematicAccess.SchematicReadResult(targets, unsupported, read.states());
    }

    private static Outcome check(BuildTarget target, World world, WorldView finished, Long2ObjectMap<BlockState> states) {
        BlockPos pos = target.pos();
        try {
            // Checked before the chunk: a placement reaching below the world's bottom (or above its top) would
            // otherwise keep whole layers of impossible targets in the plan, which the agent keeps trying first.
            if (world.isOutOfHeightLimit(pos)) {
                return Outcome.OUTSIDE_WORLD;
            }
            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                return Outcome.KEEP;
            }
            // Water is poured with buckets (FluidTask), except where it evaporates at once, as in the Nether.
            if (WaterPlacement.involvesWater(target.state()) && Boolean.TRUE.equals(world.getEnvironmentAttributes()
                    .getAttributeValue(EnvironmentAttributes.WATER_EVAPORATES_GAMEPLAY, pos))) {
                return Outcome.MISSING_WATER;
            }
            if (decaysToDirt(target.state(), finished.getBlockState(pos.up()))) {
                return Outcome.DIRT;
            }
            if (target.state().getBlock() instanceof FallingBlock && FallingBlock.canFallThrough(finished.getBlockState(pos.down()))) {
                return Outcome.DROP;
            }
            return target.state().canPlaceAt(finished, pos) ? Outcome.KEEP : Outcome.DROP;
        } catch (RuntimeException e) {
            // The world changed while it was read (a chunk loading in): better keep the block than lose it.
            LitematicaAgentClient.LOGGER.debug("Survival check of {} failed", pos, e);
            return Outcome.KEEP;
        }
    }

    /** Grass and mycelium turn into dirt under an opaque block or water. */
    private static boolean decaysToDirt(BlockState state, BlockState above) {
        return state.getBlock() instanceof SpreadableBlock
                && (above.isOpaqueFullCube() || above.getFluidState().getLevel() == 8);
    }

    /**
     * The world as it will be once the schematic is built: schematic blocks (and their water) where the schematic has
     * blocks, the real world everywhere else. Read-only. Everything else goes to the real world, default methods run
     * against this view so they see the schematic too. Methods are matched by their signature, which stays the same
     * whatever the obfuscation names are.
     */
    static WorldView finishedWorld(World world, Long2ObjectMap<BlockState> states) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (args != null && args.length == 1 && args[0] instanceof BlockPos pos) {
                if (method.getReturnType() == BlockState.class) {
                    BlockState state = states.get(pos.asLong());
                    return state != null ? state : world.getBlockState(pos);
                }
                if (method.getReturnType() == FluidState.class) {
                    // The builder pours the schematic's water, so a lily pad on it or seagrass in it can stay.
                    BlockState state = states.get(pos.asLong());
                    return state != null ? state.getFluidState() : world.getFluidState(pos);
                }
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            try {
                return method.invoke(world, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(), new Class<?>[]{WorldView.class}, handler);
    }
}

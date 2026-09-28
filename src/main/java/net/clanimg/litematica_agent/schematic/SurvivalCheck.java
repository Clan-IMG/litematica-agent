package net.clanimg.litematica_agent.schematic;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.clanimg.litematica_agent.schematic.SchematicAccess.UnsupportedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.SpreadableBlock;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds schematic blocks that cannot exist in survival next to the neighbours the schematic gives them: blocks that
 * would pop off (a candle on a lever, a torch without a wall), fall down, or turn into another block (grass under a
 * block becomes dirt). Such schematics are usually edited with block updates turned off.
 */
public final class SurvivalCheck {
    private static final int OVERRIDE_FLAGS = Block.FORCE_STATE | Block.SKIP_DROPS;

    private SurvivalCheck() {
    }

    public static SchematicAccess.SchematicReadResult apply(SchematicAccess.SchematicReadResult read, World world) {
        List<BuildTarget> targets = new ArrayList<>();
        List<UnsupportedBlock> unsupported = new ArrayList<>(read.unsupported());
        for (BuildTarget target : read.targets()) {
            BlockPos pos = target.pos();
            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                targets.add(target);
                continue;
            }
            if (decaysToDirt(target.state(), finalState(pos.up(), read.states(), world))) {
                unsupported.add(new UnsupportedBlock(pos, target.state(), UnsupportedBlock.Reason.BECOMES_DIRT));
                targets.add(new BuildTarget(pos, Blocks.DIRT.getDefaultState(), Items.DIRT, 1));
                continue;
            }
            if (!canStay(target.state(), pos, read.states(), world)) {
                unsupported.add(new UnsupportedBlock(pos, target.state(), UnsupportedBlock.Reason.NO_SUPPORT));
                continue;
            }
            targets.add(target);
        }
        return new SchematicAccess.SchematicReadResult(targets, unsupported, read.states());
    }

    /** The block at {@code pos} once the build is finished: the schematic block, or what is in the world now. */
    private static BlockState finalState(BlockPos pos, Long2ObjectMap<BlockState> states, World world) {
        BlockState state = states.get(pos.asLong());
        return state != null ? state : world.getBlockState(pos);
    }

    /** Grass and mycelium turn into dirt under an opaque block or water. */
    private static boolean decaysToDirt(BlockState state, BlockState above) {
        return state.getBlock() instanceof SpreadableBlock
                && (above.isOpaqueFullCube() || above.getFluidState().getLevel() == 8);
    }

    /**
     * Asks the block itself whether it can stay, with its neighbours set to their schematic states for the duration of
     * the check (only in the client world; the same technique the placement solver uses for helper blocks).
     */
    private static boolean canStay(BlockState state, BlockPos pos, Long2ObjectMap<BlockState> states, World world) {
        if (state.getBlock() instanceof FallingBlock && FallingBlock.canFallThrough(finalState(pos.down(), states, world))) {
            return false;
        }
        List<BlockPos> changed = new ArrayList<>();
        List<BlockState> previous = new ArrayList<>();
        try {
            for (Direction direction : Direction.values()) {
                BlockPos neighbour = pos.offset(direction);
                BlockState wanted = states.get(neighbour.asLong());
                BlockState current = world.getBlockState(neighbour);
                // Block entities (chests, signs...) would lose their client-side data, so they are left alone.
                if (wanted == null || wanted == current || current.hasBlockEntity()) {
                    continue;
                }
                changed.add(neighbour);
                previous.add(current);
                world.setBlockState(neighbour, wanted, OVERRIDE_FLAGS, 0);
            }
            return state.canPlaceAt(world, pos);
        } finally {
            for (int i = changed.size() - 1; i >= 0; i--) {
                world.setBlockState(changed.get(i), previous.get(i), OVERRIDE_FLAGS, 0);
            }
        }
    }
}

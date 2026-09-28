package net.clanimg.litematica_agent.movement;

import net.clanimg.litematica_agent.movement.pathing.NavWorld;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CactusBlock;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.CobwebBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.MagmaBlock;
import net.minecraft.block.PowderSnowBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.block.WitherRoseBlock;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

import java.util.function.LongPredicate;

public final class WorldNavAdapter implements NavWorld {
    private static final double LOW_BLOCK_HEIGHT = 0.5;

    private final World world;
    private final BlockPos.Mutable mutable = new BlockPos.Mutable();
    private final LongPredicate virtualSolid;
    private final LongPredicate helperBlocks;

    public WorldNavAdapter(World world, LongPredicate virtualSolid) {
        this(world, virtualSolid, value -> false);
    }

    /**
     * @param virtualSolid positions (packed like {@link net.clanimg.litematica_agent.movement.pathing.PosUtil})
     *                     treated as blocked, e.g. blocks the agent is about to place
     * @param helperBlocks positions of the agent's own helper blocks, which may be dug away
     */
    public WorldNavAdapter(World world, LongPredicate virtualSolid, LongPredicate helperBlocks) {
        this.world = world;
        this.virtualSolid = virtualSolid;
        this.helperBlocks = helperBlocks;
    }

    @Override
    public int flags(int x, int y, int z) {
        if (y < this.world.getBottomY() || y >= this.world.getTopYInclusive() + 1) {
            return y < this.world.getBottomY() ? UNLOADED : PASSABLE;
        }
        if (!this.world.isChunkLoaded(x >> 4, z >> 4)) {
            return UNLOADED;
        }
        this.mutable.set(x, y, z);
        if (this.virtualSolid.test(this.mutable.asLong())) {
            return SOLID_TOP;
        }
        BlockState state = this.world.getBlockState(this.mutable);
        int flags = 0;

        FluidState fluid = state.getFluidState();
        if (fluid.isIn(FluidTags.LAVA)) {
            return DANGER;
        }
        if (fluid.isIn(FluidTags.WATER)) {
            flags |= WATER;
        }
        if (isDangerous(state)) {
            flags |= DANGER;
        }
        if (state.isIn(BlockTags.CLIMBABLE)) {
            flags |= CLIMBABLE;
        }

        VoxelShape collision = state.getCollisionShape(this.world, this.mutable);
        if (collision.isEmpty() || isOpenPassage(state)) {
            flags |= PASSABLE;
        } else {
            double top = collision.getMax(net.minecraft.util.math.Direction.Axis.Y);
            if (top <= LOW_BLOCK_HEIGHT && !state.isIn(BlockTags.FENCES) && !state.isIn(BlockTags.WALLS)) {
                flags |= PASSABLE | SOLID_TOP;
            } else if (top <= 1.0) {
                flags |= SOLID_TOP;
            }
        }
        if (this.helperBlocks.test(this.mutable.asLong())) {
            flags |= HELPER;
        }
        return flags;
    }

    private static boolean isOpenPassage(BlockState state) {
        if (state.getBlock() instanceof DoorBlock) {
            return state.get(Properties.OPEN);
        }
        return state.getBlock() instanceof FenceGateBlock && state.get(Properties.OPEN);
    }

    private static boolean isDangerous(BlockState state) {
        return state.getBlock() instanceof AbstractFireBlock
                || state.getBlock() instanceof MagmaBlock
                || state.getBlock() instanceof CactusBlock
                || state.getBlock() instanceof SweetBerryBushBlock
                || state.getBlock() instanceof WitherRoseBlock
                || state.getBlock() instanceof PowderSnowBlock
                || state.getBlock() instanceof CobwebBlock
                || (state.getBlock() instanceof CampfireBlock && state.get(Properties.LIT))
                || state.isOf(Blocks.POINTED_DRIPSTONE);
    }
}

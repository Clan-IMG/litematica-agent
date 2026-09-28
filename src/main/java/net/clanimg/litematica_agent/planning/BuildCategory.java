package net.clanimg.litematica_agent.planning;

import net.minecraft.block.AbstractBannerBlock;
import net.minecraft.block.AbstractPressurePlateBlock;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.AbstractRedstoneGateBlock;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.CrafterBlock;
import net.minecraft.block.DaylightDetectorBlock;
import net.minecraft.block.DispenserBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.HopperBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.LightningRodBlock;
import net.minecraft.block.NoteBlock;
import net.minecraft.block.ObserverBlock;
import net.minecraft.block.PistonBlock;
import net.minecraft.block.RedstoneLampBlock;
import net.minecraft.block.RedstoneTorchBlock;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.block.SculkSensorBlock;
import net.minecraft.block.TargetBlock;
import net.minecraft.block.TntBlock;
import net.minecraft.block.TripwireHookBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

/**
 * Order of placement inside one layer. Redstone is placed after its supports, power sources come last so circuits
 * do not fire while they are still incomplete.
 */
public enum BuildCategory {
    SOLID,
    FALLING,
    ATTACHED,
    REDSTONE,
    ACTUATOR,
    POWER_SOURCE;

    public static BuildCategory classify(BlockState state, BlockView world, BlockPos pos) {
        Block block = state.getBlock();
        if (block instanceof RedstoneTorchBlock || block instanceof LeverBlock || block instanceof DaylightDetectorBlock
                || block instanceof SculkSensorBlock || block instanceof LightningRodBlock
                || state.isOf(Blocks.REDSTONE_BLOCK)) {
            return POWER_SOURCE;
        }
        if (block instanceof PistonBlock || block instanceof ObserverBlock || block instanceof DispenserBlock
                || block instanceof CrafterBlock || block instanceof TntBlock || block instanceof HopperBlock) {
            return ACTUATOR;
        }
        if (block instanceof RedstoneWireBlock || block instanceof AbstractRedstoneGateBlock
                || block instanceof RedstoneLampBlock || block instanceof NoteBlock || block instanceof TargetBlock) {
            return REDSTONE;
        }
        if (block instanceof FallingBlock) {
            return FALLING;
        }
        if (block instanceof ButtonBlock || block instanceof AbstractPressurePlateBlock || block instanceof AbstractRailBlock
                || block instanceof AbstractSignBlock || block instanceof AbstractBannerBlock
                || block instanceof TripwireHookBlock || state.getCollisionShape(world, pos).isEmpty()) {
            return ATTACHED;
        }
        return SOLID;
    }

    public static boolean needsBlockBelow(BlockState state) {
        return state.getBlock() instanceof FallingBlock;
    }
}

package net.clanimg.litematica_agent.placement;

import net.minecraft.block.AbstractRedstoneGateBlock;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.BeehiveBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BellBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.CakeBlock;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.CaveVines;
import net.minecraft.block.ChiseledBookshelfBlock;
import net.minecraft.block.CommandBlock;
import net.minecraft.block.ComposterBlock;
import net.minecraft.block.DaylightDetectorBlock;
import net.minecraft.block.DecoratedPotBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.DragonEggBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.FlowerPotBlock;
import net.minecraft.block.JukeboxBlock;
import net.minecraft.block.LecternBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.NoteBlock;
import net.minecraft.block.RedstoneWireBlock;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.AbstractCauldronBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Blocks that do something when right-clicked. Placing against them requires sneaking, otherwise the click opens or
 * toggles the block instead of placing.
 */
public final class Interactables {
    private Interactables() {
    }

    public static boolean needsSneak(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.createScreenHandlerFactory(world, pos) != null) {
            return true;
        }
        Block block = state.getBlock();
        return block instanceof DoorBlock
                || block instanceof TrapdoorBlock
                || block instanceof FenceGateBlock
                || block instanceof LeverBlock
                || block instanceof ButtonBlock
                || block instanceof AbstractRedstoneGateBlock
                || block instanceof RedstoneWireBlock
                || block instanceof NoteBlock
                || block instanceof BedBlock
                || block instanceof BellBlock
                || block instanceof CakeBlock
                || block instanceof AbstractCauldronBlock
                || block instanceof ComposterBlock
                || block instanceof DaylightDetectorBlock
                || block instanceof JukeboxBlock
                || block instanceof LecternBlock
                || block instanceof RespawnAnchorBlock
                || block instanceof FlowerPotBlock
                || block instanceof DragonEggBlock
                || block instanceof CommandBlock
                || block instanceof AbstractSignBlock
                || block instanceof BeehiveBlock
                || block instanceof ChiseledBookshelfBlock
                || block instanceof DecoratedPotBlock
                || block instanceof CampfireBlock
                || block instanceof SweetBerryBushBlock
                || block instanceof CaveVines;
    }
}

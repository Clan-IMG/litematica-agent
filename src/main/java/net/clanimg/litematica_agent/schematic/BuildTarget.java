package net.clanimg.litematica_agent.schematic;

import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;

/**
 * One block the schematic wants at {@link #pos()}.
 *
 * @param item  item needed to place it
 * @param count number of items consumed by the complete state (double slabs, candles, ...)
 */
public record BuildTarget(BlockPos pos, BlockState state, Item item, int count) {
}

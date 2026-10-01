package net.clanimg.litematica_agent.schematic;

import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;

import java.util.Objects;

/**
 * One block the schematic wants at {@link #pos()}.
 * <p>
 * Kept as small as possible: a real schematic can have tens of millions of these, and the game has a few gigabytes
 * of heap. The position is one packed long (see {@link BlockPos#asLong()}) instead of a BlockPos object of its own,
 * which halves the objects to keep alive; {@link #pos()} unpacks it on demand.
 */
public final class BuildTarget {
    private final long packedPos;
    private final BlockState state;
    private final Item item;
    private final int count;

    /**
     * @param item  item needed to place it
     * @param count number of items consumed by the complete state (double slabs, candles, ...)
     */
    public BuildTarget(BlockPos pos, BlockState state, Item item, int count) {
        this(pos.asLong(), state, item, count);
    }

    public BuildTarget(long packedPos, BlockState state, Item item, int count) {
        this.packedPos = packedPos;
        this.state = state;
        this.item = item;
        this.count = count;
    }

    public BlockPos pos() {
        return BlockPos.fromLong(this.packedPos);
    }

    /** The position as {@link BlockPos#asLong()} packs it, without creating a BlockPos. */
    public long posLong() {
        return this.packedPos;
    }

    public BlockState state() {
        return this.state;
    }

    public Item item() {
        return this.item;
    }

    public int count() {
        return this.count;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BuildTarget that && this.packedPos == that.packedPos && this.state == that.state
                && this.item == that.item && this.count == that.count;
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.packedPos, this.state, this.item, this.count);
    }

    @Override
    public String toString() {
        return "BuildTarget[pos=" + this.pos().toShortString() + ", state=" + this.state + ", item=" + this.item
                + ", count=" + this.count + "]";
    }
}

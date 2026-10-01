package net.clanimg.litematica_agent.agent;

/**
 * How the agent works through a schematic.
 */
public enum BuildStrategy {
    /** Bottom to top, one layer after the other (default). */
    LAYERS,
    /** Top to bottom, one layer after the other. */
    LAYERS_TOP_DOWN,
    /** Starts near the player and expands outward, layer by layer within each cluster. */
    PROXIMITY,
    /** Only the block types the player queued, one type after the other (each bottom to top). */
    BLOCKS;

    /** The next strategy in the user-facing cycle (excludes {@link #BLOCKS} which is only set through the queue). */
    public BuildStrategy next() {
        return switch (this) {
            case LAYERS -> LAYERS_TOP_DOWN;
            case LAYERS_TOP_DOWN -> PROXIMITY;
            case PROXIMITY -> LAYERS;
            case BLOCKS -> LAYERS;
        };
    }

    public String id() {
        return this.name().toLowerCase(java.util.Locale.ROOT);
    }
}

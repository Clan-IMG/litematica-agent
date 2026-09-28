package net.clanimg.litematica_agent.agent;

/**
 * How the agent works through a schematic.
 */
public enum BuildStrategy {
    /** Bottom to top, one layer after the other. */
    LAYERS,
    /** Only the block types the player queued, one type after the other (each bottom to top). */
    BLOCKS
}

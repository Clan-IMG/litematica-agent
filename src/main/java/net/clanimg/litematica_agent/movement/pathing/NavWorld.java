package net.clanimg.litematica_agent.movement.pathing;

/**
 * Navigation view of the world, reduced to per-block flags so the pathfinder stays independent of Minecraft.
 */
public interface NavWorld {
    /** The player's body can occupy this block (no collision). */
    int PASSABLE = 1;
    /** The block can be stood upon. */
    int SOLID_TOP = 1 << 1;
    /** Entering or standing on this block hurts (lava, fire, cactus, magma, ...). */
    int DANGER = 1 << 2;
    int WATER = 1 << 3;
    /** Ladders, vines, scaffolding... */
    int CLIMBABLE = 1 << 4;
    /** Chunk not loaded: treated as impassable. */
    int UNLOADED = 1 << 5;
    /** A helper block placed by the agent itself; it may be dug away to climb down. */
    int HELPER = 1 << 6;
    /** Passable, but taken by a block a placed block cannot replace (lever, torch, flower, open door...). */
    int OCCUPIED = 1 << 7;
    /**
     * A low block the player can stand in (a bottom slab, a daylight detector...): the feet are about half a block
     * higher than on the block below, so it cannot be jumped onto from one block lower and needs more headroom.
     */
    int RAISED = 1 << 8;

    int flags(int x, int y, int z);
}

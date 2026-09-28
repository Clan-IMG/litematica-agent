package net.clanimg.litematica_agent.movement.pathing;

/**
 * One step of a path: the feet position of the player after executing {@link #move()}.
 */
public record PathNode(int x, int y, int z, MoveType move) {
    public long packed() {
        return PosUtil.pack(this.x, this.y, this.z);
    }
}

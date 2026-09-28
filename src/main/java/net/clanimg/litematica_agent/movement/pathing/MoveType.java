package net.clanimg.litematica_agent.movement.pathing;

public enum MoveType {
    START(false),
    WALK(false),
    DIAGONAL(false),
    ASCEND(false),
    DESCEND(false),
    SWIM_UP(false),
    CLIMB_UP(false),
    CLIMB_DOWN(false),
    /** Jump and place a helper block below the feet. */
    PILLAR(false),
    /** Break the own helper block below the feet and drop into its place. */
    DIG_DOWN(false),
    FLY(true),
    FLY_UP(true),
    FLY_DOWN(true);

    private final boolean flying;

    MoveType(boolean flying) {
        this.flying = flying;
    }

    public boolean isFlying() {
        return this.flying;
    }
}

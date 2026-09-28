package net.clanimg.litematica_agent.agent;

public enum SessionState {
    /** Survival: waiting until the player empties the inventory (tools, food, armor and offhand may stay). */
    CHECK_INVENTORY,
    /** Survival: asking whether the storage changed since the last scan. */
    CONFIRM_STORAGE,
    /** Survival: the player opens chests and scans them. */
    SCANNING_STORAGE,
    /** Waiting for the player to click [START]. */
    READY,
    BUILDING,
    PAUSED;

    public boolean isPreparation() {
        return this == CHECK_INVENTORY || this == CONFIRM_STORAGE || this == SCANNING_STORAGE;
    }
}

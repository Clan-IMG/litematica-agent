package net.clanimg.litematica_agent.agent;

import net.clanimg.litematica_agent.schematic.PlacementRef;

import java.util.ArrayList;
import java.util.List;

/**
 * Persistent part of a build session. Build progress itself is always re-read from the world.
 */
public final class AgentSession {
    public int id;
    public PlacementRef placement;
    public SessionState state = SessionState.READY;
    /** Translation key describing why the session is paused, empty for a manual pause. */
    public String pauseReason = "";
    public List<String> pauseArgs = new ArrayList<>();
    public long createdAt;
    /** Time spent building, pauses excluded. */
    public long activeMillis;
    public int placedBlocks;
    public int totalBlocks;
    public int doneBlocks;

    public AgentSession() {
    }

    public AgentSession(int id, PlacementRef placement) {
        this.id = id;
        this.placement = placement;
        this.createdAt = System.currentTimeMillis();
    }

    public String name() {
        return this.placement == null ? "?" : this.placement.name();
    }

    public void setPause(String reason, List<String> args) {
        this.state = SessionState.PAUSED;
        this.pauseReason = reason == null ? "" : reason;
        this.pauseArgs = args == null ? new ArrayList<>() : new ArrayList<>(args);
    }
}

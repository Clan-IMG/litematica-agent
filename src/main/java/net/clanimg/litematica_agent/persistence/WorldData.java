package net.clanimg.litematica_agent.persistence;

import net.clanimg.litematica_agent.agent.AgentSession;
import net.clanimg.litematica_agent.agent.BuildStrategy;
import net.clanimg.litematica_agent.storage.ContainerRecord;
import net.clanimg.litematica_agent.storage.StorageHomes;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the agent remembers about one world or server.
 */
public final class WorldData {
    public int nextSessionId = 1;
    public List<AgentSession> sessions = new ArrayList<>();
    public List<ContainerRecord> storage = new ArrayList<>();
    /** Server command (without slash) that brings the player to the storage, e.g. {@code home lager}. */
    public String storageHomeCommand = "";
    public List<StorageHomes.Route> storageHomes = new ArrayList<>();
    /** Server command (without slash) that brings the player back to the build site. */
    public String buildHomeCommand = "";

    public void sanitize() {
        if (this.sessions == null) {
            this.sessions = new ArrayList<>();
        }
        if (this.storage == null) {
            this.storage = new ArrayList<>();
        }
        if (this.storageHomeCommand == null) {
            this.storageHomeCommand = "";
        }
        if (this.storageHomes == null) {
            this.storageHomes = new ArrayList<>();
        }
        this.storageHomes.removeIf(route -> route == null || route.dimension == null);
        for (StorageHomes.Route route : this.storageHomes) {
            if (route.command == null) {
                route.command = "";
            }
        }
        if (this.buildHomeCommand == null) {
            this.buildHomeCommand = "";
        }
        this.sessions.removeIf(session -> session == null || session.placement == null);
        for (AgentSession session : this.sessions) {
            if (session.pauseArgs == null) {
                session.pauseArgs = new ArrayList<>();
            }
            if (session.pauseReason == null) {
                session.pauseReason = "";
            }
            if (session.strategy == null) {
                session.strategy = BuildStrategy.LAYERS;
            }
            if (session.blockQueue == null) {
                session.blockQueue = new ArrayList<>();
            }
            if (session.helperBlocks == null) {
                session.helperBlocks = new ArrayList<>();
            }
            this.nextSessionId = Math.max(this.nextSessionId, session.id + 1);
        }
    }
}

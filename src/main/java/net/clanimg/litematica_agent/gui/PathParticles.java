package net.clanimg.litematica_agent.gui;

import net.clanimg.litematica_agent.agent.BuildAgent;
import net.clanimg.litematica_agent.movement.pathing.PathNode;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleTypes;

import java.util.List;

/**
 * Optional in-world trail of glow particles along the next stretch of the agent's current path, so the player can
 * see where it is walking to without a corner overlay. Toggled by {@code AgentConfig#showPathParticles}.
 */
public final class PathParticles {
    private static final int PREVIEW_NODES = 14;
    private static final int SPAWN_INTERVAL_TICKS = 5;
    private static final double HOVER_HEIGHT = 0.2;

    private static int ticksUntilSpawn;

    private PathParticles() {
    }

    public static void tick(BuildAgent agent) {
        if (!agent.config().showPathParticles) {
            return;
        }
        if (--ticksUntilSpawn > 0) {
            return;
        }
        ticksUntilSpawn = SPAWN_INTERVAL_TICKS;
        List<PathNode> nodes = agent.movement().upcomingPath(PREVIEW_NODES);
        if (nodes.isEmpty()) {
            return;
        }
        ClientWorld world = agent.world();
        for (PathNode node : nodes) {
            world.addParticleClient(ParticleTypes.GLOW, node.x() + 0.5, node.y() + HOVER_HEIGHT, node.z() + 0.5, 0.0, 0.0, 0.0);
        }
    }
}

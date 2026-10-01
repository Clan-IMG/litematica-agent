package net.clanimg.litematica_agent.movement.pathing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A flying agent building the lowest layer of a plot in a pit (bedrock, one layer of air, the layer being built):
 * hovering inside that layer, walled in by its own blocks, it must fly up and over.
 */
class FlyingPitTest {
    private static final int BEDROCK = -64;

    @Test
    void fliesUpAndOverWhenWalledInAtLayerHeight() {
        TestWorld world = new TestWorld(BEDROCK);
        int y = -62;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    world.solid(18 + dx, y, 30 + dz);
                }
            }
        }
        PathResult result = new AStarPathfinder(world).find(18, y, 30, Goal.block(21, y, 30), PathOptions.flying());

        assertTrue(result.reachedGoal(), () -> "path: " + result.nodes());
    }

    @Test
    void fliesAcrossThePitWithoutSupport() {
        TestWorld world = new TestWorld(BEDROCK);
        PathResult result = new AStarPathfinder(world).find(18, -62, 30, Goal.block(30, -62, 21), PathOptions.flying());

        assertTrue(result.reachedGoal(), () -> "path: " + result.nodes());
    }

    @Test
    void doesNotWalkThroughARoofedPond() {
        // A pond three deep with a slab of stone right over its surface, a rim of ground around it.
        TestWorld world = new TestWorld(-60);
        for (int x = 7; x <= 15; x++) {
            for (int z = 7; z <= 15; z++) {
                for (int y = -59; y <= -57; y++) {
                    world.set(x, y, z, NavWorld.PASSABLE | NavWorld.WATER);
                }
                world.solid(x, -56, z);
            }
        }
        PathResult result = new AStarPathfinder(world).find(5, -59, 11, Goal.block(17, -59, 11), PathOptions.walking());

        assertTrue(result.reachedGoal(), () -> "path: " + result.nodes());
        for (PathNode node : result.nodes()) {
            assertTrue(node.x() < 7 || node.x() > 15 || node.z() < 7 || node.z() > 15, "must go around the pond: " + node);
        }
    }

    @Test
    void reachesAColumnGoalFarAway() {
        TestWorld world = new TestWorld(BEDROCK);
        PathResult result = new AStarPathfinder(world).find(18, -62, 30, Goal.column(60.5, 30.5, 12.0), PathOptions.flying());

        assertTrue(result.reachedGoal(), () -> "path: " + result.nodes());
    }
}

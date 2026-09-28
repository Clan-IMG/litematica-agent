package net.clanimg.litematica_agent.movement.pathing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AStarPathfinderTest {
    private static final int FLOOR = 63;
    private static final int FEET = 64;

    @Test
    void walksStraightOnFlatGround() {
        TestWorld world = new TestWorld(FLOOR);
        PathResult result = new AStarPathfinder(world).find(0, FEET, 0, Goal.block(10, FEET, 0), PathOptions.walking());

        assertTrue(result.reachedGoal());
        assertEquals(11, result.nodes().size());
        assertEquals(new PathNode(10, FEET, 0, MoveType.WALK), result.last());
    }

    @Test
    void walksAroundWall() {
        TestWorld world = new TestWorld(FLOOR).wall(5, -3, 5, 3, FEET, FEET + 3);
        PathResult result = new AStarPathfinder(world).find(0, FEET, 0, Goal.block(10, FEET, 0), PathOptions.walking());

        assertTrue(result.reachedGoal());
        for (PathNode node : result.nodes()) {
            assertFalse(node.x() == 5 && Math.abs(node.z()) <= 3, "path must not cross the wall: " + node);
        }
    }

    @Test
    void jumpsOntoOneBlockStep() {
        TestWorld world = new TestWorld(FLOOR).wall(3, -20, 20, 20, FEET, FEET);
        PathResult result = new AStarPathfinder(world).find(0, FEET, 0, Goal.block(6, FEET + 1, 0), PathOptions.walking());

        assertTrue(result.reachedGoal());
        assertTrue(result.nodes().stream().anyMatch(node -> node.move() == MoveType.ASCEND));
    }

    @Test
    void doesNotJumpOntoSlabLyingOnABlock() {
        // A platform of blocks covered with bottom slabs: standing on it is 1.5 blocks up, more than a jump.
        TestWorld world = new TestWorld(FLOOR).wall(3, -20, 20, 20, FEET, FEET);
        for (int x = 3; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                world.set(x, FEET + 1, z, NavWorld.PASSABLE | NavWorld.SOLID_TOP | NavWorld.RAISED);
            }
        }
        PathResult result = new AStarPathfinder(world).find(0, FEET, 0, Goal.block(3, FEET + 1, 0),
                PathOptions.walking().withMaxNodes(5_000));

        assertFalse(result.reachedGoal(), () -> "path: " + result.nodes());
    }

    @Test
    void stepsOntoSlabFromTheSameLevelAndJumpsOnFromThere() {
        // A slab on the ground is a small step; from there the slab on the block is only one block higher.
        TestWorld world = new TestWorld(FLOOR)
                .set(2, FEET, 0, NavWorld.PASSABLE | NavWorld.SOLID_TOP | NavWorld.RAISED)
                .solid(3, FEET, 0)
                .set(3, FEET + 1, 0, NavWorld.PASSABLE | NavWorld.SOLID_TOP | NavWorld.RAISED);
        PathResult result = new AStarPathfinder(world).find(0, FEET, 0, Goal.block(3, FEET + 1, 0), PathOptions.walking());

        assertTrue(result.reachedGoal());
        assertTrue(result.nodes().contains(new PathNode(2, FEET, 0, MoveType.WALK)));
    }

    @Test
    void cannotClimbTwoBlockWallWithoutHelpers() {
        TestWorld world = new TestWorld(FLOOR).wall(3, -20, 20, 20, FEET, FEET + 1);
        PathResult result = new AStarPathfinder(world).find(0, FEET, 0, Goal.block(6, FEET + 2, 0),
                PathOptions.walking().withMaxNodes(5_000));

        assertFalse(result.reachedGoal());
    }

    @Test
    void pillarsUpWithHelperBlocks() {
        TestWorld world = new TestWorld(FLOOR).wall(3, -20, 20, 20, FEET, FEET + 1);
        PathResult result = new AStarPathfinder(world).find(0, FEET, 0, Goal.block(6, FEET + 2, 0),
                PathOptions.walking().withHelperBlocks(4));

        assertTrue(result.reachedGoal());
        assertTrue(result.helperBlocksUsed() >= 1);
        assertTrue(result.helperBlocksUsed() <= 4);
    }

    @Test
    void neverPillarsIntoOccupiedBlocks() {
        TestWorld world = shaft().set(0, FEET, 0, NavWorld.PASSABLE | NavWorld.OCCUPIED);
        PathResult result = new AStarPathfinder(world).find(0, FEET, 0, Goal.block(0, FEET + 2, 0),
                PathOptions.walking().withHelperBlocks(4).withMaxNodes(5_000));

        assertFalse(result.reachedGoal());
    }

    @Test
    void avoidsSpotsWherePillaringFailed() {
        TestWorld world = shaft();
        PathOptions options = PathOptions.walking().withHelperBlocks(4).withMaxNodes(5_000);
        assertTrue(new AStarPathfinder(world).find(0, FEET, 0, Goal.block(0, FEET + 2, 0), options).reachedGoal());

        long failed = PosUtil.pack(0, FEET, 0);
        PathResult result = new AStarPathfinder(world).find(0, FEET, 0, Goal.block(0, FEET + 2, 0),
                options.withNoPillar(pos -> pos == failed));
        assertFalse(result.reachedGoal());
    }

    /** A one-block shaft around the start position, so pillaring right there is the only way up. */
    private static TestWorld shaft() {
        return new TestWorld(FLOOR)
                .wall(-1, -1, 1, -1, FEET, FEET + 5)
                .wall(-1, 1, 1, 1, FEET, FEET + 5)
                .wall(-1, 0, -1, 0, FEET, FEET + 5)
                .wall(1, 0, 1, 0, FEET, FEET + 5);
    }

    @Test
    void dropsDownAtMostThreeBlocks() {
        TestWorld world = new TestWorld(FLOOR).hole(3, -20, 20, 20);
        for (int x = 3; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                world.solid(x, FLOOR - 3, z);
            }
        }
        PathResult result = new AStarPathfinder(world).find(0, FEET, 0, Goal.block(6, FEET - 3, 0), PathOptions.walking());
        assertTrue(result.reachedGoal());
        assertTrue(result.nodes().stream().anyMatch(node -> node.move() == MoveType.DESCEND));
    }

    @Test
    void refusesDangerousDrop() {
        TestWorld world = new TestWorld(FLOOR).hole(3, -20, 20, 20);
        for (int x = 3; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                world.solid(x, FLOOR - 10, z);
            }
        }
        PathResult result = new AStarPathfinder(world).find(0, FEET, 0, Goal.block(6, FEET - 10, 0),
                PathOptions.walking().withMaxNodes(5_000));
        assertFalse(result.reachedGoal());
    }

    @Test
    void avoidsDangerousBlocks() {
        TestWorld world = new TestWorld(FLOOR);
        for (int z = -1; z <= 1; z++) {
            world.set(5, FLOOR, z, NavWorld.SOLID_TOP | NavWorld.DANGER);
        }
        PathResult result = new AStarPathfinder(world).find(0, FEET, 0, Goal.block(10, FEET, 0), PathOptions.walking());
        assertTrue(result.reachedGoal());
        for (PathNode node : result.nodes()) {
            assertFalse(node.x() == 5 && Math.abs(node.z()) <= 1, "path must avoid magma: " + node);
        }
    }

    @Test
    void fliesOverHighWall() {
        TestWorld world = new TestWorld(FLOOR).wall(5, -30, 5, 30, FEET, FEET + 6);
        PathResult result = new AStarPathfinder(world).find(0, FEET, 0, Goal.block(10, FEET, 0), PathOptions.flying());

        assertTrue(result.reachedGoal());
        assertTrue(result.nodes().stream().anyMatch(node -> node.move().isFlying()));
        assertTrue(result.nodes().stream().anyMatch(node -> node.y() >= FEET + 7));
    }

    @Test
    void walksThroughNoFlyZone() {
        TestWorld world = new TestWorld(FLOOR);
        for (int z = -30; z <= 30; z++) {
            world.solid(8, FEET, z);
        }
        PathResult result = new AStarPathfinder(world).find(0, FEET + 5, 0, Goal.block(16, FEET + 5, 0),
                PathOptions.flying().withNoFly(column -> {
                    int x = (int) (column >> 32);
                    return x >= 6 && x <= 10;
                }));

        assertTrue(result.reachedGoal());
        List<PathNode> nodes = result.nodes();
        for (PathNode node : nodes) {
            if (node.x() >= 6 && node.x() <= 10) {
                int expectedFeet = node.x() == 8 ? FEET + 1 : FEET;
                assertEquals(expectedFeet, node.y(), "must stay on the ground inside the no-fly zone: " + node);
            }
        }
    }

    @Test
    void returnsPartialPathWhenGoalUnreachable() {
        TestWorld world = new TestWorld(FLOOR).wall(-10, 10, 10, 10, FEET, FEET + 5)
                .wall(10, -10, 10, 10, FEET, FEET + 5)
                .wall(-10, -10, 10, -10, FEET, FEET + 5)
                .wall(-10, -10, -10, 10, FEET, FEET + 5);
        PathResult result = new AStarPathfinder(world).find(0, FEET, 0, Goal.block(30, FEET, 0),
                PathOptions.walking().withMaxNodes(10_000));

        assertFalse(result.reachedGoal());
        assertEquals(9, result.last().x());
    }

    @Test
    void goalNearAcceptsAnyPositionInRadius() {
        TestWorld world = new TestWorld(FLOOR);
        PathResult result = new AStarPathfinder(world).find(0, FEET, 0, Goal.near(20.5, FEET, 0.5, 4.0), PathOptions.walking());

        assertTrue(result.reachedGoal());
        PathNode last = result.last();
        assertTrue(Math.abs(last.x() - 20) <= 4);
    }

    @Test
    void digsDownThroughOwnHelperPillar() {
        TestWorld world = new TestWorld(FLOOR);
        for (int y = FEET; y < FEET + 5; y++) {
            world.set(0, y, 0, NavWorld.SOLID_TOP | NavWorld.HELPER);
        }
        PathResult result = new AStarPathfinder(world).find(0, FEET + 5, 0, Goal.block(3, FEET, 0), PathOptions.walking());
        assertTrue(result.reachedGoal());
        assertTrue(result.nodes().stream().anyMatch(node -> node.move() == MoveType.DIG_DOWN));
    }

    @Test
    void neverDigsThroughForeignBlocks() {
        TestWorld world = new TestWorld(FLOOR);
        for (int y = FEET; y < FEET + 5; y++) {
            world.solid(0, y, 0);
        }
        PathResult result = new AStarPathfinder(world).find(0, FEET + 5, 0, Goal.block(3, FEET, 0),
                PathOptions.walking().withMaxNodes(5_000));
        assertFalse(result.reachedGoal());
    }

    @Test
    void packsCoordinatesRoundTrip() {
        int[][] samples = {{0, 0, 0}, {-1, -64, -1}, {30_000_000, 319, -30_000_000}, {12345, -12, -54321}};
        for (int[] sample : samples) {
            long packed = PosUtil.pack(sample[0], sample[1], sample[2]);
            assertEquals(sample[0], PosUtil.x(packed));
            assertEquals(sample[1], PosUtil.y(packed));
            assertEquals(sample[2], PosUtil.z(packed));
        }
    }
}

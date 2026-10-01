package net.clanimg.litematica_agent.planning;

import net.clanimg.litematica_agent.movement.pathing.PosUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildPlanTest {
    private record Block(int x, int y, int z, int category) {
        long pos() {
            return PosUtil.pack(this.x, this.y, this.z);
        }
    }

    private static BuildPlan<Block> plan(List<Block> blocks) {
        return new BuildPlan<>(blocks, Block::pos, Block::category);
    }

    @Test
    void buildsBottomUp() {
        List<Block> blocks = List.of(new Block(0, 66, 0, 0), new Block(0, 64, 0, 0), new Block(0, 65, 0, 0));
        BuildPlan<Block> plan = plan(blocks);

        assertEquals(64, plan.currentLayerY());
        List<Integer> candidates = plan.candidates(0);
        assertEquals(1, candidates.size());
        assertEquals(64, plan.get(candidates.get(0)).y());

        plan.markDone(candidates.get(0));
        assertEquals(65, plan.currentLayerY());
    }

    @Test
    void filteredCandidatesSkipLayersWithoutMatches() {
        // x = 1 stands for the block type in the queue; layer 64 only has other types.
        List<Block> blocks = List.of(new Block(0, 64, 0, 0), new Block(1, 65, 0, 0), new Block(1, 66, 0, 0));
        BuildPlan<Block> plan = plan(blocks);

        List<Integer> candidates = plan.candidates(0, index -> plan.get(index).x() == 1);
        assertEquals(1, candidates.size());
        assertEquals(65, plan.get(candidates.get(0)).y());
        assertTrue(plan.hasPending(index -> plan.get(index).y() == 66));

        plan.defer(candidates.get(0), 0, 100);
        assertEquals(66, plan.get(plan.candidates(0, index -> plan.get(index).x() == 1).get(0)).y());
    }

    @Test
    void respectsCategoryOrderInsideLayer() {
        List<Block> blocks = List.of(
                new Block(0, 64, 0, BuildCategory.POWER_SOURCE.ordinal()),
                new Block(1, 64, 0, BuildCategory.SOLID.ordinal()),
                new Block(2, 64, 0, BuildCategory.REDSTONE.ordinal()));
        BuildPlan<Block> plan = plan(blocks);

        List<Integer> first = plan.candidates(0);
        assertEquals(1, first.size());
        assertEquals(BuildCategory.SOLID.ordinal(), plan.get(first.get(0)).category());
        plan.markDone(first.get(0));

        List<Integer> second = plan.candidates(0);
        assertEquals(BuildCategory.REDSTONE.ordinal(), plan.get(second.get(0)).category());
        plan.markDone(second.get(0));

        List<Integer> third = plan.candidates(0);
        assertEquals(BuildCategory.POWER_SOURCE.ordinal(), plan.get(third.get(0)).category());
    }

    @Test
    void deferredTargetsDoNotBlockHigherLayers() {
        List<Block> blocks = List.of(new Block(0, 64, 0, 0), new Block(0, 65, 0, 0));
        BuildPlan<Block> plan = plan(blocks);

        int low = plan.candidates(0).get(0);
        plan.defer(low, 0, 100);

        List<Integer> candidates = plan.candidates(10);
        assertEquals(1, candidates.size());
        assertEquals(65, plan.get(candidates.get(0)).y());

        plan.markDone(candidates.get(0));
        assertTrue(plan.candidates(10).isEmpty());
        assertEquals(64, plan.currentLayerY());
        assertEquals(List.of(low), plan.candidates(100));
    }

    @Test
    void tracksProgressAndCompletion() {
        List<Block> blocks = new ArrayList<>();
        for (int x = 0; x < 10; x++) {
            blocks.add(new Block(x, 64, 0, 0));
        }
        BuildPlan<Block> plan = plan(blocks);
        for (int i = 0; i < 9; i++) {
            plan.markDone(i);
        }
        assertEquals(9, plan.doneCount());
        assertFalse(plan.isFinished());
        plan.markFailed(9);
        assertTrue(plan.isFinished());
        assertFalse(plan.isComplete());
        assertEquals(1, plan.failedCount());

        plan.retryFailed();
        assertFalse(plan.isFinished());
        assertEquals(0, plan.failedCount());
    }

    @Test
    void markPendingReopensLowerLayer() {
        List<Block> blocks = List.of(new Block(0, 64, 0, 0), new Block(0, 65, 0, 0));
        BuildPlan<Block> plan = plan(blocks);
        plan.markDone(0);
        assertEquals(65, plan.currentLayerY());
        plan.markPending(0);
        assertEquals(64, plan.currentLayerY());
    }

    @Test
    void serpentineOrderInsideLayer() {
        List<Block> blocks = new ArrayList<>();
        for (int z = 0; z < 2; z++) {
            for (int x = 0; x < 3; x++) {
                blocks.add(new Block(x, 64, z, 0));
            }
        }
        BuildPlan<Block> plan = plan(blocks);
        List<Integer> order = plan.upcoming(10);
        int[] xs = order.stream().mapToInt(i -> plan.get(i).x()).toArray();
        int[] zs = order.stream().mapToInt(i -> plan.get(i).z()).toArray();
        assertEquals(List.of(0, 1, 2, 2, 1, 0), java.util.Arrays.stream(xs).boxed().toList());
        assertEquals(List.of(0, 0, 0, 1, 1, 1), java.util.Arrays.stream(zs).boxed().toList());
    }

    @Test
    void layerProgressAndListenerFollowEveryChange() {
        List<Block> blocks = List.of(new Block(0, 64, 0, 0), new Block(1, 64, 0, 0), new Block(0, 65, 0, 0));
        BuildPlan<Block> plan = plan(blocks);
        List<String> changes = new ArrayList<>();
        plan.setListener((index, from, to) -> changes.add(from + ">" + to));

        int first = plan.candidates(0).get(0);
        plan.markDone(first);
        assertEquals(1, plan.layerProgress(0)[0]);
        assertEquals(2, plan.layerProgress(0)[1]);
        plan.markFailed(plan.candidates(0).get(0));
        assertEquals(65, plan.currentLayerY());
        plan.markPending(first);
        assertEquals(64, plan.currentLayerY());
        assertEquals(0, plan.layerProgress(0)[0]);
        assertEquals(List.of("PENDING>DONE", "PENDING>FAILED", "DONE>PENDING"), changes);
    }

    @Test
    void recoveryReopensOnlyRetryableFailuresAndUpdatesLowerLayer() {
        BuildPlan<Block> plan = plan(List.of(new Block(0, 64, 0, 0), new Block(1, 64, 0, 0), new Block(0, 65, 0, 0)));
        plan.defer(0, 10, 100);
        plan.markFailed(0);
        plan.markFailed(1);
        plan.markDone(2);
        assertEquals(Integer.MIN_VALUE, plan.currentLayerY());
        assertEquals(0, plan.layerProgress(0)[0]);

        assertEquals(1, plan.retryFailed(index -> index == 0));
        assertEquals(64, plan.currentLayerY());
        assertEquals(List.of(0), plan.candidates(0));
        assertEquals(0, plan.attempts(0));
        assertEquals(1, plan.failedCount());
        assertFalse(plan.isComplete());

        plan.markDone(0);
        plan.markDone(1);
        assertEquals(2, plan.layerProgress(0)[0]);
        assertTrue(plan.isComplete());
        assertTrue(plan.isFinished());
    }

    @Test
    void topDownStartsAtTheHighestLayer() {
        BuildPlan<Block> plan = plan(List.of(new Block(0, 64, 0, 0), new Block(0, 65, 0, 0), new Block(0, 66, 0, 0)));
        List<Integer> top = plan.candidatesTopDown(0, index -> true, index -> true, layer -> true);
        assertEquals(List.of(2), top);
        plan.markDone(2);
        assertEquals(65, plan.get(plan.candidatesTopDown(0, index -> true, index -> true, layer -> true).get(0)).y());
    }

    @Test
    void layerFilterSkipsWholeLayers() {
        BuildPlan<Block> plan = plan(List.of(new Block(0, 64, 0, 0), new Block(0, 65, 0, 0), new Block(0, 66, 0, 0)));
        List<Integer> candidates = plan.candidates(0, index -> true, index -> true, layer -> layer == 2);
        assertEquals(66, plan.get(candidates.get(0)).y());
    }

    @Test
    void targetsWaitingForSupportHoldBackTheirLayerButNotTheNext() {
        // Layer 64: a wall block that cannot be placed yet (not ready) and water that must not come before it.
        List<Block> blocks = List.of(
                new Block(0, 64, 0, BuildCategory.SOLID.ordinal()),
                new Block(1, 64, 0, BuildCategory.FLUID.ordinal()),
                new Block(0, 65, 0, BuildCategory.SOLID.ordinal()));
        BuildPlan<Block> plan = plan(blocks);
        int wall = plan.indexOf(PosUtil.pack(0, 64, 0));
        IntPredicate ready = index -> index != wall;

        List<Integer> candidates = plan.candidates(0, index -> true, ready, layer -> true);
        assertEquals(1, candidates.size());
        assertEquals(65, plan.get(candidates.get(0)).y(), "the next layer, not the water beside the waiting wall");

        plan.markDone(wall);
        assertEquals(BuildCategory.FLUID.ordinal(), plan.get(plan.candidates(0, index -> true, ready, layer -> true).get(0)).category());
    }

    @Test
    void candidatesNearStayAroundTheGivenChunk() {
        List<Block> blocks = new ArrayList<>();
        blocks.add(new Block(1, 64, 1, 0));       // chunk 0,0
        blocks.add(new Block(17, 64, 1, 0));      // chunk 1,0 (a neighbour)
        blocks.add(new Block(200, 64, 200, 0));   // far away
        blocks.add(new Block(1, 70, 1, BuildCategory.FLUID.ordinal()));
        blocks.add(new Block(2, 70, 1, 0));
        BuildPlan<Block> plan = plan(blocks);

        List<Integer> near = plan.candidatesNear(0, index -> true, 0, 0, 32);
        List<Integer> xs = near.stream().map(index -> plan.get(index).x()).sorted().toList();
        assertEquals(List.of(1, 2, 17), xs, "the far target is left out, the water waits for the block of its layer");

        List<Integer> far = plan.candidatesNear(0, index -> plan.get(index).x() == 200, 0, 0, 32);
        assertEquals(1, far.size());
        assertEquals(200, plan.get(far.get(0)).x());
        assertTrue(plan.candidatesNear(0, index -> plan.get(index).x() == 200, 0, 0, 4).isEmpty(), "beyond the rings");

        plan.markDone(plan.indexOf(PosUtil.pack(2, 70, 1)));
        assertTrue(plan.candidatesNear(0, index -> true, 0, 0, 32).stream()
                .anyMatch(index -> plan.get(index).category() == BuildCategory.FLUID.ordinal()));
    }

    @Test
    void candidatesNearInLayerStayInTheLowestOpenLayerAroundTheChunk() {
        List<Block> blocks = new ArrayList<>();
        blocks.add(new Block(1, 64, 1, 0));                                // layer 64, chunk 0,0
        blocks.add(new Block(2, 64, 1, BuildCategory.FLUID.ordinal()));   // layer 64, later category
        blocks.add(new Block(17, 64, 1, 0));                               // layer 64, chunk 1,0
        blocks.add(new Block(300, 64, 300, 0));                            // layer 64, far away
        blocks.add(new Block(1, 65, 1, 0));                                // layer 65, chunk 0,0
        BuildPlan<Block> plan = plan(blocks);

        assertEquals(0, plan.firstOpenLayer(layer -> true));
        assertEquals(1, plan.firstOpenLayer(layer -> layer != 0));

        List<Integer> near = plan.candidatesNearInLayer(0, index -> true, 0, 0, 0, 32);
        assertEquals(List.of(1, 17), near.stream().map(index -> plan.get(index).x()).sorted().toList(),
                "the far target and the next layer are left out, the water waits for the solid block");

        plan.markDone(plan.indexOf(PosUtil.pack(1, 64, 1)));
        plan.markDone(plan.indexOf(PosUtil.pack(17, 64, 1)));
        assertTrue(plan.candidatesNearInLayer(0, index -> true, 0, 0, 0, 4).isEmpty(), "only the far solid block is left open");
        assertEquals(300, plan.get(plan.candidatesNearInLayer(0, index -> true, 0, 0, 0, 32).get(0)).x());

        plan.markDone(plan.indexOf(PosUtil.pack(300, 64, 300)));
        List<Integer> water = plan.candidatesNearInLayer(0, index -> true, 0, 0, 0, 32);
        assertEquals(1, water.size());
        assertEquals(BuildCategory.FLUID.ordinal(), plan.get(water.get(0)).category());
    }

    @Test
    void nearestKeepsTheClosestTargets() {
        List<Block> blocks = new ArrayList<>();
        for (int x = 0; x < 50; x++) {
            blocks.add(new Block(x, 64, 0, 0));
        }
        BuildPlan<Block> plan = plan(blocks);
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) {
            all.add(i);
        }
        List<Integer> closest = plan.nearest(all, 10, 64, 0, 5);
        assertEquals(List.of(8, 9, 10, 11, 12), closest.stream().map(index -> plan.get(index).x()).sorted().toList());
    }

    @Test
    void kthSmallestSelects() {
        long[] values = {9, 1, 8, 2, 7, 3, 6, 4, 5, 0, 5};
        assertEquals(0, BuildPlan.kthSmallest(values.clone(), 0));
        assertEquals(5, BuildPlan.kthSmallest(values.clone(), 5));
        assertEquals(5, BuildPlan.kthSmallest(values.clone(), 6));
        assertEquals(9, BuildPlan.kthSmallest(values.clone(), 10));
    }

    @Test
    void indexLookupByPosition() {
        List<Block> blocks = List.of(new Block(5, 70, -3, 0), new Block(1, 64, 2, 0));
        BuildPlan<Block> plan = plan(blocks);
        int index = plan.indexOf(PosUtil.pack(5, 70, -3));
        assertEquals(5, plan.get(index).x());
        assertEquals(-1, plan.indexOf(PosUtil.pack(9, 9, 9)));
    }
}

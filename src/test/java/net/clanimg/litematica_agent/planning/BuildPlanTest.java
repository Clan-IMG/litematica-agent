package net.clanimg.litematica_agent.planning;

import net.clanimg.litematica_agent.movement.pathing.PosUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
    void indexLookupByPosition() {
        List<Block> blocks = List.of(new Block(5, 70, -3, 0), new Block(1, 64, 2, 0));
        BuildPlan<Block> plan = plan(blocks);
        int index = plan.indexOf(PosUtil.pack(5, 70, -3));
        assertEquals(5, plan.get(index).x());
        assertEquals(-1, plan.indexOf(PosUtil.pack(9, 9, 9)));
    }
}

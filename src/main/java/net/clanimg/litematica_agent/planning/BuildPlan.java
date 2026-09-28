package net.clanimg.litematica_agent.planning;

import net.clanimg.litematica_agent.movement.pathing.PosUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/**
 * The build queue: targets ordered bottom-up by layer, inside a layer by category and in serpentine rows.
 * Targets that cannot be placed yet are deferred and retried later instead of blocking the layer.
 */
public final class BuildPlan<T> {
    public enum Status {
        PENDING,
        DONE,
        FAILED
    }

    private final List<T> items;
    private final long[] positions;
    private final int[] categories;
    private final Status[] status;
    private final int[] attempts;
    private final long[] retryAt;
    private final Map<Long, Integer> indexByPos = new HashMap<>();
    private final int[] layerStarts;
    private final int[] layerYs;
    private int lowestOpenLayer;
    private int done;
    private int failed;

    public BuildPlan(List<T> input, ToLongFunction<T> posOf, ToIntFunction<T> categoryOf) {
        List<T> sorted = new ArrayList<>(input);
        sorted.sort(Comparator
                .comparingInt((T item) -> PosUtil.y(posOf.applyAsLong(item)))
                .thenComparingInt(categoryOf)
                .thenComparingInt(item -> PosUtil.z(posOf.applyAsLong(item)))
                .thenComparingInt(item -> {
                    long pos = posOf.applyAsLong(item);
                    return (PosUtil.z(pos) & 1) == 0 ? PosUtil.x(pos) : -PosUtil.x(pos);
                }));

        int size = sorted.size();
        this.items = sorted;
        this.positions = new long[size];
        this.categories = new int[size];
        this.status = new Status[size];
        this.attempts = new int[size];
        this.retryAt = new long[size];

        List<Integer> starts = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            T item = sorted.get(i);
            this.positions[i] = posOf.applyAsLong(item);
            this.categories[i] = categoryOf.applyAsInt(item);
            this.status[i] = Status.PENDING;
            this.indexByPos.put(this.positions[i], i);
            int y = PosUtil.y(this.positions[i]);
            if (ys.isEmpty() || ys.get(ys.size() - 1) != y) {
                ys.add(y);
                starts.add(i);
            }
        }
        starts.add(size);
        this.layerStarts = starts.stream().mapToInt(Integer::intValue).toArray();
        this.layerYs = ys.stream().mapToInt(Integer::intValue).toArray();
    }

    public int size() {
        return this.items.size();
    }

    public T get(int index) {
        return this.items.get(index);
    }

    public long position(int index) {
        return this.positions[index];
    }

    public Status status(int index) {
        return this.status[index];
    }

    public int attempts(int index) {
        return this.attempts[index];
    }

    public int indexOf(long pos) {
        Integer index = this.indexByPos.get(pos);
        return index == null ? -1 : index;
    }

    public int doneCount() {
        return this.done;
    }

    public int failedCount() {
        return this.failed;
    }

    public int pendingCount() {
        return this.size() - this.done - this.failed;
    }

    public boolean isFinished() {
        return this.pendingCount() == 0;
    }

    public void markDone(int index) {
        this.setStatus(index, Status.DONE);
    }

    public void markFailed(int index) {
        this.setStatus(index, Status.FAILED);
    }

    public void markPending(int index) {
        this.setStatus(index, Status.PENDING);
        this.retryAt[index] = 0L;
        int layer = this.layerOf(index);
        if (layer < this.lowestOpenLayer) {
            this.lowestOpenLayer = layer;
        }
    }

    /** Resets failed targets so they get another chance, e.g. after the player fixed something and resumed. */
    public void retryFailed() {
        for (int i = 0; i < this.size(); i++) {
            if (this.status[i] == Status.FAILED) {
                this.attempts[i] = 0;
                this.markPending(i);
            }
        }
    }

    public void defer(int index, long now, long delay) {
        this.attempts[index]++;
        this.retryAt[index] = now + delay;
    }

    public void resetAttempts(int index) {
        this.attempts[index] = 0;
        this.retryAt[index] = 0L;
    }

    /**
     * Y level of the lowest layer that still has pending targets, or {@link Integer#MIN_VALUE} if finished.
     */
    public int currentLayerY() {
        this.advanceLowestLayer();
        return this.lowestOpenLayer < this.layerYs.length ? this.layerYs[this.lowestOpenLayer] : Integer.MIN_VALUE;
    }

    public int layerCount() {
        return this.layerYs.length;
    }

    public int layerIndexOfY(int y) {
        for (int i = 0; i < this.layerYs.length; i++) {
            if (this.layerYs[i] == y) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Progress of the layer at {@code layerIndex} as {done, total}.
     */
    public int[] layerProgress(int layerIndex) {
        if (layerIndex < 0 || layerIndex >= this.layerYs.length) {
            return new int[]{0, 0};
        }
        int doneInLayer = 0;
        int start = this.layerStarts[layerIndex];
        int end = this.layerStarts[layerIndex + 1];
        for (int i = start; i < end; i++) {
            if (this.status[i] != Status.PENDING) {
                doneInLayer++;
            }
        }
        return new int[]{doneInLayer, end - start};
    }

    /**
     * Targets to work on now: pending targets of the lowest layer that has anything ready, restricted to the
     * lowest category present in that layer. Deferred targets are skipped until their retry time.
     */
    public List<Integer> candidates(long now) {
        this.advanceLowestLayer();
        List<Integer> result = new ArrayList<>();
        for (int layer = this.lowestOpenLayer; layer < this.layerYs.length; layer++) {
            int start = this.layerStarts[layer];
            int end = this.layerStarts[layer + 1];
            int minCategory = Integer.MAX_VALUE;
            for (int i = start; i < end; i++) {
                if (this.status[i] == Status.PENDING && this.retryAt[i] <= now) {
                    minCategory = Math.min(minCategory, this.categories[i]);
                }
            }
            if (minCategory == Integer.MAX_VALUE) {
                continue;
            }
            for (int i = start; i < end; i++) {
                if (this.status[i] == Status.PENDING && this.retryAt[i] <= now && this.categories[i] == minCategory) {
                    result.add(i);
                }
            }
            return result;
        }
        return result;
    }

    /**
     * Pending targets in build order starting at the lowest open layer.
     */
    public List<Integer> upcoming(int limit) {
        this.advanceLowestLayer();
        List<Integer> result = new ArrayList<>();
        int start = this.lowestOpenLayer < this.layerStarts.length ? this.layerStarts[this.lowestOpenLayer] : this.size();
        for (int i = start; i < this.size() && result.size() < limit; i++) {
            if (this.status[i] == Status.PENDING) {
                result.add(i);
            }
        }
        return result;
    }

    /**
     * Earliest time a deferred target becomes ready again, or {@link Long#MAX_VALUE}.
     */
    public long nextRetryTime() {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < this.size(); i++) {
            if (this.status[i] == Status.PENDING) {
                best = Math.min(best, this.retryAt[i]);
            }
        }
        return best;
    }

    private void setStatus(int index, Status newStatus) {
        Status old = this.status[index];
        if (old == newStatus) {
            return;
        }
        if (old == Status.DONE) {
            this.done--;
        } else if (old == Status.FAILED) {
            this.failed--;
        }
        if (newStatus == Status.DONE) {
            this.done++;
        } else if (newStatus == Status.FAILED) {
            this.failed++;
        }
        this.status[index] = newStatus;
    }

    private int layerOf(int index) {
        int low = 0;
        int high = this.layerYs.length - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (this.layerStarts[mid] <= index) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    private void advanceLowestLayer() {
        while (this.lowestOpenLayer < this.layerYs.length) {
            int start = this.layerStarts[this.lowestOpenLayer];
            int end = this.layerStarts[this.lowestOpenLayer + 1];
            for (int i = start; i < end; i++) {
                if (this.status[i] == Status.PENDING) {
                    return;
                }
            }
            this.lowestOpenLayer++;
        }
    }
}

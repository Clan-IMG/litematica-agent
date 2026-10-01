package net.clanimg.litematica_agent.planning;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.clanimg.litematica_agent.movement.pathing.PosUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntPredicate;
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

    private static final Status[] STATUSES = Status.values();
    private static final byte PENDING = (byte) Status.PENDING.ordinal();
    private static final byte DONE = (byte) Status.DONE.ordinal();
    private static final byte FAILED = (byte) Status.FAILED.ordinal();

    /** Told about every status change, e.g. to keep material counts up to date without recounting everything. */
    public interface StatusListener {
        void changed(int index, Status from, Status to);
    }

    private final List<T> items;
    private final long[] positions;
    /** Per target, as small as it gets: a real schematic has tens of millions of targets. */
    private final byte[] categories;
    private final byte[] status;
    private final byte[] attempts;
    /** Agent tick from which a deferred target may be looked at again (ticks fit an int for years). */
    private final int[] retryAt;
    private final Long2IntOpenHashMap indexByPos;
    private final int[] layerStarts;
    private final int[] layerYs;
    /** Pending targets per layer, so progress and the lowest open layer need no counting. */
    private final int[] layerPending;
    private final int[] layerDone;
    /** Highest category + 1; the row width of {@link #layerCategoryPending}. */
    private final int categoryCount;
    /** Pending targets per layer and category, so the open category of a layer is known without counting. */
    private final int[] layerCategoryPending;
    /**
     * Target indices per chunk column in plan order (so bottom-up), for {@link #candidatesNear}. Built the first time
     * it is needed: only building around the player uses it, and for a huge schematic it is not small.
     */
    private @Nullable Long2ObjectOpenHashMap<int[]> chunkIndex;
    private @Nullable Long2IntOpenHashMap chunkPending;
    private int lowestOpenLayer;
    private int done;
    private int failed;
    private StatusListener listener = (index, from, to) -> {
    };

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
        this.categories = new byte[size];
        this.status = new byte[size];
        this.attempts = new byte[size];
        this.retryAt = new int[size];
        // Densely filled: at the default fill a map of this size rounds up to twice the slots it needs.
        this.indexByPos = new Long2IntOpenHashMap(size, 0.9f);
        this.indexByPos.defaultReturnValue(-1);

        List<Integer> starts = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            T item = sorted.get(i);
            this.positions[i] = posOf.applyAsLong(item);
            int category = categoryOf.applyAsInt(item);
            if (category < 0 || category > Byte.MAX_VALUE) {
                throw new IllegalArgumentException("Category out of range: " + category);
            }
            this.categories[i] = (byte) category;
            this.status[i] = PENDING;
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
        this.layerPending = new int[this.layerYs.length];
        this.layerDone = new int[this.layerYs.length];
        int highestCategory = 0;
        for (int i = 0; i < size; i++) {
            highestCategory = Math.max(highestCategory, this.categories[i]);
        }
        this.categoryCount = highestCategory + 1;
        this.layerCategoryPending = new int[this.layerYs.length * this.categoryCount];
        for (int layer = 0; layer < this.layerYs.length; layer++) {
            this.layerPending[layer] = this.layerStarts[layer + 1] - this.layerStarts[layer];
            for (int i = this.layerStarts[layer]; i < this.layerStarts[layer + 1]; i++) {
                this.layerCategoryPending[layer * this.categoryCount + this.categories[i]]++;
            }
        }
    }

    public void setListener(StatusListener listener) {
        this.listener = listener;
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
        return STATUSES[this.status[index]];
    }

    public int attempts(int index) {
        return this.attempts[index];
    }

    public int indexOf(long pos) {
        return this.indexByPos.get(pos);
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

    /** All targets verified, as distinct from an exhausted queue that still contains failures. */
    public boolean isComplete() {
        return this.done == this.size();
    }

    public void markDone(int index) {
        this.setStatus(index, Status.DONE);
    }

    public void markFailed(int index) {
        this.setStatus(index, Status.FAILED);
    }

    public void markPending(int index) {
        this.setStatus(index, Status.PENDING);
        this.retryAt[index] = 0;
        int layer = this.layerOf(index);
        if (layer < this.lowestOpenLayer) {
            this.lowestOpenLayer = layer;
        }
    }

    /** Resets failed targets so they get another chance, e.g. after the player fixed something and resumed. */
    public void retryFailed() {
        this.retryFailed(index -> true);
    }

    /** Reopens only retryable failures; explicit skips and missing prerequisites can remain blocked. */
    public int retryFailed(IntPredicate retryable) {
        int retried = 0;
        for (int i = 0; i < this.size(); i++) {
            if (this.status[i] == FAILED && retryable.test(i)) {
                this.attempts[i] = 0;
                this.markPending(i);
                retried++;
            }
        }
        return retried;
    }

    public void defer(int index, long now, long delay) {
        if (this.attempts[index] < Byte.MAX_VALUE) {
            this.attempts[index]++;
        }
        this.retryAt[index] = (int) Math.min(Integer.MAX_VALUE, now + delay);
    }

    public void resetAttempts(int index) {
        this.attempts[index] = 0;
        this.retryAt[index] = 0;
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
        int total = this.layerStarts[layerIndex + 1] - this.layerStarts[layerIndex];
        return new int[]{this.layerDone[layerIndex], total};
    }

    /**
     * Targets to work on now: pending targets of the lowest layer that has anything ready, restricted to the
     * lowest category present in that layer. Deferred targets are skipped until their retry time.
     */
    public List<Integer> candidates(long now) {
        return this.candidates(now, index -> true);
    }

    /**
     * Like {@link #candidates(long)}, but only among the targets accepted by {@code filter}.
     */
    public List<Integer> candidates(long now, IntPredicate filter) {
        return this.candidates(now, filter, filter, layer -> true);
    }

    /**
     * Like {@link #candidates(long, IntPredicate)}, with the two roles of the filter apart:
     *
     * @param order       targets that hold back the later categories of their layer (water after the walls around it),
     *                    even while they cannot be worked on themselves
     * @param ready       targets that may be worked on now; a subset of {@code order}
     * @param layerFilter layers it rejects are skipped without looking at their targets. With a huge schematic and a
     *                    few selected block types this keeps a decision fast: a layer without any of them costs nothing
     *                    instead of a pass over all its targets
     */
    public List<Integer> candidates(long now, IntPredicate order, IntPredicate ready, IntPredicate layerFilter) {
        this.advanceLowestLayer();
        return this.layerCandidates(now, order, ready, layerFilter, false);
    }

    /**
     * Like {@link #candidates(long, IntPredicate, IntPredicate, IntPredicate)} but from the top layer down.
     */
    public List<Integer> candidatesTopDown(long now, IntPredicate order, IntPredicate ready, IntPredicate layerFilter) {
        return this.layerCandidates(now, order, ready, layerFilter, true);
    }

    /**
     * Targets around a chunk column, for building around the player: the ready targets of the chunks within one chunk
     * of it, or of the nearest ring of chunks further out that has any. Every layer counts, but only with the category
     * that is open in that layer (like {@link #candidates}), so water still comes after the walls that hold it.
     * The work per call depends on the few chunks looked at, not on the size of the schematic.
     */
    public List<Integer> candidatesNear(long now, IntPredicate filter, int chunkX, int chunkZ, int maxRing) {
        this.ensureChunkIndex();
        List<Integer> result = new ArrayList<>();
        int[] openCategory = new int[this.layerYs.length];
        Arrays.fill(openCategory, -1);
        for (int ring = 0; ring <= maxRing; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    long key = chunkKey(chunkX + dx, chunkZ + dz);
                    if (this.chunkPending.get(key) <= 0) {
                        continue;
                    }
                    int layer = 0;
                    for (int index : this.chunkIndex.get(key)) {
                        if (this.status[index] != PENDING || this.retryAt[index] > now) {
                            continue;
                        }
                        // Plan order is bottom-up, so the layer only ever moves forward within a chunk's list.
                        while (this.layerStarts[layer + 1] <= index) {
                            layer++;
                        }
                        if (openCategory[layer] < 0) {
                            openCategory[layer] = this.openCategory(layer);
                        }
                        if (this.categories[index] == openCategory[layer] && filter.test(index)) {
                            result.add(index);
                        }
                    }
                }
            }
            // The chunk the player stands in and the ones around it belong together; a single chunk would cut off
            // the targets right behind a chunk border.
            if (!result.isEmpty() && ring >= 1) {
                return result;
            }
        }
        return result;
    }

    /**
     * The {@code limit} targets of {@code indices} closest to a point. Building around the player hands over the
     * candidates of the chunks around it, hundreds of thousands in a dense schematic, while a decision needs only the
     * closest few: selecting instead of sorting keeps that linear.
     */
    public List<Integer> nearest(List<Integer> indices, int x, int y, int z, int limit) {
        if (indices.size() <= limit) {
            return indices;
        }
        long[] distances = new long[indices.size()];
        for (int i = 0; i < distances.length; i++) {
            long pos = this.positions[indices.get(i)];
            long dx = PosUtil.x(pos) - x;
            long dy = PosUtil.y(pos) - y;
            long dz = PosUtil.z(pos) - z;
            distances[i] = dx * dx + dy * dy + dz * dz;
        }
        long threshold = kthSmallest(distances.clone(), limit - 1);
        List<Integer> result = new ArrayList<>(limit);
        for (int i = 0; i < distances.length && result.size() < limit; i++) {
            if (distances[i] <= threshold) {
                result.add(indices.get(i));
            }
        }
        return result;
    }

    /** Quickselect: the value that would be at index {@code k} if {@code values} were sorted; reorders the array. */
    static long kthSmallest(long[] values, int k) {
        int low = 0;
        int high = values.length - 1;
        while (low < high) {
            long pivot = values[(low + high) >>> 1];
            int i = low;
            int j = high;
            while (i <= j) {
                while (values[i] < pivot) {
                    i++;
                }
                while (values[j] > pivot) {
                    j--;
                }
                if (i <= j) {
                    long swap = values[i];
                    values[i] = values[j];
                    values[j] = swap;
                    i++;
                    j--;
                }
            }
            if (k <= j) {
                high = j;
            } else if (k >= i) {
                low = i;
            } else {
                return values[k];
            }
        }
        return values[k];
    }

    /** The lowest layer with pending targets that {@code layerFilter} accepts, or -1. */
    public int firstOpenLayer(IntPredicate layerFilter) {
        this.advanceLowestLayer();
        return this.nextOpenLayer(this.lowestOpenLayer, layerFilter);
    }

    /** The lowest layer at or above {@code from} with pending targets that {@code layerFilter} accepts, or -1. */
    public int nextOpenLayer(int from, IntPredicate layerFilter) {
        for (int layer = Math.max(0, from); layer < this.layerYs.length; layer++) {
            if (this.layerPending[layer] > 0 && layerFilter.test(layer)) {
                return layer;
            }
        }
        return -1;
    }

    /** The highest layer at or below {@code from} with pending targets that {@code layerFilter} accepts, or -1. */
    public int previousOpenLayer(int from, IntPredicate layerFilter) {
        for (int layer = Math.min(this.layerYs.length - 1, from); layer >= 0; layer--) {
            if (this.layerPending[layer] > 0 && layerFilter.test(layer)) {
                return layer;
            }
        }
        return -1;
    }

    /**
     * The ready targets of one layer's open category within the chunks around a chunk column (see
     * {@link #candidatesNear} for the rings), in plan order. With a layer of hundreds of thousands of targets this is
     * what keeps a decision cheap: only the chunks around the player are looked at, not the whole layer. Empty when
     * nothing of the layer is ready nearby; the full scan of {@link #candidates} then finds what is left further away.
     */
    public List<Integer> candidatesNearInLayer(long now, IntPredicate ready, int layer, int chunkX, int chunkZ, int maxRing) {
        List<Integer> result = new ArrayList<>();
        int category = this.openCategory(layer);
        if (category < 0) {
            return result;
        }
        this.ensureChunkIndex();
        int from = this.layerStarts[layer];
        int to = this.layerStarts[layer + 1];
        for (int ring = 0; ring <= maxRing; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    long key = chunkKey(chunkX + dx, chunkZ + dz);
                    if (this.chunkPending.get(key) <= 0) {
                        continue;
                    }
                    int[] indices = this.chunkIndex.get(key);
                    // Plan order within the chunk, so the layer is one contiguous range of it.
                    int start = Arrays.binarySearch(indices, from);
                    if (start < 0) {
                        start = -start - 1;
                    }
                    for (int i = start; i < indices.length && indices[i] < to; i++) {
                        int index = indices[i];
                        if (this.status[index] == PENDING && this.retryAt[index] <= now
                                && this.categories[index] == category && ready.test(index)) {
                            result.add(index);
                        }
                    }
                }
            }
            if (!result.isEmpty() && ring >= 1) {
                return result;
            }
        }
        return result;
    }

    /** The lowest category that still has pending targets in a layer, or -1. */
    private int openCategory(int layer) {
        int row = layer * this.categoryCount;
        for (int category = 0; category < this.categoryCount; category++) {
            if (this.layerCategoryPending[row + category] > 0) {
                return category;
            }
        }
        return -1;
    }

    /**
     * Builds the chunk index now, e.g. on the loading thread, so the first decision does not stall on it. It is built
     * on first use otherwise.
     */
    public void prepareIndexes() {
        this.ensureChunkIndex();
    }

    private void ensureChunkIndex() {
        if (this.chunkIndex != null) {
            return;
        }
        // Two passes with exactly sized arrays: growing lists would briefly hold the index twice, and for tens of
        // millions of targets that is hundreds of megabytes the game may not have.
        Long2IntOpenHashMap counts = new Long2IntOpenHashMap();
        for (int i = 0; i < this.size(); i++) {
            counts.addTo(this.chunkKeyOf(i), 1);
        }
        Long2ObjectOpenHashMap<int[]> index = new Long2ObjectOpenHashMap<>(counts.size());
        Long2IntOpenHashMap fill = new Long2IntOpenHashMap(counts.size());
        Long2IntOpenHashMap pending = new Long2IntOpenHashMap(counts.size());
        for (Long2IntOpenHashMap.Entry entry : counts.long2IntEntrySet()) {
            index.put(entry.getLongKey(), new int[entry.getIntValue()]);
        }
        for (int i = 0; i < this.size(); i++) {
            long key = this.chunkKeyOf(i);
            index.get(key)[fill.addTo(key, 1)] = i;
            if (this.status[i] == PENDING) {
                pending.addTo(key, 1);
            }
        }
        this.chunkIndex = index;
        this.chunkPending = pending;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private long chunkKeyOf(int index) {
        long pos = this.positions[index];
        return chunkKey(PosUtil.x(pos) >> 4, PosUtil.z(pos) >> 4);
    }

    /** The layer (see {@link #layerCount}) a target belongs to. */
    public int layerOfIndex(int index) {
        return this.layerOf(index);
    }

    private List<Integer> layerCandidates(long now, IntPredicate order, IntPredicate ready, IntPredicate layerFilter,
                                          boolean topDown) {
        List<Integer> result = new ArrayList<>();
        int start = topDown ? this.layerYs.length - 1 : this.lowestOpenLayer;
        int end = topDown ? -1 : this.layerYs.length;
        int step = topDown ? -1 : 1;
        for (int layer = start; layer != end; layer += step) {
            if (this.layerPending[layer] == 0 || !layerFilter.test(layer)) {
                continue;
            }
            int ls = this.layerStarts[layer];
            int le = this.layerStarts[layer + 1];
            int minCategory = Integer.MAX_VALUE;
            for (int i = ls; i < le; i++) {
                if (this.status[i] == PENDING && this.retryAt[i] <= now && order.test(i)) {
                    minCategory = Math.min(minCategory, this.categories[i]);
                }
            }
            if (minCategory == Integer.MAX_VALUE) {
                continue;
            }
            for (int i = ls; i < le; i++) {
                if (this.status[i] == PENDING && this.retryAt[i] <= now && this.categories[i] == minCategory
                        && ready.test(i)) {
                    result.add(i);
                }
            }
            // Nothing of the open category can be worked on now (it all waits for support): the layer's later
            // categories keep waiting too, but the next layer may well have something.
            if (!result.isEmpty()) {
                return result;
            }
        }
        return result;
    }

    /** Whether a pending target accepted by {@code filter} exists, including deferred ones. */
    public boolean hasPending(IntPredicate filter) {
        for (int i = 0; i < this.size(); i++) {
            if (this.status[i] == PENDING && filter.test(i)) {
                return true;
            }
        }
        return false;
    }

    /** Pending targets of a layer, deferred ones included. */
    public int pendingInLayer(int layerIndex) {
        return layerIndex < 0 || layerIndex >= this.layerYs.length ? 0 : this.layerPending[layerIndex];
    }

    /** Target indices of a layer as {start, end (exclusive)}. */
    public int[] layerBounds(int layerIndex) {
        if (layerIndex < 0 || layerIndex >= this.layerYs.length) {
            return new int[]{0, 0};
        }
        return new int[]{this.layerStarts[layerIndex], this.layerStarts[layerIndex + 1]};
    }

    /**
     * Pending targets in build order starting at the lowest open layer.
     */
    public List<Integer> upcoming(int limit) {
        this.advanceLowestLayer();
        List<Integer> result = new ArrayList<>();
        int start = this.lowestOpenLayer < this.layerStarts.length ? this.layerStarts[this.lowestOpenLayer] : this.size();
        for (int i = start; i < this.size() && result.size() < limit; i++) {
            if (this.status[i] == PENDING) {
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
            if (this.status[i] == PENDING) {
                best = Math.min(best, this.retryAt[i]);
            }
        }
        return best;
    }

    private void setStatus(int index, Status newStatus) {
        Status old = STATUSES[this.status[index]];
        if (old == newStatus) {
            return;
        }
        int layer = this.layerOf(index);
        if (old == Status.DONE) {
            this.done--;
            this.layerDone[layer]--;
        } else if (old == Status.FAILED) {
            this.failed--;
        } else {
            this.pendingChanged(index, layer, -1);
        }
        if (newStatus == Status.DONE) {
            this.done++;
            this.layerDone[layer]++;
        } else if (newStatus == Status.FAILED) {
            this.failed++;
        } else {
            this.pendingChanged(index, layer, 1);
        }
        this.status[index] = (byte) newStatus.ordinal();
        this.listener.changed(index, old, newStatus);
    }

    private void pendingChanged(int index, int layer, int delta) {
        this.layerPending[layer] += delta;
        this.layerCategoryPending[layer * this.categoryCount + this.categories[index]] += delta;
        if (this.chunkPending != null) {
            this.chunkPending.addTo(this.chunkKeyOf(index), delta);
        }
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
        while (this.lowestOpenLayer < this.layerYs.length && this.layerPending[this.lowestOpenLayer] == 0) {
            this.lowestOpenLayer++;
        }
    }
}

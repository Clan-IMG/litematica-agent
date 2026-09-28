package net.clanimg.litematica_agent.agent;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Rough remaining-time estimate from the measured building speed. Includes walking and restocking time implicitly,
 * because it only looks at completed blocks over active time.
 */
public final class EtaEstimator {
    private static final double DEFAULT_SECONDS_PER_BLOCK = 0.8;
    private static final long WINDOW_MILLIS = 5 * 60 * 1000L;
    private static final int MIN_BLOCKS_FOR_MEASUREMENT = 8;

    private final Deque<long[]> samples = new ArrayDeque<>();
    private long activeMillis;
    private int completedWhileActive;
    private double smoothedSeconds = -1.0;

    /**
     * @param activeMillis total time the agent has been building (pauses excluded)
     * @param completed    blocks completed while building
     */
    public void sample(long activeMillis, int completed) {
        this.activeMillis = activeMillis;
        this.completedWhileActive = completed;
        this.samples.addLast(new long[]{activeMillis, completed});
        while (this.samples.size() > 2 && activeMillis - this.samples.peekFirst()[0] > WINDOW_MILLIS) {
            this.samples.removeFirst();
        }
    }

    public double secondsPerBlock() {
        double overall = this.completedWhileActive >= MIN_BLOCKS_FOR_MEASUREMENT
                ? this.activeMillis / 1000.0 / this.completedWhileActive
                : DEFAULT_SECONDS_PER_BLOCK;
        if (this.samples.size() < 2) {
            return overall;
        }
        long[] first = this.samples.peekFirst();
        long[] last = this.samples.peekLast();
        long blocks = last[1] - first[1];
        double seconds = (last[0] - first[0]) / 1000.0;
        if (blocks < MIN_BLOCKS_FOR_MEASUREMENT || seconds <= 0.0) {
            return overall;
        }
        double recent = seconds / blocks;
        return recent * 0.6 + overall * 0.4;
    }

    /**
     * Remaining minutes, rounded up, smoothed so the display does not jump around.
     */
    public long remainingMinutes(int remainingBlocks) {
        double seconds = remainingBlocks * this.secondsPerBlock();
        if (this.smoothedSeconds < 0.0) {
            this.smoothedSeconds = seconds;
        } else {
            this.smoothedSeconds = this.smoothedSeconds * 0.8 + seconds * 0.2;
        }
        if (remainingBlocks == 0) {
            return 0;
        }
        return Math.max(1L, (long) Math.ceil(this.smoothedSeconds / 60.0));
    }

    public boolean hasMeasurement() {
        return this.completedWhileActive >= MIN_BLOCKS_FOR_MEASUREMENT;
    }
}

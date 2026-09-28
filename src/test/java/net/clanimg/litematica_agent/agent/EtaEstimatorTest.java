package net.clanimg.litematica_agent.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EtaEstimatorTest {
    @Test
    void usesDefaultSpeedBeforeMeasurement() {
        EtaEstimator eta = new EtaEstimator();
        assertFalse(eta.hasMeasurement());
        assertEquals(0.8, eta.secondsPerBlock(), 1.0E-9);
        assertEquals(2, eta.remainingMinutes(100));
    }

    @Test
    void adaptsToMeasuredSpeed() {
        EtaEstimator eta = new EtaEstimator();
        for (int second = 0; second <= 120; second++) {
            eta.sample(second * 1000L, second / 2);
        }
        assertTrue(eta.hasMeasurement());
        assertEquals(2.0, eta.secondsPerBlock(), 0.1);
    }

    @Test
    void smoothsDisplayedValue() {
        EtaEstimator eta = new EtaEstimator();
        for (int second = 0; second <= 60; second++) {
            eta.sample(second * 1000L, second);
        }
        long first = eta.remainingMinutes(600);
        for (int second = 61; second <= 120; second++) {
            eta.sample(second * 1000L, 60 + (second - 60) / 4);
        }
        long second = eta.remainingMinutes(600);
        assertTrue(second >= first, "slower building must not decrease the estimate");
        assertTrue(second < 40, "estimate must move gradually, got " + second);
    }

    @Test
    void zeroWhenNothingRemains() {
        EtaEstimator eta = new EtaEstimator();
        assertEquals(0, eta.remainingMinutes(0));
    }
}

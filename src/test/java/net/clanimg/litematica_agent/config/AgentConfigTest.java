package net.clanimg.litematica_agent.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConfigTest {
    @Test
    void defaultSpeedKeepsTheOriginalTimings() {
        AgentConfig config = new AgentConfig();
        assertEquals(3, config.placeDelayTicks());
        assertEquals(3, config.containerClickDelayTicks());
        assertEquals(30.0F, config.rotationSpeed(), 1.0E-6);
        assertEquals(0.4, config.rotationSettle(), 1.0E-6);
        assertEquals(1.5F, config.rotationMinStep(), 1.0E-6);
    }

    @Test
    void speedRangeEnds() {
        AgentConfig config = new AgentConfig();
        config.speed = AgentConfig.FASTEST_SPEED;
        assertEquals(1, config.placeDelayTicks());
        assertEquals(1, config.containerClickDelayTicks());
        assertEquals(180.0F, config.rotationSpeed(), 1.0E-6);

        config.speed = AgentConfig.SLOWEST_SPEED;
        assertEquals(20, config.placeDelayTicks());
        assertEquals(12, config.containerClickDelayTicks());
        assertEquals(12.0F, config.rotationSpeed(), 1.0E-6);
    }

    @Test
    void slowerLevelsNeverGetFaster() {
        AgentConfig config = new AgentConfig();
        int previousDelay = 0;
        float previousRotation = Float.MAX_VALUE;
        double previousSettle = 0.0;
        for (int speed = AgentConfig.FASTEST_SPEED; speed <= AgentConfig.SLOWEST_SPEED; speed++) {
            config.speed = speed;
            assertTrue(config.placeDelayTicks() >= previousDelay, "place delay at speed " + speed);
            assertTrue(config.rotationSpeed() <= previousRotation, "rotation at speed " + speed);
            assertTrue(config.rotationSettle() >= previousSettle, "settling at speed " + speed);
            previousDelay = config.placeDelayTicks();
            previousRotation = config.rotationSpeed();
            previousSettle = config.rotationSettle();
        }
    }
}

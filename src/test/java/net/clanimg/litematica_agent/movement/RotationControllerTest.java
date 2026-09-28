package net.clanimg.litematica_agent.movement;

import net.clanimg.litematica_agent.config.AgentConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RotationControllerTest {
    private static RotationController atSpeed(AgentConfig config, int speed) {
        config.speed = speed;
        RotationController rotation = new RotationController();
        rotation.setSpeed(config.rotationSpeed(), config.rotationSettle(), config.rotationMinStep());
        return rotation;
    }

    @Test
    void fastestSpeedTurnsToTheNextBlockWithinOneTick() {
        AgentConfig config = new AgentConfig();
        RotationController rotation = atSpeed(config, AgentConfig.FASTEST_SPEED);
        float remaining = 20.0F;
        // Three frames at 60 fps are one game tick.
        for (int frame = 0; frame < 3; frame++) {
            remaining -= rotation.ease(remaining, config.rotationSpeed(), 1.0 / 3.0);
        }
        assertTrue(Math.abs(remaining) < 1.0F, "left after one tick: " + remaining);
    }

    @Test
    void defaultSpeedKeepsTheOriginalEasing() {
        AgentConfig config = new AgentConfig();
        RotationController rotation = atSpeed(config, AgentConfig.DEFAULT_SPEED);
        assertEquals(12.0F, rotation.ease(20.0F, config.rotationSpeed(), 1.0), 1.0E-4);
        assertEquals(1.5F, rotation.ease(2.0F, config.rotationSpeed(), 1.0), 1.0E-4);
    }

    @Test
    void neverOvershoots() {
        AgentConfig config = new AgentConfig();
        RotationController rotation = atSpeed(config, AgentConfig.FASTEST_SPEED);
        assertEquals(-3.0F, rotation.ease(-3.0F, config.rotationSpeed(), 2.0), 1.0E-4);
    }
}

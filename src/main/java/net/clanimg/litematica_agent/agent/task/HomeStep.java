package net.clanimg.litematica_agent.agent.task;

import net.clanimg.litematica_agent.agent.BuildAgent;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/**
 * Sends a server teleport command (e.g. {@code /home lager}) and waits until the player has actually been moved.
 */
final class HomeStep {
    enum State {
        RUNNING,
        DONE,
        FAILED
    }

    private static final int TIMEOUT = 20 * 10;
    private static final int SETTLE_TICKS = 30;

    private final String command;
    private @Nullable Vec3d start;
    private int timer;
    private int settle = -1;

    HomeStep(String command) {
        this.command = command;
    }

    State tick(BuildAgent agent) {
        this.timer++;
        Vec3d pos = agent.player().getEntityPos();
        if (this.start == null) {
            this.start = pos;
            agent.sendServerCommand(this.command);
            return State.RUNNING;
        }
        if (this.settle >= 0) {
            this.settle++;
            return this.settle >= SETTLE_TICKS ? State.DONE : State.RUNNING;
        }
        if (pos.distanceTo(this.start) > 8.0) {
            this.settle = 0;
            return State.RUNNING;
        }
        return this.timer > TIMEOUT ? State.FAILED : State.RUNNING;
    }
}

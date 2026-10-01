package net.clanimg.litematica_agent.agent.task;

import net.clanimg.litematica_agent.agent.BuildAgent;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.BlockPos;
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
    private final @Nullable String destinationDimension;
    private final @Nullable BlockPos destination;
    private @Nullable Vec3d start;
    private String startDimension;
    private int timer;
    private int settle = -1;
    private int attempts;

    HomeStep(String command) {
        this(command, null, null);
    }

    HomeStep(String command, @Nullable String dimension, @Nullable BlockPos destination) {
        this.command = command;
        this.destinationDimension = dimension;
        this.destination = destination;
    }

    State tick(BuildAgent agent) {
        this.timer++;
        Vec3d pos = agent.player().getEntityPos();
        if (this.start == null) {
            agent.movement().stop();
            this.start = pos;
            this.startDimension = agent.dimensionId();
            this.attempts++;
            agent.sendServerCommand(this.command);
            return State.RUNNING;
        }
        if (this.settle >= 0) {
            this.settle++;
            return this.settle >= SETTLE_TICKS ? State.DONE : State.RUNNING;
        }
        boolean moved = pos.distanceTo(this.start) > (this.destination == null ? 8.0 : 0.75)
                || !agent.dimensionId().equals(this.startDimension);
        boolean nearDestination = this.destination == null
                || (agent.dimensionId().equals(this.destinationDimension)
                && pos.squaredDistanceTo(Vec3d.ofCenter(this.destination)) <= 48.0 * 48.0
                && agent.world().isChunkLoaded(this.destination.getX() >> 4, this.destination.getZ() >> 4));
        if (moved && nearDestination) {
            this.settle = 0;
            return State.RUNNING;
        }
        if (this.timer > TIMEOUT) {
            if (this.attempts < 2) {
                this.start = null;
                this.timer = 0;
            } else {
                return State.FAILED;
            }
        }
        return State.RUNNING;
    }
}

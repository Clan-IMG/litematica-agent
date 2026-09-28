package net.clanimg.litematica_agent.agent.task;

import net.clanimg.litematica_agent.agent.BuildAgent;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * One step of work, ticked by the {@link BuildAgent} until it finishes.
 */
public interface AgentTask {
    enum Result {
        RUNNING,
        SUCCESS,
        FAILED
    }

    Result tick(BuildAgent agent);

    /** Called when the task is aborted (pause, cancel) so it can release keys or close screens. */
    default void cancel(BuildAgent agent) {
    }

    /** Short description for the status display. */
    Text describe();

    /** Translation key explaining a failure, empty if none. */
    default String failureReason() {
        return "";
    }

    /** The agent aborts a task that runs longer than this, so a single problem can never stall the build. */
    default int timeoutTicks() {
        return 20 * 45;
    }

    /** The block the task is about, shown in the agent view; null if none. */
    default @Nullable BlockPos focus() {
        return null;
    }
}

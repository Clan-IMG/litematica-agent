package net.clanimg.litematica_agent.agent.task;

import net.clanimg.litematica_agent.agent.BuildAgent;
import net.clanimg.litematica_agent.ui.Chat;
import net.minecraft.text.Text;

/**
 * Uses a server home/warp command, e.g. to get back to a build site on another plot.
 */
public final class HomeTask implements AgentTask {
    private final HomeStep step;
    private final String command;

    public HomeTask(String command) {
        this.command = command;
        this.step = new HomeStep(command);
    }

    @Override
    public Result tick(BuildAgent agent) {
        HomeStep.State state = this.step.tick(agent);
        return switch (state) {
            case RUNNING -> Result.RUNNING;
            case DONE -> Result.SUCCESS;
            case FAILED -> Result.FAILED;
        };
    }

    @Override
    public Text describe() {
        return Chat.tr("action.home", Text.literal("/" + this.command));
    }

    @Override
    public String failureReason() {
        return "home_failed";
    }
}

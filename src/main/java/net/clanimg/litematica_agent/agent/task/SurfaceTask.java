package net.clanimg.litematica_agent.agent.task;

import net.clanimg.litematica_agent.agent.BuildAgent;
import net.clanimg.litematica_agent.movement.InputController;
import net.clanimg.litematica_agent.movement.pathing.Goal;
import net.clanimg.litematica_agent.ui.Chat;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Swims up for air when the agent has been under water for too long, around a ceiling (a bridge, an overhang) if
 * straight up is blocked. Ends once the air supply is mostly back.
 */
public final class SurfaceTask implements AgentTask {
    private boolean pathStarted;
    private int timer;

    @Override
    public Result tick(BuildAgent agent) {
        ClientPlayerEntity player = agent.player();
        this.timer++;
        InputController.setJump(true);
        if (!player.isSubmergedInWater()) {
            agent.movement().stop();
            return player.getAir() >= player.getMaxAir() * 2 / 3 ? Result.SUCCESS : Result.RUNNING;
        }
        if (!this.pathStarted) {
            this.pathStarted = true;
            World world = agent.world();
            agent.moveTo(new Goal() {
                @Override
                public boolean isGoal(int x, int y, int z) {
                    return !world.getFluidState(new BlockPos(x, y + 1, z)).isIn(FluidTags.WATER);
                }

                @Override
                public double heuristic(int x, int y, int z) {
                    return 0.0;
                }
            });
        }
        agent.tickMovement();
        // Pressing space keeps swimming up even if no path was found; the movement's own keys come on top.
        InputController.setJump(true);
        return this.timer > 20 * 30 ? Result.FAILED : Result.RUNNING;
    }

    @Override
    public void cancel(BuildAgent agent) {
        agent.movement().stop();
    }

    @Override
    public Text describe() {
        return Chat.tr("action.surface");
    }

    @Override
    public String failureReason() {
        return "no_air";
    }
}

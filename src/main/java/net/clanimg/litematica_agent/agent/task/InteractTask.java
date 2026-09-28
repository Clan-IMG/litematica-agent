package net.clanimg.litematica_agent.agent.task;

import net.clanimg.litematica_agent.agent.BuildAgent;
import net.clanimg.litematica_agent.inventory.InventoryHelper;
import net.clanimg.litematica_agent.placement.Aiming;
import net.clanimg.litematica_agent.placement.StateMatcher;
import net.clanimg.litematica_agent.schematic.BuildTarget;
import net.clanimg.litematica_agent.ui.Chat;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Right-clicks a placed block until its adjustable properties match: repeater delay, comparator mode, lever,
 * doors, trapdoors, fence gates, note block pitch, daylight detector mode, lit candles and campfires, and the tool
 * conversion of dirt into farmland or a dirt path.
 */
public final class InteractTask implements AgentTask {
    private enum Phase {
        APPROACH,
        AIM,
        CLICK,
        WAIT
    }

    private static final int MAX_CLICKS = 30;
    /** Longest wait for the server's answer to a click the client does not show by itself (e.g. a lever). */
    private static final int WAIT_TICKS = 8;
    private static final int MAX_PHASES_PER_TICK = 4;

    private final int index;
    private final BuildTarget target;
    private final Approach approach;
    private Phase phase = Phase.APPROACH;
    private @Nullable Aiming.Aim aim;
    private @Nullable BlockState beforeClick;
    private int timer;
    private int clicks;
    private String failure = "";

    public InteractTask(int index, BuildTarget target) {
        this.index = index;
        this.target = target;
        this.approach = new Approach(target.pos());
    }

    public int index() {
        return this.index;
    }

    /** Phases that need no time in the world follow each other within the same tick. */
    @Override
    public Result tick(BuildAgent agent) {
        agent.setHeldSneak(false);
        this.timer++;
        for (int step = 0; step < MAX_PHASES_PER_TICK; step++) {
            Phase current = this.phase;
            Result result = this.step(agent);
            if (result != Result.RUNNING || this.phase == current) {
                return result;
            }
        }
        return Result.RUNNING;
    }

    private Result step(BuildAgent agent) {
        ClientPlayerEntity player = agent.player();
        BlockState state = agent.world().getBlockState(this.target.pos());
        if (StateMatcher.isComplete(state, this.target.state())) {
            return Result.SUCCESS;
        }
        if (!StateMatcher.needsInteraction(state, this.target.state())) {
            this.failure = "state_changed";
            return Result.FAILED;
        }

        switch (this.phase) {
            case APPROACH -> {
                Approach.State result = this.approach.tick(agent);
                if (result == Approach.State.FAILED) {
                    this.failure = this.approach.failure();
                    return Result.FAILED;
                }
                if (result == Approach.State.READY) {
                    this.next(Phase.AIM);
                }
            }
            case AIM -> {
                StateMatcher.Tool tool = StateMatcher.toolFor(state, this.target.state());
                if (tool == null) {
                    InventoryHelper.selectNeutralSlot(player);
                } else if (!agent.selectTool(tool)) {
                    this.failure = "missing_tool";
                    return Result.FAILED;
                }
                this.aim = Aiming.aimAt(player, this.target.pos(), player.getEyePos(), agent.reach());
                if (this.aim == null) {
                    this.next(Phase.APPROACH);
                    return Result.RUNNING;
                }
                agent.rotation().setTarget(this.aim.yaw(), this.aim.pitch());
                // Click as soon as the crosshair is on the planned face of the block, like a player would.
                if (this.onTarget(agent, player) != null && !player.isSneaking()) {
                    this.next(Phase.CLICK);
                } else if (this.timer > 40) {
                    this.failure = "aim_timeout";
                    return Result.FAILED;
                }
            }
            case CLICK -> {
                if (!agent.placeCooldownReady()) {
                    return Result.RUNNING;
                }
                BlockHitResult hit = this.onTarget(agent, player);
                if (hit == null) {
                    this.next(Phase.AIM);
                    return Result.RUNNING;
                }
                this.beforeClick = state;
                agent.clickBlock(hit);
                this.clicks++;
                this.next(Phase.WAIT);
            }
            case WAIT -> {
                // Blocks the client changes by itself (repeaters, comparators, doors, ...) can be clicked again right
                // away; for the others the server's answer is awaited, so no click is ever counted twice.
                if (state != this.beforeClick || this.timer >= WAIT_TICKS) {
                    if (this.clicks >= MAX_CLICKS) {
                        this.failure = "interaction_failed";
                        return Result.FAILED;
                    }
                    this.next(Phase.CLICK);
                }
            }
        }
        return Result.RUNNING;
    }

    private @Nullable BlockHitResult onTarget(BuildAgent agent, ClientPlayerEntity player) {
        BlockHitResult hit = Aiming.crosshair(player, agent.reach() + 0.5);
        return hit != null && this.aim != null && hit.getBlockPos().equals(this.target.pos())
                && hit.getSide() == this.aim.hit().getSide() ? hit : null;
    }

    private void next(Phase phase) {
        this.phase = phase;
        this.timer = 0;
    }

    @Override
    public void cancel(BuildAgent agent) {
        agent.movement().stop();
    }

    @Override
    public Text describe() {
        return Chat.tr("action.interact", this.target.state().getBlock().getName(), PlaceTask.posText(this.target.pos()));
    }

    @Override
    public String failureReason() {
        return this.failure;
    }
}

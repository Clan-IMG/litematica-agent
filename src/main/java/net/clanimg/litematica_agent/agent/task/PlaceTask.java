package net.clanimg.litematica_agent.agent.task;

import net.clanimg.litematica_agent.agent.BuildAgent;
import net.clanimg.litematica_agent.movement.MovementController;
import net.clanimg.litematica_agent.movement.pathing.Goal;
import net.clanimg.litematica_agent.placement.Aiming;
import net.clanimg.litematica_agent.placement.PlacementSolver;
import net.clanimg.litematica_agent.placement.StateMatcher;
import net.clanimg.litematica_agent.schematic.BuildTarget;
import net.clanimg.litematica_agent.ui.Chat;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Walks to a stand position (if needed), selects the item, sneaks if necessary, turns the camera to the planned
 * point, re-validates the real crosshair hit and right-clicks. Afterwards the world is checked.
 */
public final class PlaceTask implements AgentTask {
    private enum Phase {
        MOVE,
        PREPARE,
        SNEAK,
        AIM,
        CLICK,
        VERIFY
    }

    private static final int AIM_TIMEOUT = 30;
    private static final int VERIFY_TIMEOUT = 12;
    private static final int MAX_REAIMS = 3;
    private static final int SETTLE_TICKS = 8;

    private final int index;
    private final BuildTarget target;
    private final @Nullable BlockPos spot;
    private Phase phase;
    private boolean moving;
    private int timer;
    private int reaims;
    private @Nullable PlacementSolver.Option option;
    private @Nullable BlockState before;
    private String failure = "";
    private boolean missingItem;

    public PlaceTask(int index, BuildTarget target, @Nullable BlockPos spot) {
        this.index = index;
        this.target = target;
        this.spot = spot;
        this.phase = spot == null ? Phase.PREPARE : Phase.MOVE;
    }

    public int index() {
        return this.index;
    }

    public boolean isMissingItem() {
        return this.missingItem;
    }

    @Override
    public Result tick(BuildAgent agent) {
        ClientPlayerEntity player = agent.player();
        this.timer++;
        return switch (this.phase) {
            case MOVE -> this.tickMove(agent, player);
            case PREPARE -> this.tickPrepare(agent, player);
            case SNEAK -> this.tickSneak(agent, player);
            case AIM -> this.tickAim(agent, player);
            case CLICK -> this.tickClick(agent, player);
            case VERIFY -> this.tickVerify(agent);
        };
    }

    private Result tickMove(BuildAgent agent, ClientPlayerEntity player) {
        if (!this.moving) {
            this.moving = true;
            if (!agent.moveTo(Goal.block(this.spot.getX(), this.spot.getY(), this.spot.getZ()))) {
                return this.fail("path_not_found");
            }
        }
        if (this.timer % 5 == 0 && agent.isStable() && !agent.isPlayerInTheWay(this.target)
                && agent.solver().findFromEye(player, this.target, player.getEyePos(), agent.reach()) != null) {
            agent.movement().stop();
            return this.next(Phase.PREPARE);
        }
        MovementController.Status status = agent.tickMovement();
        if (status == MovementController.Status.ARRIVED) {
            return this.next(Phase.PREPARE);
        }
        if (status == MovementController.Status.FAILED) {
            return this.fail("path_" + agent.movement().getFailure());
        }
        if (status == MovementController.Status.IDLE) {
            return this.fail("path_interrupted");
        }
        return Result.RUNNING;
    }

    private Result tickPrepare(BuildAgent agent, ClientPlayerEntity player) {
        BlockState state = agent.world().getBlockState(this.target.pos());
        if (StateMatcher.isComplete(state, this.target.state())) {
            return Result.SUCCESS;
        }
        if (agent.isPlayerInTheWay(this.target)) {
            return this.fail("player_in_the_way");
        }
        if (!agent.selectItem(this.target.item())) {
            this.missingItem = true;
            return this.fail("missing_item");
        }
        this.option = agent.solver().findFromEye(player, this.target, player.getEyePos(), agent.reach());
        if (this.option == null) {
            // Right after walking the player may still slide a little; give it a moment before giving up.
            return this.timer < SETTLE_TICKS ? Result.RUNNING : this.fail("no_placement_option");
        }
        agent.setHeldSneak(this.option.sneak());
        return this.next(this.option.sneak() && !player.isInSneakingPose() && !player.getAbilities().flying ? Phase.SNEAK : Phase.AIM);
    }

    private Result tickSneak(BuildAgent agent, ClientPlayerEntity player) {
        agent.setHeldSneak(true);
        if (player.isInSneakingPose() || this.timer > 10) {
            this.option = agent.solver().findFromEye(player, this.target, player.getEyePos(), agent.reach());
            if (this.option == null) {
                return this.fail("no_placement_option");
            }
            return this.next(Phase.AIM);
        }
        return Result.RUNNING;
    }

    private Result tickAim(BuildAgent agent, ClientPlayerEntity player) {
        agent.setHeldSneak(this.option.sneak());
        agent.rotation().setTarget(this.option.yaw(), this.option.pitch());
        if (agent.rotation().isAligned(player, 0.4F)) {
            return this.next(Phase.CLICK);
        }
        if (this.timer > AIM_TIMEOUT) {
            return this.fail("aim_timeout");
        }
        return Result.RUNNING;
    }

    private Result tickClick(BuildAgent agent, ClientPlayerEntity player) {
        agent.setHeldSneak(this.option.sneak());
        if (!agent.placeCooldownReady()) {
            return Result.RUNNING;
        }
        if (!player.getMainHandStack().isOf(this.target.item())) {
            return this.retry(Phase.PREPARE);
        }
        // Normally the click goes exactly where the crosshair points. For look tricks the camera deliberately looks
        // elsewhere, so the planned face is clicked instead; the result is still verified with the real rotation.
        BlockHitResult hit = this.option.lookTrick() ? this.option.hit() : Aiming.crosshair(player, agent.reach() + 0.5);
        if (hit == null || !agent.solver().check(player, this.target, hit, this.option.sneak())) {
            return this.retry(Phase.PREPARE);
        }
        this.before = agent.world().getBlockState(this.target.pos());
        agent.clickBlock(hit);
        agent.onBlockPlaced(this.target.pos());
        return this.next(Phase.VERIFY);
    }

    private Result tickVerify(BuildAgent agent) {
        agent.setHeldSneak(this.option != null && this.option.sneak() && this.timer < 3);
        BlockState state = agent.world().getBlockState(this.target.pos());
        if (state != this.before && state.isOf(this.target.state().getBlock())) {
            return Result.SUCCESS;
        }
        if (this.timer > VERIFY_TIMEOUT) {
            return this.fail("placement_not_confirmed");
        }
        return Result.RUNNING;
    }

    private Result retry(Phase phase) {
        this.reaims++;
        if (this.reaims > MAX_REAIMS) {
            return this.fail("crosshair_mismatch");
        }
        return this.next(phase);
    }

    private Result next(Phase phase) {
        this.phase = phase;
        this.timer = 0;
        return Result.RUNNING;
    }

    private Result fail(String reason) {
        this.failure = reason;
        return Result.FAILED;
    }

    @Override
    public void cancel(BuildAgent agent) {
        agent.setHeldSneak(false);
        agent.movement().stop();
    }

    @Override
    public Text describe() {
        return Chat.tr("action.place", this.target.item().getName(), posText(this.target.pos()));
    }

    @Override
    public String failureReason() {
        return this.failure;
    }

    static Text posText(BlockPos pos) {
        return Text.literal(pos.getX() + " " + pos.getY() + " " + pos.getZ());
    }
}

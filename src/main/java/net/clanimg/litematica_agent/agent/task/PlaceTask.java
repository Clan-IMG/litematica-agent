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
import net.minecraft.util.math.Vec3d;
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
    /** Upper bound for phases passed through in one tick; a retry loop can never spin. */
    private static final int MAX_PHASES_PER_TICK = 6;
    /** The eye moved farther than this (squared) since the click was planned: plan it again. */
    private static final double EYE_MOVED_SQ = 0.02 * 0.02;

    private final int index;
    private final BuildTarget target;
    private final @Nullable BlockPos spot;
    private Phase phase;
    private boolean moving;
    private int timer;
    private int reaims;
    private @Nullable PlacementSolver.Option option;
    /** Where the eye was when {@link #option} was planned. */
    private @Nullable Vec3d optionEye;
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

    /**
     * Phases that need no time in the world follow each other within the same tick: selecting the item, turning,
     * clicking and checking the client's own placement. Only walking, sneaking, turning the camera and the place
     * delay take ticks, so a block costs as little time as a player's click would.
     */
    @Override
    public Result tick(BuildAgent agent) {
        ClientPlayerEntity player = agent.player();
        this.timer++;
        for (int step = 0; step < MAX_PHASES_PER_TICK; step++) {
            Phase current = this.phase;
            Result result = switch (current) {
                case MOVE -> this.tickMove(agent, player);
                case PREPARE -> this.tickPrepare(agent, player);
                case SNEAK -> this.tickSneak(agent, player);
                case AIM -> this.tickAim(agent, player);
                case CLICK -> this.tickClick(agent, player);
                case VERIFY -> this.tickVerify(agent);
            };
            if (result != Result.RUNNING || this.phase == current) {
                return result;
            }
        }
        return Result.RUNNING;
    }

    private Result tickMove(BuildAgent agent, ClientPlayerEntity player) {
        if (!this.moving) {
            this.moving = true;
            if (!agent.moveTo(Goal.block(this.spot.getX(), this.spot.getY(), this.spot.getZ()))) {
                return this.fail("path_not_found");
            }
        }
        // Stop as soon as the block can be placed from where the player is, not only at the planned spot.
        if (agent.isStable() && !agent.isPlayerInTheWay(this.target)
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
        this.plan(agent, player);
        if (this.option == null) {
            // Right after walking the player may still slide a little; give it a moment before giving up.
            return this.timer < SETTLE_TICKS ? Result.RUNNING : this.fail("no_placement_option");
        }
        agent.setHeldSneak(this.option.sneak());
        // Start turning right away; the camera moves every frame until the next tick.
        agent.rotation().setTarget(this.option.yaw(), this.option.pitch());
        return this.next(this.option.sneak() && !player.isInSneakingPose() && !player.getAbilities().flying ? Phase.SNEAK : Phase.AIM);
    }

    private Result tickSneak(BuildAgent agent, ClientPlayerEntity player) {
        agent.setHeldSneak(true);
        if (player.isInSneakingPose() || this.timer > 10) {
            this.plan(agent, player);
            if (this.option == null) {
                return this.fail("no_placement_option");
            }
            return this.next(Phase.AIM);
        }
        return Result.RUNNING;
    }

    private Result tickAim(BuildAgent agent, ClientPlayerEntity player) {
        if (this.optionEye != null && player.getEyePos().squaredDistanceTo(this.optionEye) > EYE_MOVED_SQ) {
            // The player still slides after walking: the planned view direction no longer fits, plan it again.
            return this.next(Phase.PREPARE);
        }
        agent.setHeldSneak(this.option.sneak());
        agent.rotation().setTarget(this.option.yaw(), this.option.pitch());
        // Like a player, click as soon as the crosshair is on a spot that gives exactly the right block; the camera
        // does not have to rest on the planned point. Look tricks need the planned view direction itself.
        boolean aligned = agent.rotation().isAligned(player, 0.4F);
        if (this.option.lookTrick() ? aligned : this.validHit(agent, player) != null) {
            return this.next(Phase.CLICK);
        }
        if (aligned) {
            // Looking exactly at the planned point and still no fitting hit: something changed, plan again.
            return this.retry(Phase.PREPARE);
        }
        if (this.timer > AIM_TIMEOUT) {
            return this.fail("aim_timeout");
        }
        return Result.RUNNING;
    }

    private void plan(BuildAgent agent, ClientPlayerEntity player) {
        this.optionEye = player.getEyePos();
        this.option = agent.solver().findFromEye(player, this.target, this.optionEye, agent.reach());
    }

    private Result tickClick(BuildAgent agent, ClientPlayerEntity player) {
        agent.setHeldSneak(this.option.sneak());
        if (!agent.placeCooldownReady()) {
            return Result.RUNNING;
        }
        if (!player.getMainHandStack().isOf(this.target.item())) {
            return this.retry(Phase.PREPARE);
        }
        BlockHitResult hit = this.validHit(agent, player);
        if (hit == null) {
            return this.retry(Phase.PREPARE);
        }
        this.before = agent.world().getBlockState(this.target.pos());
        agent.clickBlock(hit);
        agent.onBlockPlaced(this.target.pos());
        return this.next(Phase.VERIFY);
    }

    /**
     * The hit the click would produce right now, if it places exactly the target block. Normally that is where the
     * crosshair points. For look tricks the camera deliberately looks elsewhere, so the planned face is clicked
     * instead; the result is still verified with the real rotation.
     */
    private @Nullable BlockHitResult validHit(BuildAgent agent, ClientPlayerEntity player) {
        BlockHitResult hit = this.option.lookTrick() ? this.option.hit() : Aiming.crosshair(player, agent.reach() + 0.5);
        return hit != null && agent.solver().check(player, this.target, hit, this.option.sneak()) ? hit : null;
    }

    /**
     * The client places the block itself right away and the server confirms it; a rejected placement is reverted by
     * the server and noticed by the agent's continuous rescan.
     */
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

package net.clanimg.litematica_agent.agent.task;

import net.clanimg.litematica_agent.agent.BuildAgent;
import net.clanimg.litematica_agent.inventory.InventoryHelper;
import net.clanimg.litematica_agent.movement.MovementController;
import net.clanimg.litematica_agent.movement.pathing.Goal;
import net.clanimg.litematica_agent.placement.Aiming;
import net.clanimg.litematica_agent.ui.Chat;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Mines a block by holding left click, choosing the best tool that still has durability left.
 */
public final class BreakTask implements AgentTask {
    private enum Phase {
        APPROACH,
        AIM,
        MINE,
        COLLECT
    }

    private static final int MINE_TIMEOUT = 20 * 30;
    private static final int MAX_REAIMS = 8;
    /** How long to wait near a just-mined block for the drop to be swept up once the agent stopped approaching it. */
    private static final int COLLECT_SETTLE_TICKS = 6;
    /** Upper bound while still walking toward the drop, in case it is further away than expected. */
    private static final int COLLECT_TIMEOUT_TICKS = 15;

    private final int index;
    private final BlockPos pos;
    private final Approach approach;
    private Phase phase = Phase.APPROACH;
    private @Nullable Aiming.Aim aim;
    private int timer;
    private int reaims;
    private boolean started;
    private String failure = "";

    /**
     * @param index build target index, or -1 for helper blocks
     */
    public BreakTask(int index, BlockPos pos) {
        this.index = index;
        this.pos = pos;
        this.approach = new Approach(pos);
    }

    public BlockPos pos() {
        return this.pos;
    }

    public int index() {
        return this.index;
    }

    @Override
    public Result tick(BuildAgent agent) {
        ClientPlayerEntity player = agent.player();
        agent.setHeldSneak(false);
        this.timer++;
        BlockState state = agent.world().getBlockState(this.pos);
        if (this.phase != Phase.COLLECT && (state.isAir() || state.getOutlineShape(agent.world(), this.pos).isEmpty())) {
            agent.onBlockRemoved(this.pos);
            if (this.started && !player.isInCreativeMode()) {
                // Walk to the drop so it is not simply left behind; someone else grabbing it first is fine.
                this.stopMining(agent);
                agent.moveTo(Goal.near(this.pos.getX() + 0.5, this.pos.getY() + 0.5, this.pos.getZ() + 0.5, 1.0));
                this.next(Phase.COLLECT);
            } else {
                return Result.SUCCESS;
            }
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
                this.selectTool(agent, player, state);
                this.aim = Aiming.aimAt(player, this.pos, player.getEyePos(), agent.reach());
                if (this.aim == null) {
                    this.next(Phase.APPROACH);
                    return Result.RUNNING;
                }
                agent.rotation().setTarget(this.aim.yaw(), this.aim.pitch());
                if (agent.rotation().isAligned(player, 1.0F)) {
                    this.next(Phase.MINE);
                } else if (this.timer > 40) {
                    this.failure = "aim_timeout";
                    return Result.FAILED;
                }
            }
            case MINE -> {
                BlockHitResult hit = Aiming.crosshair(player, agent.reach() + 0.5);
                if (hit == null || !hit.getBlockPos().equals(this.pos)) {
                    this.stopMining(agent);
                    // Aimed, but the crosshair lands elsewhere again and again (a drifting player, an edge): give up
                    // after a few tries instead of flipping between aiming and mining until the task times out.
                    if (++this.reaims > MAX_REAIMS) {
                        this.failure = "crosshair_mismatch";
                        return Result.FAILED;
                    }
                    this.next(Phase.AIM);
                    return Result.RUNNING;
                }
                ItemStack held = player.getMainHandStack();
                if (held.isDamageable() && InventoryHelper.remainingDurability(held) <= agent.config().toolDurabilityReserve) {
                    agent.protectTool(held);
                    this.stopMining(agent);
                    this.next(Phase.AIM);
                    return Result.RUNNING;
                }
                if (!this.started) {
                    agent.interactionManager().attackBlock(this.pos, hit.getSide());
                    this.started = true;
                } else if (player.isInCreativeMode()) {
                    if (this.timer % 6 == 0) {
                        agent.interactionManager().attackBlock(this.pos, hit.getSide());
                    }
                } else {
                    agent.interactionManager().updateBlockBreakingProgress(this.pos, hit.getSide());
                }
                player.swingHand(Hand.MAIN_HAND);
                if (this.timer > MINE_TIMEOUT) {
                    this.stopMining(agent);
                    this.failure = "break_timeout";
                    return Result.FAILED;
                }
            }
            case COLLECT -> {
                MovementController.Status status = agent.tickMovement();
                boolean approaching = status == MovementController.Status.MOVING;
                if (approaching ? this.timer > COLLECT_TIMEOUT_TICKS : this.timer > COLLECT_SETTLE_TICKS) {
                    return Result.SUCCESS;
                }
            }
        }
        return Result.RUNNING;
    }

    private void selectTool(BuildAgent agent, ClientPlayerEntity player, BlockState state) {
        if (player.isInCreativeMode()) {
            return;
        }
        int slot = InventoryHelper.findBestTool(player.getInventory(), state, agent.config().toolDurabilityReserve);
        InventoryHelper.selectSlot(agent.client(), slot);
    }

    private void stopMining(BuildAgent agent) {
        if (this.started) {
            agent.interactionManager().cancelBlockBreaking();
            this.started = false;
        }
    }

    private void next(Phase phase) {
        this.phase = phase;
        this.timer = 0;
    }

    @Override
    public void cancel(BuildAgent agent) {
        this.stopMining(agent);
        agent.movement().stop();
    }

    @Override
    public Text describe() {
        return Chat.tr("action.break", PlaceTask.posText(this.pos));
    }

    @Override
    public String failureReason() {
        return this.failure;
    }
}

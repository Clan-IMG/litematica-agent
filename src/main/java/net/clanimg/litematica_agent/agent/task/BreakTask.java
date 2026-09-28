package net.clanimg.litematica_agent.agent.task;

import net.clanimg.litematica_agent.agent.BuildAgent;
import net.clanimg.litematica_agent.inventory.InventoryHelper;
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
        MINE
    }

    private static final int MINE_TIMEOUT = 20 * 30;

    private final int index;
    private final BlockPos pos;
    private final Approach approach;
    private Phase phase = Phase.APPROACH;
    private @Nullable Aiming.Aim aim;
    private int timer;
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
        if (state.isAir() || state.getOutlineShape(agent.world(), this.pos).isEmpty()) {
            agent.onBlockRemoved(this.pos);
            return Result.SUCCESS;
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

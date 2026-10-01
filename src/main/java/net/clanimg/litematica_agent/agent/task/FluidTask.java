package net.clanimg.litematica_agent.agent.task;

import net.clanimg.litematica_agent.agent.BuildAgent;
import net.clanimg.litematica_agent.inventory.InventoryHelper;
import net.clanimg.litematica_agent.movement.RotationController;
import net.clanimg.litematica_agent.placement.Aiming;
import net.clanimg.litematica_agent.placement.WaterPlacement;
import net.clanimg.litematica_agent.schematic.BuildTarget;
import net.clanimg.litematica_agent.ui.Chat;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EntityPose;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.jetbrains.annotations.Nullable;

/**
 * Works with water the way a player does: pours a bucket onto a face next to the target so a source appears there,
 * pours it into a dry waterloggable block, drains a block that has to be dry, or puts a lily pad onto the water.
 * An empty bucket is filled again from a renewable source nearby first - taking from one never undoes finished water.
 */
public final class FluidTask implements AgentTask {
    public enum Mode {
        FILL,
        WATERLOG,
        DRAIN,
        SURFACE
    }

    private enum Phase {
        PREPARE,
        REFILL_APPROACH,
        REFILL_AIM,
        REFILL_USE,
        REFILL_WAIT,
        APPROACH,
        AIM,
        USE,
        VERIFY
    }

    /** Ticks the result has to hold before it counts, like a placed block (see PlaceTask). */
    private static final int CONFIRM_TICKS = 3;
    private static final int VERIFY_TIMEOUT = 20;
    private static final int AIM_TIMEOUT = 40;
    private static final int MAX_REFILLS = 3;
    private static final int MAX_USES = 3;
    private static final int MAX_REAIMS = 3;
    private static final int MAX_APPROACHES = 3;
    private static final int MAX_PHASES_PER_TICK = 6;
    /** Within this angle the crosshair is where the aim planned it; the real ray is checked before every use. */
    private static final float ALIGNED_DEGREES = 0.5F;
    /** How far off the middle of a planned position the player may end up, see {@link #bodyAt}. */
    private static final double BODY_MARGIN = 0.35;

    private final int index;
    private final BuildTarget target;
    private final Mode mode;
    private Approach approach;
    private int approaches = 1;
    private @Nullable BlockPos refillSource;
    private @Nullable Approach refillApproach;
    private Phase phase = Phase.PREPARE;
    private @Nullable Aiming.Aim aim;
    private int timer;
    private int confirmTicks;
    private int uses;
    private int refills;
    private int reaims;
    private int waterBucketsBefore;
    private String failure = "";

    public FluidTask(int index, BuildTarget target, Mode mode) {
        this.index = index;
        this.target = target;
        this.mode = mode;
        this.approach = this.newApproach();
    }

    public int index() {
        return this.index;
    }

    public Mode mode() {
        return this.mode;
    }

    /** Failed for lack of water: no bucket to pour, nothing to refill an empty one from. */
    public boolean ranOutOfWater() {
        return "no_water_bucket".equals(this.failure) || "no_water_source".equals(this.failure)
                || "refill_failed".equals(this.failure) || "no_empty_bucket".equals(this.failure);
    }

    /** The click of this mode from an eye position, or null; also used to prefer water work within reach. */
    public static @Nullable Aiming.Aim aimFor(Mode mode, ClientPlayerEntity player, BlockPos pos, Vec3d eye, double reach) {
        return switch (mode) {
            case FILL -> WaterPlacement.fillAim(player, pos, eye, reach);
            case WATERLOG -> WaterPlacement.waterlogAim(player, pos, eye, reach);
            case DRAIN -> WaterPlacement.drainAim(player, pos, eye, reach);
            // A lily pad cannot be placed into the player's own body, e.g. while floating right below it.
            case SURFACE -> bodyAt(player, eye).intersects(new Box(pos)) ? null : WaterPlacement.surfaceAim(player, pos, eye, reach);
        };
    }

    /**
     * The player's body with some room around it: floating in water, the player never ends up exactly in the middle
     * of the planned position, and a lily pad cannot go where any part of the body is.
     */
    private static Box bodyAt(ClientPlayerEntity player, Vec3d eye) {
        double feet = eye.y - player.getEyeHeight(EntityPose.STANDING);
        double half = player.getWidth() / 2.0 + BODY_MARGIN;
        return new Box(eye.x - half, feet, eye.z - half, eye.x + half, feet + player.getHeight(), eye.z + half);
    }

    private Approach newApproach() {
        BlockPos pos = this.target.pos();
        return new Approach(pos, (player, eye, reach) -> aimFor(this.mode, player, pos, eye, reach));
    }

    private Item item() {
        return switch (this.mode) {
            case FILL, WATERLOG -> Items.WATER_BUCKET;
            case DRAIN -> Items.BUCKET;
            case SURFACE -> this.target.item();
        };
    }

    /** The ray the server casts for this item: a full bucket ignores water, an empty one and a lily pad stop at sources. */
    private RaycastContext.FluidHandling fluidHandling() {
        return this.mode == Mode.FILL || this.mode == Mode.WATERLOG
                ? RaycastContext.FluidHandling.NONE : RaycastContext.FluidHandling.SOURCE_ONLY;
    }

    private boolean achieved(BuildAgent agent) {
        BlockState state = agent.world().getBlockState(this.target.pos());
        return switch (this.mode) {
            case FILL -> state.isOf(Blocks.WATER) && state.getFluidState().isStill();
            case WATERLOG -> state.contains(Properties.WATERLOGGED) && state.get(Properties.WATERLOGGED);
            case DRAIN -> state.contains(Properties.WATERLOGGED) && !state.get(Properties.WATERLOGGED);
            case SURFACE -> state.isOf(this.target.state().getBlock());
        };
    }

    /** Phases that need no time in the world follow each other within the same tick. */
    @Override
    public Result tick(BuildAgent agent) {
        agent.setHeldSneak(false);
        this.timer++;
        for (int step = 0; step < MAX_PHASES_PER_TICK; step++) {
            Phase current = this.phase;
            Result result = this.step(agent, agent.player());
            if (result != Result.RUNNING || this.phase == current) {
                return result;
            }
        }
        return Result.RUNNING;
    }

    private Result step(BuildAgent agent, ClientPlayerEntity player) {
        switch (this.phase) {
            case PREPARE -> {
                return this.prepare(agent, player);
            }
            case REFILL_APPROACH -> {
                Approach.State state = this.refillApproach.tick(agent);
                if (state == Approach.State.FAILED) {
                    // Another source may work better; PREPARE looks again, bounded by MAX_REFILLS.
                    return this.next(Phase.PREPARE);
                }
                return state == Approach.State.READY ? this.next(Phase.REFILL_AIM) : Result.RUNNING;
            }
            case REFILL_AIM -> {
                if (!agent.selectItem(Items.BUCKET)) {
                    return this.next(Phase.PREPARE);
                }
                this.aim = WaterPlacement.refillAim(player, this.refillSource, player.getEyePos(), agent.reach());
                if (this.aim == null) {
                    return this.next(Phase.PREPARE);
                }
                return this.turn(agent, player, Phase.REFILL_USE);
            }
            case REFILL_USE -> {
                if (!agent.placeCooldownReady() || !RotationController.isKnownToServer(player)) {
                    return Result.RUNNING;
                }
                BlockHitResult hit = WaterPlacement.currentHit(player, RaycastContext.FluidHandling.SOURCE_ONLY);
                if (!player.getMainHandStack().isOf(Items.BUCKET) || hit == null
                        || !WaterPlacement.isRenewable(agent.world(), hit.getBlockPos())) {
                    return this.reaim(Phase.REFILL_AIM);
                }
                this.waterBucketsBefore = InventoryHelper.count(player.getInventory(), Items.WATER_BUCKET);
                agent.useItem();
                return this.next(Phase.REFILL_WAIT);
            }
            case REFILL_WAIT -> {
                if (InventoryHelper.count(player.getInventory(), Items.WATER_BUCKET) > this.waterBucketsBefore
                        || this.timer > VERIFY_TIMEOUT) {
                    return this.next(Phase.PREPARE);
                }
                return Result.RUNNING;
            }
            case APPROACH -> {
                Approach.State state = this.approach.tick(agent);
                if (state == Approach.State.FAILED) {
                    return this.fail(this.approach.failure());
                }
                return state == Approach.State.READY ? this.next(Phase.AIM) : Result.RUNNING;
            }
            case AIM -> {
                if (!agent.selectItem(this.item())) {
                    return this.next(Phase.PREPARE);
                }
                this.aim = aimFor(this.mode, player, this.target.pos(), player.getEyePos(), agent.reach());
                if (this.aim == null) {
                    if (++this.approaches > MAX_APPROACHES) {
                        return this.fail("unreachable");
                    }
                    this.approach = this.newApproach();
                    return this.next(Phase.APPROACH);
                }
                return this.turn(agent, player, Phase.USE);
            }
            case USE -> {
                if (!agent.placeCooldownReady() || !RotationController.isKnownToServer(player)) {
                    return Result.RUNNING;
                }
                if (!player.getMainHandStack().isOf(this.item())) {
                    return this.next(Phase.PREPARE);
                }
                if (!this.hitMatches(player)) {
                    return this.reaim(Phase.AIM);
                }
                agent.useItem();
                this.uses++;
                this.confirmTicks = 0;
                return this.next(Phase.VERIFY);
            }
            case VERIFY -> {
                if (this.achieved(agent)) {
                    return ++this.confirmTicks >= CONFIRM_TICKS ? Result.SUCCESS : Result.RUNNING;
                }
                if (this.confirmTicks > 0 || this.timer > VERIFY_TIMEOUT) {
                    if (this.uses < MAX_USES) {
                        return this.next(Phase.PREPARE);
                    }
                    return this.fail(this.mode == Mode.SURFACE ? "placement_not_confirmed" : "water_not_placed");
                }
                return Result.RUNNING;
            }
        }
        return Result.RUNNING;
    }

    private Result prepare(BuildAgent agent, ClientPlayerEntity player) {
        if (this.achieved(agent)) {
            return Result.SUCCESS;
        }
        Item item = this.item();
        if (item == Items.WATER_BUCKET && !player.isInCreativeMode() && !agent.hasItem(Items.WATER_BUCKET)) {
            if (!agent.hasItem(Items.BUCKET)) {
                return this.fail("no_water_bucket");
            }
            if (this.refills++ >= MAX_REFILLS) {
                return this.fail("refill_failed");
            }
            BlockPos source = WaterPlacement.findRenewable(agent.world(), player.getBlockPos(), WaterPlacement.REFILL_RADIUS);
            if (source == null) {
                source = WaterPlacement.findRenewable(agent.world(), this.target.pos(), WaterPlacement.REFILL_RADIUS);
            }
            if (source == null) {
                return this.fail("no_water_source");
            }
            BlockPos found = source;
            this.refillSource = found;
            this.refillApproach = new Approach(found, (p, eye, reach) -> WaterPlacement.refillAim(p, found, eye, reach));
            return this.next(Phase.REFILL_APPROACH);
        }
        if (!agent.selectItem(item)) {
            return this.fail(item == Items.BUCKET ? "no_empty_bucket" : item == Items.WATER_BUCKET ? "no_water_bucket" : "missing_item");
        }
        return this.next(Phase.APPROACH);
    }

    /** Turns towards the planned point; the use itself waits until the server knows this view direction. */
    private Result turn(BuildAgent agent, ClientPlayerEntity player, Phase then) {
        agent.rotation().setTarget(this.aim.yaw(), this.aim.pitch());
        if (agent.rotation().isAligned(player, ALIGNED_DEGREES)) {
            agent.rotation().hold(player);
            return this.next(then);
        }
        if (this.timer > AIM_TIMEOUT) {
            return this.fail("aim_timeout");
        }
        return Result.RUNNING;
    }

    /** Whether the ray the server will cast with the current view direction hits what the aim planned. */
    private boolean hitMatches(ClientPlayerEntity player) {
        BlockHitResult hit = WaterPlacement.currentHit(player, this.fluidHandling());
        if (hit == null || this.aim == null) {
            return false;
        }
        BlockPos pos = this.target.pos();
        return switch (this.mode) {
            case FILL -> hit.getBlockPos().equals(this.aim.hit().getBlockPos()) && hit.getSide() == this.aim.hit().getSide();
            case WATERLOG, DRAIN -> hit.getBlockPos().equals(pos);
            case SURFACE -> hit.getBlockPos().equals(pos.down());
        };
    }

    private Result reaim(Phase phase) {
        if (++this.reaims > MAX_REAIMS) {
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
        agent.movement().stop();
    }

    @Override
    public Text describe() {
        Text pos = PlaceTask.posText(this.target.pos());
        return switch (this.mode) {
            case FILL -> Chat.tr("action.water_fill", pos);
            case WATERLOG -> Chat.tr("action.water_log", this.target.state().getBlock().getName(), pos);
            case DRAIN -> Chat.tr("action.water_drain", this.target.state().getBlock().getName(), pos);
            case SURFACE -> Chat.tr("action.place", this.target.item().getName(), pos);
        };
    }

    @Override
    public String failureReason() {
        return this.failure;
    }

    @Override
    public int timeoutTicks() {
        return 20 * 90;
    }
}

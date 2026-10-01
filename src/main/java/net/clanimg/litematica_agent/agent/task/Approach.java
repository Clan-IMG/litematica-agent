package net.clanimg.litematica_agent.agent.task;

import net.clanimg.litematica_agent.agent.BuildAgent;
import net.clanimg.litematica_agent.movement.MovementController;
import net.clanimg.litematica_agent.movement.pathing.Goal;
import net.clanimg.litematica_agent.placement.Aiming;
import net.clanimg.litematica_agent.placement.StandSpots;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EntityPose;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/**
 * Gets the player into a position from which a block is visible and within reach, or more generally from which a
 * given click is possible (see {@link AimProvider}).
 */
final class Approach {
    enum State {
        RUNNING,
        READY,
        FAILED
    }

    /** The click that works from an eye position, or null if none does. */
    interface AimProvider {
        @Nullable Aiming.Aim aim(ClientPlayerEntity player, Vec3d eye, double reach);
    }

    /** A journey that gets nowhere is ended by the movement's own stuck detection; this only catches an endless one. */
    private static final int MAX_TRAVEL_TICKS = 20 * 60 * 10;
    private static final int MAX_TRAVEL_LEGS = 3;

    private final BlockPos pos;
    private final AimProvider aims;
    private boolean moving;
    private boolean travelling;
    private int travelLegs;
    private int travelTicks;
    private int retries;
    private String failure = "";

    Approach(BlockPos pos) {
        this(pos, (player, eye, reach) -> Aiming.aimAt(player, pos, eye, reach));
    }

    /**
     * @param pos  where stand positions are searched around
     * @param aims decides whether a position works
     */
    Approach(BlockPos pos, AimProvider aims) {
        this.pos = pos;
        this.aims = aims;
    }

    String failure() {
        return this.failure;
    }

    /** True while on the journey to a far-off block; the owner's time limits are for the last stretch, not for this. */
    boolean travelling() {
        return this.travelling;
    }

    State tick(BuildAgent agent) {
        ClientPlayerEntity player = agent.player();
        if (!this.moving) {
            if (this.aims.aim(player, player.getEyePos(), agent.reach()) != null) {
                return State.READY;
            }
            // Far off, or in chunks that are not loaded (the storage chest hundreds of blocks from where a plot this
            // size is being built): a stand spot cannot be found in a world that is not there yet. The journey first,
            // as the column at whatever height is reachable; the stand spot is searched once the chunks are loaded.
            if (!agent.world().isChunkLoaded(this.pos.getX() >> 4, this.pos.getZ() >> 4)
                    || BuildAgent.isFarAway(this.pos, player)) {
                if (++this.travelLegs > MAX_TRAVEL_LEGS) {
                    this.failure = "unreachable";
                    return State.FAILED;
                }
                if (!agent.moveTo(Goal.column(this.pos.getX() + 0.5, this.pos.getZ() + 0.5, 12.0))) {
                    this.failure = "path_not_found";
                    return State.FAILED;
                }
                agent.debug("Approach {}: travelling there from {}", this.pos.toShortString(), player.getBlockPos().toShortString());
                this.travelling = true;
                this.moving = true;
            } else {
                float eyeHeight = player.getEyeHeight(EntityPose.STANDING);
                double planningReach = agent.reach() - StandSpots.ARRIVAL_MARGIN;
                StandSpots.Result<Aiming.Aim> spot = StandSpots.search(player, this.pos, agent.reach(), agent.canFly(),
                        agent.approachStandable(), null, 150,
                        feet -> this.aims.aim(player, StandSpots.eyeAt(feet, eyeHeight), planningReach));
                if (spot == null) {
                    this.failure = "unreachable";
                    agent.debug("Approach {}: no stand spot found from {}", this.pos.toShortString(), player.getEyePos());
                    return State.FAILED;
                }
                agent.debug("Approach {}: walking to {}", this.pos.toShortString(), spot.feet().toShortString());
                if (!agent.moveTo(Goal.block(spot.feet().getX(), spot.feet().getY(), spot.feet().getZ()))) {
                    this.failure = "path_not_found";
                    return State.FAILED;
                }
                this.moving = true;
            }
        }
        if (this.travelling && ++this.travelTicks > MAX_TRAVEL_TICKS) {
            agent.movement().stop();
            this.failure = "unreachable";
            return State.FAILED;
        }
        MovementController.Status status = agent.tickMovement();
        if (status == MovementController.Status.ARRIVED || status == MovementController.Status.IDLE) {
            // IDLE: something else stopped the movement (a door, a safety stop); look again from where the player is.
            this.moving = false;
            if (this.travelling) {
                this.travelling = false;
                return State.RUNNING;
            }
            if (this.aims.aim(player, player.getEyePos(), agent.reach()) != null) {
                return State.READY;
            }
            agent.debug("Approach {}: arrived at {} but cannot see it (eye {})", this.pos.toShortString(),
                    player.getBlockPos().toShortString(), player.getEyePos());
            if (++this.retries <= 2) {
                return State.RUNNING;
            }
            this.failure = "unreachable";
            return State.FAILED;
        }
        if (status == MovementController.Status.FAILED) {
            this.failure = "path_" + agent.movement().getFailure();
            return State.FAILED;
        }
        return State.RUNNING;
    }
}

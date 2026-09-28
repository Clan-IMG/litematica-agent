package net.clanimg.litematica_agent.agent.task;

import net.clanimg.litematica_agent.agent.BuildAgent;
import net.clanimg.litematica_agent.movement.MovementController;
import net.clanimg.litematica_agent.movement.pathing.Goal;
import net.clanimg.litematica_agent.placement.Aiming;
import net.clanimg.litematica_agent.placement.StandSpots;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EntityPose;
import net.minecraft.util.math.BlockPos;

/**
 * Gets the player into a position from which a block is visible and within reach.
 */
final class Approach {
    enum State {
        RUNNING,
        READY,
        FAILED
    }

    private final BlockPos pos;
    private boolean moving;
    private int retries;
    private String failure = "";

    Approach(BlockPos pos) {
        this.pos = pos;
    }

    String failure() {
        return this.failure;
    }

    State tick(BuildAgent agent) {
        ClientPlayerEntity player = agent.player();
        if (!this.moving) {
            if (Aiming.aimAt(player, this.pos, player.getEyePos(), agent.reach()) != null) {
                return State.READY;
            }
            float eyeHeight = player.getEyeHeight(EntityPose.STANDING);
            double planningReach = agent.reach() - StandSpots.ARRIVAL_MARGIN;
            StandSpots.Result<Aiming.Aim> spot = StandSpots.search(player, this.pos, agent.reach(), agent.canFly(),
                    agent.standable(), null, 150,
                    feet -> Aiming.aimAt(player, this.pos, StandSpots.eyeAt(feet, eyeHeight), planningReach));
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
        MovementController.Status status = agent.tickMovement();
        if (status == MovementController.Status.ARRIVED) {
            this.moving = false;
            if (Aiming.aimAt(player, this.pos, player.getEyePos(), agent.reach()) != null) {
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

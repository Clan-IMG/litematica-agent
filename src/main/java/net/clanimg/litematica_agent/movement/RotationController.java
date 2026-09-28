package net.clanimg.litematica_agent.movement;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Turns the camera smoothly towards a target like a player moving the mouse.
 */
public final class RotationController {
    private float targetYaw;
    private float targetPitch;
    private boolean hasTarget;
    private float maxYawStep = 35.0F;
    private float maxPitchStep = 25.0F;
    private double settle = 0.4;
    private float minStep = 1.5F;

    /**
     * @param degreesPerTick fastest turn per tick
     * @param settle         share of the remaining turn left after one tick close to the target (0..1)
     * @param minStep        smallest turn per tick, so the last degrees do not crawl
     */
    public void setSpeed(float degreesPerTick, double settle, float minStep) {
        this.maxYawStep = Math.max(5.0F, degreesPerTick);
        this.maxPitchStep = Math.max(5.0F, degreesPerTick * 0.75F);
        this.settle = Math.max(0.0, Math.min(0.95, settle));
        this.minStep = Math.max(0.5F, minStep);
    }

    public void lookAt(Vec3d eye, Vec3d point) {
        float[] angles = anglesTo(eye, point);
        this.setTarget(angles[0], angles[1]);
    }

    public void setTarget(float yaw, float pitch) {
        this.targetYaw = yaw;
        this.targetPitch = MathHelper.clamp(pitch, -90.0F, 90.0F);
        this.hasTarget = true;
    }

    public void clear() {
        this.hasTarget = false;
    }

    public boolean hasTarget() {
        return this.hasTarget;
    }

    public void tick(ClientPlayerEntity player) {
        if (!this.hasTarget) {
            return;
        }
        float yaw = player.getYaw();
        float pitch = player.getPitch();
        float yawDelta = MathHelper.wrapDegrees(this.targetYaw - yaw);
        float pitchDelta = this.targetPitch - pitch;
        float yawStep = this.ease(yawDelta, this.maxYawStep, 1.0);
        float pitchStep = this.ease(pitchDelta, this.maxPitchStep, 1.0);
        player.setYaw(yaw + yawStep);
        player.setPitch(MathHelper.clamp(pitch + pitchStep, -90.0F, 90.0F));
        player.setHeadYaw(player.getYaw());
    }

    /**
     * Turns by the share of a tick's turn that belongs to the time since the last frame. Goes through the same method
     * as mouse input, so the view moves at the frame rate instead of 20 times per second.
     *
     * @param ticks game ticks since the last call (fractional)
     */
    public void frame(ClientPlayerEntity player, double ticks) {
        if (!this.hasTarget || ticks <= 0.0) {
            return;
        }
        float yawStep = this.ease(MathHelper.wrapDegrees(this.targetYaw - player.getYaw()), this.maxYawStep, ticks);
        float pitchStep = this.ease(this.targetPitch - player.getPitch(), this.maxPitchStep, ticks);
        // changeLookDirection takes mouse deltas and multiplies them by 0.15.
        player.changeLookDirection(yawStep / 0.15, pitchStep / 0.15);
    }

    public boolean isAligned(ClientPlayerEntity player, float tolerance) {
        if (!this.hasTarget) {
            return true;
        }
        return Math.abs(MathHelper.wrapDegrees(this.targetYaw - player.getYaw())) <= tolerance
                && Math.abs(this.targetPitch - player.getPitch()) <= tolerance;
    }

    /**
     * Large differences are covered at full speed, the last few degrees slow down like a real mouse movement. Scaled
     * to {@code ticks}, so many small frame steps add up to the same turn as one tick step.
     */
    float ease(float delta, float maxStep, double ticks) {
        float abs = Math.abs(delta);
        if (abs < 0.01F) {
            return delta;
        }
        double limit = maxStep * ticks;
        double step = abs > maxStep * 2.0F ? limit
                : Math.max(Math.min(abs, this.minStep * ticks), abs * (1.0 - Math.pow(this.settle, ticks)));
        step = Math.min(step, limit);
        return (float) Math.copySign(Math.min(step, abs), delta);
    }

    public static float[] anglesTo(Vec3d eye, Vec3d point) {
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float pitch = (float) -(MathHelper.atan2(dy, horizontal) * (180.0 / Math.PI));
        return new float[]{MathHelper.wrapDegrees(yaw), pitch};
    }
}

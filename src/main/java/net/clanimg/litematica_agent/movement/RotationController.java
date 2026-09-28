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

    public void setSpeed(float degreesPerTick) {
        this.maxYawStep = Math.max(5.0F, degreesPerTick);
        this.maxPitchStep = Math.max(5.0F, degreesPerTick * 0.75F);
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
        float yawStep = ease(yawDelta, this.maxYawStep);
        float pitchStep = ease(pitchDelta, this.maxPitchStep);
        player.setYaw(yaw + yawStep);
        player.setPitch(MathHelper.clamp(pitch + pitchStep, -90.0F, 90.0F));
        player.setHeadYaw(player.getYaw());
    }

    public boolean isAligned(ClientPlayerEntity player, float tolerance) {
        if (!this.hasTarget) {
            return true;
        }
        return Math.abs(MathHelper.wrapDegrees(this.targetYaw - player.getYaw())) <= tolerance
                && Math.abs(this.targetPitch - player.getPitch()) <= tolerance;
    }

    /**
     * Large differences are covered at full speed, the last few degrees slow down like a real mouse movement.
     */
    private static float ease(float delta, float maxStep) {
        float abs = Math.abs(delta);
        if (abs < 0.01F) {
            return delta;
        }
        float step = abs > maxStep * 2.0F ? maxStep : Math.max(Math.min(abs, 1.5F), abs * 0.6F);
        step = Math.min(step, maxStep);
        return Math.copySign(Math.min(step, abs), delta);
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

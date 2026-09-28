package net.clanimg.litematica_agent.mixin;

import net.clanimg.litematica_agent.movement.InputController;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds the agent's key presses into the regular movement input, so movement goes through exactly the same code path
 * as a player pressing W/A/S/D, space, shift and sprint. Works independently of the user's toggle-sneak settings.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends Input {
    @Inject(method = "tick", at = @At("TAIL"))
    private void litematicaAgent$applyAgentInput(CallbackInfo ci) {
        PlayerInput override = InputController.getOverride();
        if (override == null) {
            return;
        }
        this.playerInput = override;
        float forward = axis(override.forward(), override.backward());
        float sideways = axis(override.left(), override.right());
        this.movementVector = new Vec2f(sideways, forward).normalize();
    }

    private static float axis(boolean positive, boolean negative) {
        if (positive == negative) {
            return 0.0F;
        }
        return positive ? 1.0F : -1.0F;
    }
}

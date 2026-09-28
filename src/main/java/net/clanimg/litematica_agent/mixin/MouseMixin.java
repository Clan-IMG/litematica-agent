package net.clanimg.litematica_agent.mixin;

import net.clanimg.litematica_agent.agent.AgentManager;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs once per rendered frame, right where the player's own mouse turns the camera, so the agent can turn it just
 * as smoothly.
 */
@Mixin(Mouse.class)
public abstract class MouseMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void litematicaAgent$turnCamera(CallbackInfo ci) {
        AgentManager.get().onFrame();
    }
}

package net.clanimg.litematica_agent.mixin;

import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The view direction the client sent to the server with its last movement packet.
 */
@Mixin(ClientPlayerEntity.class)
public interface ClientPlayerEntityAccessor {
    @Accessor("lastYawClient")
    float litematicaAgent$getLastSentYaw();

    @Accessor("lastPitchClient")
    float litematicaAgent$getLastSentPitch();
}

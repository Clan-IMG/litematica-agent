package net.clanimg.litematica_agent.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ClientPlayerInteractionManager.class)
public interface InteractionManagerInvoker {
    /** Sends the selected hotbar slot to the server now, before a block-breaking packet that depends on it. */
    @Invoker("syncSelectedSlot")
    void litematicaAgent$syncSelectedSlot();
}

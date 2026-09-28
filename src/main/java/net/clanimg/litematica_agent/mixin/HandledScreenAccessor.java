package net.clanimg.litematica_agent.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
    @Accessor("x")
    int litematicaAgent$getX();

    @Accessor("y")
    int litematicaAgent$getY();

    @Accessor("backgroundWidth")
    int litematicaAgent$getBackgroundWidth();

    @Accessor("backgroundHeight")
    int litematicaAgent$getBackgroundHeight();
}

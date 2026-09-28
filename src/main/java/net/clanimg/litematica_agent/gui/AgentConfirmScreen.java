package net.clanimg.litematica_agent.gui;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.text.Text;

public final class AgentConfirmScreen extends ConfirmScreen implements AgentScreen {
    public AgentConfirmScreen(BooleanConsumer callback, Text title, Text message) {
        super(callback, title, message);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}

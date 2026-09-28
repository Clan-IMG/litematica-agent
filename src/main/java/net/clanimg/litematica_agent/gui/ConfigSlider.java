package net.clanimg.litematica_agent.gui;

import net.clanimg.litematica_agent.agent.AgentManager;
import net.clanimg.litematica_agent.config.AgentConfig;
import net.clanimg.litematica_agent.ui.Chat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

/**
 * A slider for a whole-number setting. Changes apply at once (the agent reads the config every tick) and are saved.
 */
public final class ConfigSlider extends SliderWidget {
    private final int min;
    private final int max;
    private final IntSupplier getter;
    private final IntConsumer setter;
    private final IntFunction<Text> label;

    public ConfigSlider(int x, int y, int width, int min, int max, IntSupplier getter, IntConsumer setter,
                        IntFunction<Text> label, Text tooltip) {
        super(x, y, width, 20, Text.empty(), MathHelper.clamp((getter.getAsInt() - min) / (double) (max - min), 0.0, 1.0));
        this.min = min;
        this.max = max;
        this.getter = getter;
        this.setter = setter;
        this.label = label;
        this.setTooltip(Tooltip.of(tooltip));
        this.updateMessage();
    }

    public static ConfigSlider speed(int x, int y, int width) {
        AgentConfig config = AgentManager.get().config();
        return new ConfigSlider(x, y, width, AgentConfig.FASTEST_SPEED, AgentConfig.SLOWEST_SPEED,
                () -> config.speed, value -> config.speed = value,
                value -> Chat.tr(value == AgentConfig.DEFAULT_SPEED ? "lock.speed_default" : "lock.speed", value),
                Chat.tr("lock.speed_tooltip"));
    }

    /** Shows the frame rate the PC actually reaches when it is lower than the setting. */
    public static ConfigSlider agentFps(int x, int y, int width) {
        AgentConfig config = AgentManager.get().config();
        return new ConfigSlider(x, y, width, AgentConfig.MIN_AGENT_FPS, AgentConfig.MAX_AGENT_FPS,
                () -> config.agentFps, value -> config.agentFps = value,
                value -> {
                    int reached = MinecraftClient.getInstance().getCurrentFps();
                    return reached < value ? Chat.tr("settings.agent_fps_limited", value, reached) : Chat.tr("settings.agent_fps", value);
                },
                Chat.tr("settings.agent_fps_tooltip"));
    }

    /** Re-reads the label, e.g. when it depends on something other than the value. */
    public void refreshMessage() {
        this.updateMessage();
    }

    private int current() {
        return this.min + (int) Math.round(this.value * (this.max - this.min));
    }

    @Override
    protected void updateMessage() {
        this.setMessage(this.label.apply(this.current()));
    }

    @Override
    protected void applyValue() {
        int value = this.current();
        if (this.getter.getAsInt() != value) {
            this.setter.accept(value);
            AgentManager.get().saveConfig();
        }
    }
}

package net.clanimg.litematica_agent.gui;

import net.clanimg.litematica_agent.agent.AgentManager;
import net.clanimg.litematica_agent.config.AgentConfig;
import net.clanimg.litematica_agent.config.AgentConfig.MaterialView;
import net.clanimg.litematica_agent.config.AgentConfig.WrongBlockMode;
import net.clanimg.litematica_agent.ui.Chat;
import net.clanimg.litematica_agent.ui.Messages;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * All settings of the agent in one scrollable list, like the vanilla options screens. The lock screen only keeps the
 * quick controls; new settings belong here. Does not pause the game, so the agent keeps working while it is open.
 */
public final class AgentSettingsScreen extends GameOptionsScreen implements AgentScreen {
    private @Nullable ConfigSlider fpsSlider;

    public AgentSettingsScreen(@Nullable Screen parent) {
        super(parent, MinecraftClient.getInstance().options, Chat.tr("settings.title"));
    }

    @Override
    protected void addOptions() {
        AgentManager manager = AgentManager.get();
        AgentConfig config = manager.config();

        this.body.addHeader(Chat.tr("settings.section_build"));
        this.fpsSlider = ConfigSlider.agentFps(0, 0, 150);
        this.body.addWidgetEntry(ConfigSlider.speed(0, 0, 150), this.fpsSlider);
        this.body.addWidgetEntry(
                CyclingButtonWidget.builder((WrongBlockMode mode) -> Chat.tr("lock.mode_" + mode.id()), config.wrongBlockMode)
                        .values(WrongBlockMode.values())
                        .tooltip(mode -> Tooltip.of(Chat.tr("lock.mode_" + mode.id() + "_tooltip")))
                        .build(Chat.tr("settings.mode"), (button, mode) -> {
                            config.wrongBlockMode = mode;
                            manager.saveConfig();
                        }),
                toggle("sprint", config.sprint, value -> config.sprint = value));
        this.body.addWidgetEntry(
                toggle("helper_blocks", config.useHelperBlocks, value -> config.useHelperBlocks = value),
                toggle("look_tricks", config.allowLookTricks, value -> config.allowLookTricks = value));

        this.body.addHeader(Chat.tr("settings.section_tools"));
        this.body.addWidgetEntry(
                toggle("auto_tool", config.autoTool, value -> config.autoTool = value),
                new ConfigSlider(0, 0, 150, 1, 100, () -> config.toolDurabilityReserve,
                        value -> config.toolDurabilityReserve = value,
                        value -> Chat.tr("settings.tool_reserve", value), Chat.tr("settings.tool_reserve_tooltip")));

        this.body.addHeader(Chat.tr("settings.section_survival"));
        this.body.addWidgetEntry(
                new ConfigSlider(0, 0, 150, 1, 19, () -> config.eatAtFoodLevel, value -> config.eatAtFoodLevel = value,
                        value -> Chat.tr("settings.eat_at", value), Chat.tr("settings.eat_at_tooltip")),
                new ConfigSlider(0, 0, 150, 1, 20, () -> Math.round(config.pauseAtHealth), value -> config.pauseAtHealth = value,
                        value -> Chat.tr("settings.pause_health", hearts(value)), Chat.tr("settings.pause_health_tooltip")));
        this.body.addWidgetEntry(
                new ConfigSlider(0, 0, 150, 1, 20, () -> Math.round(config.emergencyHealth), value -> config.emergencyHealth = value,
                        value -> Chat.tr("settings.emergency_health", hearts(value)), Chat.tr("settings.emergency_health_tooltip")),
                toggle("emergency_disconnect", config.emergencyDisconnect, value -> config.emergencyDisconnect = value));

        this.body.addHeader(Chat.tr("settings.section_display"));
        this.body.addWidgetEntry(
                CyclingButtonWidget.builder((String language) -> Text.literal(language.toUpperCase(Locale.ROOT)), config.language)
                        .values(Messages.availableLanguages())
                        .build(Chat.tr("settings.language"), (button, language) -> {
                            config.language = language;
                            manager.saveConfig();
                            Messages.load(language);
                            // Rebuild so every label appears in the new language.
                            this.client.setScreen(new AgentSettingsScreen(this.parent));
                        }),
                CyclingButtonWidget.builder((MaterialView view) -> Chat.tr("settings.view_" + view.name().toLowerCase(Locale.ROOT)),
                                config.materialView)
                        .values(MaterialView.values())
                        .build(Chat.tr("settings.view"), (button, view) -> {
                            config.materialView = view;
                            manager.saveConfig();
                        }));
        this.body.addWidgetEntry(
                toggle("material_hud", config.showMaterialHud, value -> config.showMaterialHud = value),
                toggle("verbose_logging", config.verboseLogging, value -> config.verboseLogging = value));
    }

    private static CyclingButtonWidget<Boolean> toggle(String key, boolean value, Consumer<Boolean> setter) {
        return CyclingButtonWidget.onOffBuilder(Chat.tr("settings.on"), Chat.tr("settings.off"), value)
                .tooltip(current -> Tooltip.of(Chat.tr("settings." + key + "_tooltip")))
                .build(Chat.tr("settings." + key), (button, current) -> {
                    setter.accept(current);
                    AgentManager.get().saveConfig();
                });
    }

    private static String hearts(int halfHearts) {
        return String.format(Locale.ROOT, "%.1f", halfHearts / 2.0);
    }

    @Override
    public void tick() {
        super.tick();
        // The reached frame rate changes all the time.
        if (this.fpsSlider != null) {
            this.fpsSlider.refreshMessage();
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}

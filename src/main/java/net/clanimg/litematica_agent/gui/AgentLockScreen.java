package net.clanimg.litematica_agent.gui;

import net.clanimg.litematica_agent.agent.AgentManager;
import net.clanimg.litematica_agent.agent.AgentSession;
import net.clanimg.litematica_agent.agent.BuildAgent;
import net.clanimg.litematica_agent.agent.BuildStrategy;
import net.clanimg.litematica_agent.agent.SessionRuntime;
import net.clanimg.litematica_agent.agent.SessionState;
import net.clanimg.litematica_agent.config.AgentConfig.MaterialView;
import net.clanimg.litematica_agent.config.AgentConfig.WrongBlockMode;
import net.clanimg.litematica_agent.inventory.InventoryHelper;
import net.clanimg.litematica_agent.planning.BuildPlan;
import net.clanimg.litematica_agent.schematic.BuildTarget;
import net.clanimg.litematica_agent.ui.Chat;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shown while the agent is in control. Keyboard and mouse only reach this screen, so the player cannot disturb the
 * agent by accident, while the chat stays usable. The world stays visible behind it. Everything beyond the quick
 * controls lives in the settings screen (gear button).
 */
public final class AgentLockScreen extends ChatScreen implements AgentScreen {
    private static final int PANEL_WIDTH = 230;
    private static final int MAX_PANEL_HEIGHT = 272;
    private static final int PADDING = 8;
    /** Space between the icon and its count, and between two entries of the item row. */
    private static final int ITEM_TEXT_GAP = 2;
    private static final int ITEM_GAP = 8;
    private static final int ITEM_ROW_HEIGHT = 18;
    private static final int COLOR_PANEL = 0xB0101418;
    private static final int COLOR_BORDER = 0xFF2DD4BF;
    private static final int COLOR_TEXT = 0xFFE5E7EB;
    private static final int COLOR_MUTED = 0xFF9CA3AF;
    private static final int COLOR_TITLE = 0xFF5EEAD4;
    private static final int COLOR_BAR_BG = 0xFF374151;
    private static final int COLOR_BAR = 0xFF22C55E;
    private static final int COLOR_LAYER_BAR = 0xFF38BDF8;
    private static final int COLOR_PAUSED = 0xFFFBBF24;

    private final List<ClickableWidget> panelButtons = new ArrayList<>();
    private final List<ButtonWidget> modeOptions = new ArrayList<>();
    private ButtonWidget pauseButton;
    private ButtonWidget resumeButton;
    private ButtonWidget cancelButton;
    private ButtonWidget approveButton;
    private ButtonWidget rejectButton;
    private ButtonWidget modeButton;
    private ButtonWidget viewButton;
    private ButtonWidget strategyButton;
    private boolean modeMenuOpen;
    private int panelX;
    private int panelY;
    private int panelHeight;
    /** Texts and items of the panel end here, above the buttons. */
    private int contentBottom;

    public AgentLockScreen() {
        super("", false);
    }

    @Override
    protected void init() {
        super.init();
        this.panelX = this.width - PANEL_WIDTH - 6;
        this.panelY = 6;
        this.panelHeight = Math.min(MAX_PANEL_HEIGHT, this.height - 12);
        int bottom = this.panelY + this.panelHeight;
        int toolsY = bottom - 98;
        this.contentBottom = toolsY - 4;
        int buttonY = bottom - 74;
        int speedY = bottom - 50;
        int modeY = bottom - 26;
        int fullWidth = PANEL_WIDTH - PADDING * 2;
        int halfWidth = (fullWidth - 4) / 2;
        int buttonWidth = (fullWidth - 8) / 3;
        int x = this.panelX + PADDING;

        // The options open below the panel like a dropdown list, or above the mode button in small windows. They are
        // added first so that they receive clicks before the buttons they cover.
        this.modeOptions.clear();
        int optionCount = WrongBlockMode.values().length;
        int optionY = bottom + 2;
        if (optionY + optionCount * 22 > this.height) {
            optionY = modeY - optionCount * 22;
        }
        for (WrongBlockMode mode : WrongBlockMode.values()) {
            this.modeOptions.add(this.modeOption(mode, x, optionY, fullWidth));
            optionY += 22;
        }

        this.viewButton = this.addSelectableChild(ButtonWidget.builder(Text.empty(), button -> this.nextView())
                .dimensions(x, toolsY, halfWidth, 20).tooltip(Tooltip.of(Chat.tr("lock.view_tooltip"))).build());
        this.strategyButton = this.addSelectableChild(ButtonWidget.builder(Text.empty(), button -> this.openStrategy())
                .dimensions(x + halfWidth + 4, toolsY, halfWidth, 20).tooltip(Tooltip.of(Chat.tr("lock.strategy_tooltip"))).build());

        this.pauseButton = this.addSelectableChild(ButtonWidget.builder(Chat.tr("lock.pause"), button -> AgentManager.get().pauseActive())
                .dimensions(x, buttonY, buttonWidth, 20).build());
        this.resumeButton = this.addSelectableChild(ButtonWidget.builder(Chat.tr("lock.resume"), button -> {
                    AgentSession session = AgentManager.get().activeSession();
                    if (session != null) {
                        AgentManager.get().resume(session.id);
                    }
                })
                .dimensions(x, buttonY, buttonWidth, 20).build());
        this.approveButton = this.addSelectableChild(ButtonWidget.builder(Chat.tr("lock.approve"),
                        button -> AgentManager.get().answerApproval(true))
                .dimensions(x, buttonY, buttonWidth, 20).build());
        this.cancelButton = this.addSelectableChild(ButtonWidget.builder(Chat.tr("lock.cancel"), button -> this.confirmCancel())
                .dimensions(x + buttonWidth + 4, buttonY, buttonWidth, 20).build());
        this.rejectButton = this.addSelectableChild(ButtonWidget.builder(Chat.tr("lock.reject"),
                        button -> AgentManager.get().answerApproval(false))
                .dimensions(x + buttonWidth + 4, buttonY, buttonWidth, 20).build());
        ButtonWidget leave = this.addSelectableChild(ButtonWidget.builder(Chat.tr("lock.leave"), button -> this.close())
                .dimensions(x + (buttonWidth + 4) * 2, buttonY, buttonWidth, 20).build());

        ConfigSlider speed = this.addSelectableChild(ConfigSlider.speed(x, speedY, fullWidth));
        this.modeButton = this.addSelectableChild(ButtonWidget.builder(Text.empty(), button -> this.modeMenuOpen = !this.modeMenuOpen)
                .dimensions(x, modeY, fullWidth - 24, 20).build());
        ButtonWidget settings = this.addSelectableChild(ButtonWidget.builder(Text.literal("⚙"),
                        button -> this.client.setScreen(new AgentSettingsScreen(this)))
                .dimensions(x + fullWidth - 20, modeY, 20, 20).tooltip(Tooltip.of(Chat.tr("settings.title"))).build());

        this.panelButtons.clear();
        this.panelButtons.addAll(List.of(this.viewButton, this.strategyButton, this.pauseButton, this.resumeButton,
                this.approveButton, this.cancelButton, this.rejectButton, leave, speed, this.modeButton, settings));
        this.updateButtons();
    }

    private ButtonWidget modeOption(WrongBlockMode mode, int x, int y, int width) {
        return this.addSelectableChild(ButtonWidget.builder(modeName(mode), button -> {
                    AgentManager manager = AgentManager.get();
                    manager.config().wrongBlockMode = mode;
                    manager.saveConfig();
                    this.modeMenuOpen = false;
                    this.updateButtons();
                })
                .dimensions(x, y, width, 20)
                .tooltip(Tooltip.of(Chat.tr("lock.mode_" + mode.id() + "_tooltip")))
                .build());
    }

    private static Text modeName(WrongBlockMode mode) {
        return Chat.tr("lock.mode_" + mode.id());
    }

    private void nextView() {
        AgentManager manager = AgentManager.get();
        MaterialView[] views = MaterialView.values();
        manager.config().materialView = views[(manager.config().materialView.ordinal() + 1) % views.length];
        manager.saveConfig();
        this.updateButtons();
    }

    private void openStrategy() {
        SessionRuntime runtime = AgentManager.get().focused();
        if (runtime != null) {
            this.client.setScreen(new BlockQueueScreen(this, runtime));
        }
    }

    @Override
    public void tick() {
        super.tick();
        this.updateButtons();
    }

    private void updateButtons() {
        AgentManager manager = AgentManager.get();
        AgentSession session = manager.activeSession();
        BuildAgent agent = manager.activeAgent();
        boolean paused = session != null && session.state == SessionState.PAUSED;
        BuildAgent.DecisionKind decision = paused && agent != null ? agent.pendingDecision() : null;
        boolean awaitingDecision = decision != null;

        this.pauseButton.visible = !paused;
        this.pauseButton.active = session != null;
        this.resumeButton.visible = paused && !awaitingDecision;
        this.cancelButton.visible = !awaitingDecision;
        this.approveButton.visible = awaitingDecision;
        this.rejectButton.visible = awaitingDecision;
        boolean wrongBlock = decision == BuildAgent.DecisionKind.WRONG_BLOCK;
        this.approveButton.setMessage(Chat.tr(wrongBlock ? "lock.approve" : "lock.retry"));
        this.rejectButton.setMessage(Chat.tr(wrongBlock ? "lock.reject" : "lock.skip"));

        boolean blocks = session != null && session.strategy == BuildStrategy.BLOCKS;
        MaterialView view = manager.config().materialView;
        String viewKey = view == MaterialView.CURRENT ? (blocks ? "lock.view_queue" : "lock.view_layer")
                : view == MaterialView.TOTAL ? "lock.view_total" : "lock.view_inventory";
        this.viewButton.setMessage(Chat.tr(viewKey).append(" ⇄"));
        this.strategyButton.active = manager.focused() != null;
        this.strategyButton.setMessage((blocks ? Chat.tr("lock.strategy_blocks", session.blockQueue.size())
                : Chat.tr("lock.strategy_layers")).append(" ▸"));

        WrongBlockMode current = manager.config().wrongBlockMode;
        this.modeButton.setMessage(Chat.tr("lock.mode", modeName(current)).append(" ▼"));
        for (int i = 0; i < this.modeOptions.size(); i++) {
            ButtonWidget option = this.modeOptions.get(i);
            WrongBlockMode mode = WrongBlockMode.values()[i];
            option.visible = this.modeMenuOpen;
            option.setMessage(mode == current
                    ? Text.literal("✔ ").append(modeName(mode)).formatted(Formatting.GREEN)
                    : modeName(mode));
        }
    }

    private void confirmCancel() {
        AgentSession session = AgentManager.get().activeSession();
        BuildAgent agent = AgentManager.get().activeAgent();
        if (session == null) {
            if (agent != null) {
                AgentManager.get().pauseActive();
            }
            this.client.setScreen(null);
            return;
        }
        AgentManager.get().pauseActive();
        this.client.setScreen(new AgentConfirmScreen(confirmed -> {
            if (confirmed) {
                AgentManager.get().cancelNow(session);
                this.client.setScreen(null);
            } else {
                this.client.setScreen(new AgentLockScreen());
            }
        }, Chat.tr("lock.cancel_title"), Chat.tr("lock.cancel_message", session.id, session.name())));
    }

    /**
     * "Leave": the agent stops and the player gets control back.
     */
    @Override
    public void close() {
        AgentSession session = AgentManager.get().activeSession();
        BuildAgent agent = AgentManager.get().activeAgent();
        if (agent != null && (session == null || session.state == SessionState.BUILDING)) {
            AgentManager.get().pauseActive();
        }
        super.close();
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.isEscape()) {
            // Only the panel buttons (Pause/Cancel/Leave) may close this screen, so an accidental
            // ESC press cannot hand control back to the player.
            return true;
        }
        if (input.isEnter()) {
            String text = this.chatField.getText();
            if (!text.isBlank()) {
                this.sendMessage(text, true);
            }
            this.chatField.setText("");
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        // Since 1.21.6 all text of a layer is drawn after all its shapes, so text underneath would shine through the
        // panel. Chat, panel, buttons and the open dropdown therefore each get their own layer.
        context.createNewRootLayer();
        this.renderPanel(context);
        context.createNewRootLayer();
        for (ClickableWidget button : this.panelButtons) {
            button.render(context, mouseX, mouseY, deltaTicks);
        }
        if (this.modeMenuOpen) {
            context.createNewRootLayer();
            for (ButtonWidget option : this.modeOptions) {
                option.render(context, mouseX, mouseY, deltaTicks);
            }
        }
    }

    private void renderPanel(DrawContext context) {
        AgentManager manager = AgentManager.get();
        BuildAgent agent = manager.activeAgent();
        AgentSession session = manager.activeSession();
        int x = this.panelX;
        int y = this.panelY;
        int right = x + PANEL_WIDTH;
        int bottom = y + this.panelHeight;

        context.fill(x, y, right, bottom, COLOR_PANEL);
        context.drawStrokedRectangle(x, y, PANEL_WIDTH, this.panelHeight, COLOR_BORDER);
        int textX = x + PADDING;
        int line = y + PADDING;

        context.drawTextWithShadow(this.textRenderer, Text.literal("LITEMATICA AGENT").formatted(Formatting.BOLD), textX, line, COLOR_TITLE);
        line += 14;

        if (agent == null) {
            context.drawTextWithShadow(this.textRenderer, Chat.tr("lock.idle"), textX, line, COLOR_MUTED);
            return;
        }
        if (session == null) {
            context.drawTextWithShadow(this.textRenderer, Chat.tr("lock.depositing"), textX, line, COLOR_TEXT);
            line += 12;
            this.drawWrapped(context, agent.action(), textX, line, COLOR_MUTED);
            return;
        }

        context.drawTextWithShadow(this.textRenderer, Text.literal("#" + session.id + "  " + session.name()), textX, line, COLOR_TEXT);
        line += 12;

        boolean paused = session.state == SessionState.PAUSED;
        Text status = paused ? Chat.tr("lock.status_paused") : Chat.tr("lock.status_building");
        context.drawTextWithShadow(this.textRenderer, Chat.tr("lock.status", status), textX, line, paused ? COLOR_PAUSED : COLOR_BAR);
        line += 12;
        if (paused && !session.pauseReason.isEmpty()) {
            line = this.drawWrapped(context, Chat.trList(session.pauseReason, session.pauseArgs), textX, line, COLOR_PAUSED);
        }

        int total = Math.max(1, session.totalBlocks);
        int done = session.doneBlocks;
        double percent = done * 100.0 / total;
        context.drawTextWithShadow(this.textRenderer,
                Chat.tr("lock.progress", String.format("%.1f", percent), done, session.totalBlocks), textX, line, COLOR_TEXT);
        line += 11;
        this.drawBar(context, textX, line, PANEL_WIDTH - PADDING * 2, done / (double) total, COLOR_BAR);
        line += 9;

        if (agent.runtime() != null && session.strategy == BuildStrategy.LAYERS) {
            BuildPlan<BuildTarget> plan = agent.runtime().plan();
            int layerY = plan.currentLayerY();
            if (layerY != Integer.MIN_VALUE) {
                int layerIndex = plan.layerIndexOfY(layerY);
                int[] layer = plan.layerProgress(layerIndex);
                context.drawTextWithShadow(this.textRenderer,
                        Chat.tr("lock.layer", layerY, layerIndex + 1, plan.layerCount()), textX, line, COLOR_TEXT);
                line += 11;
                this.drawBar(context, textX, line, PANEL_WIDTH - PADDING * 2,
                        layer[1] == 0 ? 0.0 : layer[0] / (double) layer[1], COLOR_LAYER_BAR);
                line += 9;
            }
        }

        Text eta = agent.hasEtaMeasurement()
                ? Chat.tr("lock.eta", agent.remainingMinutes())
                : Chat.tr("lock.eta_estimate", agent.remainingMinutes());
        context.drawTextWithShadow(this.textRenderer, eta, textX, line, COLOR_TEXT);
        line += 12;

        context.drawTextWithShadow(this.textRenderer, Chat.tr("lock.action"), textX, line, COLOR_MUTED);
        line += 10;
        line = this.drawWrapped(context, paused ? Chat.tr("lock.waiting_for_player") : agent.action(), textX, line, COLOR_TEXT);

        this.drawMaterials(context, textX, line + 2, agent.runtime(), session);
    }

    /**
     * The item row: what the current layer (or the queued block types) still needs, what the whole schematic still
     * needs, or what is in the inventory, depending on the view button.
     */
    private void drawMaterials(DrawContext context, int x, int y, @Nullable SessionRuntime runtime, AgentSession session) {
        MaterialView view = AgentManager.get().config().materialView;
        List<Map.Entry<Item, Integer>> entries;
        if (view == MaterialView.INVENTORY) {
            entries = this.inventoryMaterials();
        } else if (runtime == null) {
            return;
        } else if (view == MaterialView.TOTAL) {
            entries = sortedByCount(runtime.remainingMaterials());
        } else if (session.strategy == BuildStrategy.BLOCKS) {
            Map<Item, Integer> queued = new LinkedHashMap<>();
            for (String id : session.blockQueue) {
                Item item = Registries.ITEM.get(Identifier.tryParse(id));
                int remaining = runtime.remainingMaterials().getOrDefault(item, 0);
                if (remaining > 0) {
                    queued.put(item, remaining);
                }
            }
            entries = new ArrayList<>(queued.entrySet());
        } else {
            BuildPlan<BuildTarget> plan = runtime.plan();
            int layerY = plan.currentLayerY();
            entries = layerY == Integer.MIN_VALUE ? List.of() : sortedByCount(runtime.remainingInLayer(plan.layerIndexOfY(layerY)));
        }

        if (view != MaterialView.INVENTORY) {
            if (y + 9 > this.contentBottom) {
                return;
            }
            int sum = 0;
            for (Map.Entry<Item, Integer> entry : entries) {
                sum += entry.getValue();
            }
            context.drawTextWithShadow(this.textRenderer, Chat.tr("lock.remaining_blocks", sum), x, y, COLOR_MUTED);
            y += 11;
        }
        // Every entry is as wide as its icon and count, so a long number never runs into the next icon. Rows continue
        // below as long as there is room above the buttons.
        int right = x + PANEL_WIDTH - PADDING * 2;
        int itemX = x;
        for (Map.Entry<Item, Integer> entry : entries) {
            String count = compact(entry.getValue());
            int width = 16 + ITEM_TEXT_GAP + this.textRenderer.getWidth(count);
            if (itemX > x && itemX + width > right) {
                itemX = x;
                y += ITEM_ROW_HEIGHT;
            }
            if (y + 16 > this.contentBottom) {
                break;
            }
            context.drawItem(new ItemStack(entry.getKey()), itemX, y);
            context.drawTextWithShadow(this.textRenderer, Text.literal(count), itemX + 16 + ITEM_TEXT_GAP, y + 4, COLOR_TEXT);
            itemX += width + ITEM_GAP;
        }
    }

    private List<Map.Entry<Item, Integer>> inventoryMaterials() {
        ClientPlayerEntity player = this.client.player;
        if (player == null || player.isInCreativeMode()) {
            return List.of();
        }
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < InventoryHelper.MAIN_SIZE; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && !InventoryHelper.isTool(stack) && !InventoryHelper.isFood(stack)) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return sortedByCount(counts);
    }

    private static List<Map.Entry<Item, Integer>> sortedByCount(Map<Item, Integer> counts) {
        List<Map.Entry<Item, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return entries;
    }

    /** Exact counts up to 99999, shortened beyond that. */
    static String compact(int count) {
        if (count >= 1_000_000) {
            return (count / 1_000_000) + "M";
        }
        return count >= 100_000 ? (count / 1000) + "k" : String.valueOf(count);
    }

    /** Draws wrapped text; lines that would reach into the buttons are left out. */
    private int drawWrapped(DrawContext context, Text text, int x, int y, int color) {
        for (OrderedText line : this.textRenderer.wrapLines(text, PANEL_WIDTH - PADDING * 2)) {
            if (y + 9 > this.contentBottom) {
                break;
            }
            context.drawTextWithShadow(this.textRenderer, line, x, y, color);
            y += 10;
        }
        return y;
    }

    private void drawBar(DrawContext context, int x, int y, int width, double fraction, int color) {
        context.fill(x, y, x + width, y + 5, COLOR_BAR_BG);
        int filled = (int) Math.round(width * Math.max(0.0, Math.min(1.0, fraction)));
        if (filled > 0) {
            context.fill(x, y, x + filled, y + 5, color);
        }
    }
}

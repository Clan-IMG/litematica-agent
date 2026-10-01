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
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

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
    private static final int SCROLLBAR_WIDTH = 5;
    private static final int SCROLL_STEP = 30;
    /** Space between the icon and its count, and between two entries of the item row. */
    private static final int ITEM_TEXT_GAP = 2;
    private static final int ITEM_GAP = 8;
    private static final int ITEM_ROW_HEIGHT = 18;
    /** Opaque enough that chat lines behind the panel (small windows) do not show through the text. */
    private static final int COLOR_PANEL = 0xD8101418;
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
    private int panelWidth;
    private int panelHeight;
    private int contentTop;
    private int contentBottom;
    private int contentHeight;
    private double scrollOffset;
    private double scrollbarGrabOffset;
    private boolean draggingScrollbar;
    private final List<PanelRow> contentRows = new ArrayList<>();
    /** Bounded cache: repeated long instructions are wrapped only when their text or the viewport width changes. */
    private final Map<Text, List<OrderedText>> wrappedTexts = new LinkedHashMap<>();
    private @Nullable Text displayedPauseReason;

    private interface PanelRow {
        int y();
        int height();
        void draw(AgentLockScreen screen, DrawContext context, int x, int y);
    }

    private record TextRow(int y, OrderedText text, int color) implements PanelRow {
        @Override public int height() { return 10; }
        @Override public void draw(AgentLockScreen screen, DrawContext context, int x, int y) {
            context.drawTextWithShadow(screen.textRenderer, this.text, x, y, this.color);
        }
    }

    private record BarRow(int y, double fraction, int color) implements PanelRow {
        @Override public int height() { return 5; }
        @Override public void draw(AgentLockScreen screen, DrawContext context, int x, int y) {
            screen.drawBar(context, x, y, screen.contentWidth(), this.fraction, this.color);
        }
    }

    private record ItemRow(int xOffset, int y, ItemStack stack, String count) implements PanelRow {
        @Override public int height() { return ITEM_ROW_HEIGHT; }
        @Override public void draw(AgentLockScreen screen, DrawContext context, int x, int y) {
            context.drawItem(this.stack, x + this.xOffset, y);
            context.drawTextWithShadow(screen.textRenderer, this.count,
                    x + this.xOffset + 16 + ITEM_TEXT_GAP, y + 4, COLOR_TEXT);
        }
    }

    public AgentLockScreen() {
        super("", false);
    }

    @Override
    protected void init() {
        super.init();
        this.panelWidth = Math.min(PANEL_WIDTH, this.width - 12);
        this.panelX = this.width - this.panelWidth - 6;
        this.panelY = 6;
        // Leave the chat entry field visible and keep all controls anchored inside the window.
        this.panelHeight = Math.min(MAX_PANEL_HEIGHT, this.height - 28);
        int bottom = this.panelY + this.panelHeight;
        int toolsY = bottom - 98;
        this.contentTop = this.panelY + PADDING + 14;
        this.contentBottom = toolsY - 4;
        int buttonY = bottom - 74;
        int speedY = bottom - 50;
        int modeY = bottom - 26;
        int fullWidth = this.panelWidth - PADDING * 2;
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
        this.wrappedTexts.clear();
        this.draggingScrollbar = false;
        this.updateButtons();
        this.updateContent();
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
        this.updateContent();
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
        Text strategyText = session == null ? Chat.tr("lock.strategy_layers") : switch (session.strategy) {
            case LAYERS_TOP_DOWN -> Chat.tr("lock.strategy_top_down");
            case PROXIMITY -> Chat.tr("lock.strategy_proximity");
            default -> Chat.tr("lock.strategy_layers");
        };
        if (session != null && !session.blockQueue.isEmpty()) {
            strategyText = strategyText.copy().append(" ▪" + session.blockQueue.size());
        }
        this.strategyButton.setMessage(strategyText.copy().append(" ▸"));

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
        if (input.getKeycode() == InputUtil.GLFW_KEY_F4) {
            // Let the player watch in freecam while the agent keeps building. This does not pause anything -
            // AgentManager brings the panel back once the same key toggles freecam off again.
            AgentManager.get().allowFreecam();
            this.client.setScreen(null);
            return true;
        }
        // Page keys scroll instructions even while chat owns focus. Plain Home/End retain chat caret behavior.
        int key = input.getKeycode();
        if (key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN) {
            this.scrollBy((key == GLFW.GLFW_KEY_PAGE_UP ? -1 : 1) * Math.max(10, this.viewportHeight() - 10));
            return true;
        }
        if ((input.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0
                && (key == GLFW.GLFW_KEY_HOME || key == GLFW.GLFW_KEY_END)) {
            this.scrollOffset = key == GLFW.GLFW_KEY_HOME ? 0 : this.maxScroll();
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
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (this.isInPanel(mouseX, mouseY)) {
            this.scrollBy(-verticalAmount * SCROLL_STEP);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (!this.modeMenuOpen && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && this.maxScroll() > 0
                && click.x() >= this.scrollbarX() - 2 && click.x() < this.panelX + this.panelWidth - 2
                && click.y() >= this.contentTop && click.y() < this.contentBottom) {
            int thumbY = this.scrollbarThumbY();
            this.scrollbarGrabOffset = click.y() >= thumbY && click.y() < thumbY + this.scrollbarThumbHeight()
                    ? click.y() - thumbY : this.scrollbarThumbHeight() / 2.0;
            this.draggingScrollbar = true;
            this.dragScrollbar(click.y());
            return true;
        }
        // ChatScreen handles chat links before child widgets. Panel controls must win over links hidden behind it.
        if (this.modeMenuOpen) {
            for (ButtonWidget option : this.modeOptions) {
                if (this.clickPanelWidget(option, click, doubled)) {
                    return true;
                }
            }
        }
        if (this.isInPanel(click.x(), click.y())) {
            for (ClickableWidget widget : this.panelButtons) {
                if (this.clickPanelWidget(widget, click, doubled)) {
                    return true;
                }
            }
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    private boolean clickPanelWidget(ClickableWidget widget, Click click, boolean doubled) {
        if (widget.mouseClicked(click, doubled)) {
            this.setFocused(widget);
            if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                this.setDragging(true);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        if (this.draggingScrollbar && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            this.dragScrollbar(click.y());
            return true;
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (this.draggingScrollbar && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            this.draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(click);
    }

    private boolean isInPanel(double x, double y) {
        return x >= this.panelX && x < this.panelX + this.panelWidth
                && y >= this.panelY && y < this.panelY + this.panelHeight;
    }

    private int contentWidth() {
        return Math.max(1, this.panelWidth - PADDING * 2 - SCROLLBAR_WIDTH - 3);
    }

    private int viewportHeight() {
        return Math.max(1, this.contentBottom - this.contentTop);
    }

    private int maxScroll() {
        return Math.max(0, this.contentHeight - this.viewportHeight());
    }

    private void scrollBy(double amount) {
        this.scrollOffset = Math.max(0, Math.min(this.maxScroll(), this.scrollOffset + amount));
    }

    private int scrollbarX() {
        return this.panelX + this.panelWidth - PADDING - SCROLLBAR_WIDTH;
    }

    private int scrollbarThumbHeight() {
        return Math.min(this.viewportHeight(), Math.max(12,
                (int) ((long) this.viewportHeight() * this.viewportHeight() / Math.max(1, this.contentHeight))));
    }

    private int scrollbarThumbY() {
        int travel = this.viewportHeight() - this.scrollbarThumbHeight();
        return this.contentTop + (int) Math.round(travel * this.scrollOffset / Math.max(1, this.maxScroll()));
    }

    private void dragScrollbar(double mouseY) {
        int travel = this.viewportHeight() - this.scrollbarThumbHeight();
        if (travel > 0) {
            this.scrollOffset = Math.max(0, Math.min(this.maxScroll(),
                    (mouseY - this.contentTop - this.scrollbarGrabOffset) * this.maxScroll() / travel));
        }
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
        int x = this.panelX;
        int y = this.panelY;
        context.fill(x, y, x + this.panelWidth, y + this.panelHeight, COLOR_PANEL);
        context.drawStrokedRectangle(x, y, this.panelWidth, this.panelHeight, COLOR_BORDER);
        context.drawTextWithShadow(this.textRenderer, Text.literal("LITEMATICA AGENT").formatted(Formatting.BOLD),
                x + PADDING, y + PADDING, COLOR_TITLE);

        // Clip every primitive (including item icons and bars), not just wrapped text. Layout always keeps all rows.
        context.enableScissor(x + PADDING, this.contentTop, this.scrollbarX() - 3, this.contentBottom);
        int offset = (int) this.scrollOffset;
        for (PanelRow row : this.contentRows) {
            int rowY = this.contentTop + row.y() - offset;
            if (rowY + row.height() > this.contentTop && rowY < this.contentBottom) {
                row.draw(this, context, x + PADDING, rowY);
            }
        }
        context.disableScissor();
        if (this.maxScroll() > 0) {
            int scrollX = this.scrollbarX();
            context.fill(scrollX, this.contentTop, scrollX + SCROLLBAR_WIDTH, this.contentBottom, COLOR_BAR_BG);
            int thumbY = this.scrollbarThumbY();
            context.fill(scrollX, thumbY, scrollX + SCROLLBAR_WIDTH, thumbY + this.scrollbarThumbHeight(), COLOR_BORDER);
        }
    }

    /** A snapshot per game tick, independent of render FPS, keeps long instructions inexpensive to display. */
    private void updateContent() {
        this.contentRows.clear();
        this.contentHeight = this.layoutContent();
        this.scrollBy(0); // Clamp after resize or after a long message/material list disappears.
    }

    private int layoutContent() {
        AgentManager manager = AgentManager.get();
        BuildAgent agent = manager.activeAgent();
        AgentSession session = manager.activeSession();
        int line = 0;
        if (agent == null) {
            return this.addWrapped(Chat.tr("lock.idle"), line, COLOR_MUTED);
        }
        if (session == null) {
            line = this.addWrapped(Chat.tr("lock.depositing"), line, COLOR_TEXT) + 2;
            return this.addWrapped(agent.action(), line, COLOR_MUTED);
        }
        line = this.addWrapped(Text.literal("#" + session.id + "  " + session.name()), line, COLOR_TEXT) + 2;
        boolean paused = session.state == SessionState.PAUSED;
        Text status = paused ? Chat.tr("lock.status_paused") : Chat.tr("lock.status_building");
        line = this.addWrapped(Chat.tr("lock.status", status), line, paused ? COLOR_PAUSED : COLOR_BAR) + 2;
        Text pauseReason = paused && !session.pauseReason.isEmpty()
                ? Chat.trList(session.pauseReason, session.pauseArgs) : null;
        if (pauseReason != null) {
            if (!pauseReason.equals(this.displayedPauseReason)) {
                this.scrollOffset = 0; // A new problem must be visible, even after reading a previous message's end.
            }
            line = this.addWrapped(pauseReason, line, COLOR_PAUSED) + 2;
        }
        this.displayedPauseReason = pauseReason;

        int total = Math.max(1, session.totalBlocks);
        int done = session.doneBlocks;
        line = this.addWrapped(Chat.tr("lock.progress", String.format("%.1f", done * 100.0 / total),
                done, session.totalBlocks), line, COLOR_TEXT) + 1;
        this.contentRows.add(new BarRow(line, done / (double) total, COLOR_BAR));
        line += 9;
        if (agent.runtime() != null && session.strategy != BuildStrategy.BLOCKS && session.strategy != BuildStrategy.PROXIMITY) {
            BuildPlan<BuildTarget> plan = agent.runtime().plan();
            int layerY = plan.currentLayerY();
            if (layerY != Integer.MIN_VALUE) {
                int layerIndex = plan.layerIndexOfY(layerY);
                int[] layer = plan.layerProgress(layerIndex);
                line = this.addWrapped(Chat.tr("lock.layer", layerY, layerIndex + 1, plan.layerCount()), line, COLOR_TEXT) + 1;
                this.contentRows.add(new BarRow(line,
                        layer[1] == 0 ? 0.0 : layer[0] / (double) layer[1], COLOR_LAYER_BAR));
                line += 9;
            }
        }
        Text eta = agent.hasEtaMeasurement()
                ? Chat.tr("lock.eta", agent.remainingMinutes())
                : Chat.tr("lock.eta_estimate", agent.remainingMinutes());
        line = this.addWrapped(eta, line, COLOR_TEXT) + 2;
        line = this.addWrapped(Chat.tr("lock.action"), line, COLOR_MUTED);
        line = this.addWrapped(paused ? Chat.tr("lock.waiting_for_player") : agent.action(), line, COLOR_TEXT);
        return this.addMaterials(line + 2, agent.runtime(), session);
    }

    /** Lay out every material; the viewport decides which rows to draw. */
    private int addMaterials(int y, @Nullable SessionRuntime runtime, AgentSession session) {
        MaterialView view = AgentManager.get().config().materialView;
        List<Map.Entry<Item, Integer>> entries;
        if (view == MaterialView.INVENTORY) {
            entries = this.inventoryMaterials();
        } else if (runtime == null) {
            return y;
        } else if (view == MaterialView.TOTAL) {
            entries = sortedByCount(runtime.remainingMaterials());
        } else if (session.strategy == BuildStrategy.BLOCKS || !session.blockQueue.isEmpty()) {
            Map<Item, Integer> queued = new LinkedHashMap<>();
            Map<Item, Integer> remainingMaterials = runtime.remainingMaterials();
            for (String id : session.blockQueue) {
                Identifier identifier = Identifier.tryParse(id);
                if (identifier == null) {
                    continue;
                }
                Item item = Registries.ITEM.get(identifier);
                int remaining = remainingMaterials.getOrDefault(item, 0);
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
            int sum = 0;
            for (Map.Entry<Item, Integer> entry : entries) {
                sum += entry.getValue();
            }
            y = this.addWrapped(Chat.tr("lock.remaining_blocks", sum), y, COLOR_MUTED) + 1;
        }
        int itemX = 0;
        for (Map.Entry<Item, Integer> entry : entries) {
            String count = compact(entry.getValue());
            int width = 16 + ITEM_TEXT_GAP + this.textRenderer.getWidth(count);
            if (itemX > 0 && itemX + width > this.contentWidth()) {
                itemX = 0;
                y += ITEM_ROW_HEIGHT;
            }
            this.contentRows.add(new ItemRow(itemX, y, new ItemStack(entry.getKey()), count));
            itemX += width + ITEM_GAP;
        }
        return y + (entries.isEmpty() ? 0 : ITEM_ROW_HEIGHT);
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

    private int addWrapped(Text text, int y, int color) {
        List<OrderedText> lines = this.wrappedTexts.get(text);
        if (lines == null) {
            lines = this.textRenderer.wrapLines(text, this.contentWidth());
            if (this.wrappedTexts.size() >= 32) {
                this.wrappedTexts.remove(this.wrappedTexts.keySet().iterator().next());
            }
            this.wrappedTexts.put(text.copy(), lines);
        }
        for (OrderedText line : lines) {
            this.contentRows.add(new TextRow(y, line, color));
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

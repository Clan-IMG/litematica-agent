package net.clanimg.litematica_agent.gui;

import net.clanimg.litematica_agent.agent.AgentManager;
import net.clanimg.litematica_agent.agent.AgentSession;
import net.clanimg.litematica_agent.agent.BuildAgent;
import net.clanimg.litematica_agent.agent.SessionState;
import net.clanimg.litematica_agent.inventory.InventoryHelper;
import net.clanimg.litematica_agent.planning.BuildPlan;
import net.clanimg.litematica_agent.schematic.BuildTarget;
import net.clanimg.litematica_agent.ui.Chat;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shown while the agent is in control. Keyboard and mouse only reach this screen, so the player cannot disturb the
 * agent by accident, while the chat stays usable. The world stays visible behind it.
 */
public final class AgentLockScreen extends ChatScreen implements AgentScreen {
    private static final int PANEL_WIDTH = 230;
    private static final int PADDING = 8;
    private static final int COLOR_PANEL = 0xB0101418;
    private static final int COLOR_BORDER = 0xFF2DD4BF;
    private static final int COLOR_TEXT = 0xFFE5E7EB;
    private static final int COLOR_MUTED = 0xFF9CA3AF;
    private static final int COLOR_TITLE = 0xFF5EEAD4;
    private static final int COLOR_BAR_BG = 0xFF374151;
    private static final int COLOR_BAR = 0xFF22C55E;
    private static final int COLOR_LAYER_BAR = 0xFF38BDF8;
    private static final int COLOR_PAUSED = 0xFFFBBF24;

    private final List<ButtonWidget> panelButtons = new ArrayList<>();
    private @Nullable ButtonWidget pauseButton;
    private @Nullable ButtonWidget resumeButton;
    private int panelX;
    private int panelY;
    private int panelHeight;

    public AgentLockScreen() {
        super("", false);
    }

    @Override
    protected void init() {
        super.init();
        this.panelX = this.width - PANEL_WIDTH - 6;
        this.panelY = 6;
        this.panelHeight = 200;
        int buttonY = this.panelY + this.panelHeight - 26;
        int buttonWidth = (PANEL_WIDTH - PADDING * 2 - 8) / 3;
        int x = this.panelX + PADDING;

        this.pauseButton = this.addDrawableChild(ButtonWidget.builder(Chat.tr("lock.pause"), button -> AgentManager.get().pauseActive())
                .dimensions(x, buttonY, buttonWidth, 20).build());
        this.resumeButton = this.addDrawableChild(ButtonWidget.builder(Chat.tr("lock.resume"), button -> {
                    AgentSession session = AgentManager.get().activeSession();
                    if (session != null) {
                        AgentManager.get().resume(session.id);
                    }
                })
                .dimensions(x, buttonY, buttonWidth, 20).build());
        ButtonWidget cancel = this.addDrawableChild(ButtonWidget.builder(Chat.tr("lock.cancel"), button -> this.confirmCancel())
                .dimensions(x + buttonWidth + 4, buttonY, buttonWidth, 20).build());
        ButtonWidget leave = this.addDrawableChild(ButtonWidget.builder(Chat.tr("lock.leave"), button -> this.close())
                .dimensions(x + (buttonWidth + 4) * 2, buttonY, buttonWidth, 20).build());
        this.panelButtons.clear();
        this.panelButtons.addAll(List.of(this.pauseButton, this.resumeButton, cancel, leave));
        this.updateButtons();
    }

    @Override
    public void tick() {
        super.tick();
        this.updateButtons();
    }

    private void updateButtons() {
        AgentSession session = AgentManager.get().activeSession();
        boolean paused = session != null && session.state == SessionState.PAUSED;
        if (this.pauseButton != null) {
            this.pauseButton.visible = !paused;
            this.pauseButton.active = session != null;
        }
        if (this.resumeButton != null) {
            this.resumeButton.visible = paused;
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
     * ESC or "leave": the agent stops and the player gets control back.
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
        // The panel goes over the chat, and the panel buttons over the panel.
        this.renderPanel(context);
        for (ButtonWidget button : this.panelButtons) {
            button.render(context, mouseX, mouseY, deltaTicks);
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

        if (agent.runtime() != null) {
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

        this.drawInventory(context, textX, line + 2);

        context.drawTextWithShadow(this.textRenderer, paused ? Chat.tr("lock.hint_paused") : Chat.tr("lock.hint_running"),
                textX, bottom + 4, COLOR_MUTED);
    }

    private void drawInventory(DrawContext context, int x, int y) {
        ClientPlayerEntity player = this.client.player;
        if (player == null || player.isInCreativeMode()) {
            return;
        }
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < InventoryHelper.MAIN_SIZE; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!stack.isEmpty() && !InventoryHelper.isTool(stack) && !InventoryHelper.isFood(stack)) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        List<Map.Entry<Item, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        int column = 0;
        for (Map.Entry<Item, Integer> entry : entries) {
            if (column >= 6) {
                break;
            }
            int itemX = x + column * 36;
            ItemStack stack = new ItemStack(entry.getKey());
            context.drawItem(stack, itemX, y);
            context.drawTextWithShadow(this.textRenderer, Text.literal(String.valueOf(entry.getValue())), itemX + 17, y + 5, COLOR_TEXT);
            column++;
        }
    }

    private int drawWrapped(DrawContext context, Text text, int x, int y, int color) {
        for (OrderedText line : this.textRenderer.wrapLines(text, PANEL_WIDTH - PADDING * 2)) {
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

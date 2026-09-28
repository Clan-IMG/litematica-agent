package net.clanimg.litematica_agent.gui;

import net.clanimg.litematica_agent.agent.AgentManager;
import net.clanimg.litematica_agent.agent.StockAgent;
import net.clanimg.litematica_agent.ui.Chat;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;

/**
 * Shown while the chest-stocking test helper is running. Same idea as {@link AgentLockScreen}: only the panel
 * buttons can close it, ESC does nothing.
 */
public final class StockLockScreen extends ChatScreen implements AgentScreen {
    private static final int PANEL_WIDTH = 230;
    private static final int PADDING = 8;
    private static final int PANEL_HEIGHT = 118;
    private static final int COLOR_PANEL = 0xB0101418;
    private static final int COLOR_BORDER = 0xFF2DD4BF;
    private static final int COLOR_TEXT = 0xFFE5E7EB;
    private static final int COLOR_MUTED = 0xFF9CA3AF;
    private static final int COLOR_TITLE = 0xFF5EEAD4;
    private static final int COLOR_BAR_BG = 0xFF374151;
    private static final int COLOR_BAR = 0xFF22C55E;

    private @Nullable ButtonWidget cancelButton;
    private @Nullable ConfigSlider speedSlider;
    private int panelX;
    private int panelY;

    public StockLockScreen() {
        super("", false);
    }

    @Override
    protected void init() {
        super.init();
        this.panelX = this.width - PANEL_WIDTH - 6;
        this.panelY = 6;
        int buttonY = this.panelY + PANEL_HEIGHT - 26;
        this.speedSlider = this.addSelectableChild(ConfigSlider.speed(this.panelX + PADDING, buttonY - 24, PANEL_WIDTH - PADDING * 2));
        this.cancelButton = this.addSelectableChild(ButtonWidget.builder(Chat.tr("stock.cancel"),
                        button -> AgentManager.get().cancelStocking())
                .dimensions(this.panelX + PADDING, buttonY, PANEL_WIDTH - PADDING * 2, 20).build());
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.isEscape()) {
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        // Own layers so chat text cannot shine through the panel (see AgentLockScreen#render).
        context.createNewRootLayer();
        this.renderPanel(context);
        context.createNewRootLayer();
        if (this.speedSlider != null) {
            this.speedSlider.render(context, mouseX, mouseY, deltaTicks);
        }
        if (this.cancelButton != null) {
            this.cancelButton.render(context, mouseX, mouseY, deltaTicks);
        }
    }

    private void renderPanel(DrawContext context) {
        StockAgent job = AgentManager.get().stockAgent();
        int x = this.panelX;
        int y = this.panelY;
        int right = x + PANEL_WIDTH;
        int bottom = y + PANEL_HEIGHT;

        context.fill(x, y, right, bottom, COLOR_PANEL);
        context.drawStrokedRectangle(x, y, PANEL_WIDTH, PANEL_HEIGHT, COLOR_BORDER);
        int textX = x + PADDING;
        int line = y + PADDING;

        context.drawTextWithShadow(this.textRenderer, Chat.tr("stock.title").formatted(Formatting.BOLD), textX, line, COLOR_TITLE);
        line += 14;

        if (job == null) {
            context.drawTextWithShadow(this.textRenderer, Chat.tr("lock.idle"), textX, line, COLOR_MUTED);
            return;
        }

        context.drawTextWithShadow(this.textRenderer,
                Chat.tr("stock.level_progress", job.currentLevel(), job.totalLevels()), textX, line, COLOR_TEXT);
        line += 12;
        this.drawBar(context, textX, line, PANEL_WIDTH - PADDING * 2, job.progress(), COLOR_BAR);
        line += 10;

        for (var wrapped : this.textRenderer.wrapLines(job.action(), PANEL_WIDTH - PADDING * 2)) {
            context.drawTextWithShadow(this.textRenderer, wrapped, textX, line, COLOR_MUTED);
            line += 10;
        }
    }

    private void drawBar(DrawContext context, int x, int y, int width, double fraction, int color) {
        context.fill(x, y, x + width, y + 5, COLOR_BAR_BG);
        int filled = (int) Math.round(width * Math.max(0.0, Math.min(1.0, fraction)));
        if (filled > 0) {
            context.fill(x, y, x + filled, y + 5, color);
        }
    }
}

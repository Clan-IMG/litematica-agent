package net.clanimg.litematica_agent.gui;

import net.clanimg.litematica_agent.agent.AgentManager;
import net.clanimg.litematica_agent.agent.StockAgent;
import net.clanimg.litematica_agent.ui.Chat;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Shown while the chest-stocking test helper is running. Same idea as {@link AgentLockScreen}: keyboard and mouse only
 * reach this screen, only the panel buttons close it, ESC does nothing. The job can be paused, continued, left (paused,
 * with the controls back) or cancelled.
 */
public final class StockLockScreen extends ChatScreen implements AgentScreen {
    private static final int PANEL_WIDTH = 230;
    private static final int PADDING = 8;
    private static final int PANEL_HEIGHT = 138;
    private static final int COLOR_PANEL = 0xB0101418;
    private static final int COLOR_BORDER = 0xFF2DD4BF;
    private static final int COLOR_TEXT = 0xFFE5E7EB;
    private static final int COLOR_MUTED = 0xFF9CA3AF;
    private static final int COLOR_TITLE = 0xFF5EEAD4;
    private static final int COLOR_BAR_BG = 0xFF374151;
    private static final int COLOR_BAR = 0xFF22C55E;
    private static final int COLOR_PAUSED = 0xFFFBBF24;

    private final List<ClickableWidget> panelButtons = new ArrayList<>();
    private ButtonWidget pauseButton;
    private ButtonWidget resumeButton;
    private int panelX;
    private int panelY;
    /** Texts of the panel end here, above the slider. */
    private int contentBottom;

    public StockLockScreen() {
        super("", false);
    }

    @Override
    protected void init() {
        super.init();
        this.panelX = this.width - PANEL_WIDTH - 6;
        this.panelY = 6;
        int fullWidth = PANEL_WIDTH - PADDING * 2;
        int buttonWidth = (fullWidth - 8) / 3;
        int x = this.panelX + PADDING;
        int buttonY = this.panelY + PANEL_HEIGHT - 26;
        int sliderY = buttonY - 24;
        this.contentBottom = sliderY - 4;

        ConfigSlider speed = this.addSelectableChild(ConfigSlider.speed(x, sliderY, fullWidth));
        this.pauseButton = this.addSelectableChild(ButtonWidget.builder(Chat.tr("lock.pause"),
                        button -> AgentManager.get().pauseStocking())
                .dimensions(x, buttonY, buttonWidth, 20).build());
        this.resumeButton = this.addSelectableChild(ButtonWidget.builder(Chat.tr("lock.resume"),
                        button -> AgentManager.get().resumeStocking())
                .dimensions(x, buttonY, buttonWidth, 20).build());
        ButtonWidget cancel = this.addSelectableChild(ButtonWidget.builder(Chat.tr("stock.cancel"),
                        button -> AgentManager.get().cancelStocking())
                .dimensions(x + buttonWidth + 4, buttonY, buttonWidth, 20).build());
        ButtonWidget leave = this.addSelectableChild(ButtonWidget.builder(Chat.tr("lock.leave"),
                        button -> AgentManager.get().leaveStocking())
                .dimensions(x + (buttonWidth + 4) * 2, buttonY, buttonWidth, 20).build());

        this.panelButtons.clear();
        this.panelButtons.addAll(List.of(speed, this.pauseButton, this.resumeButton, cancel, leave));
        this.updateButtons();
    }

    @Override
    public void tick() {
        super.tick();
        this.updateButtons();
    }

    private void updateButtons() {
        StockAgent job = AgentManager.get().stockAgent();
        boolean paused = job != null && job.isPaused();
        this.pauseButton.visible = !paused;
        this.pauseButton.active = job != null;
        this.resumeButton.visible = paused;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.isEscape()) {
            // Only the panel buttons may close this screen, so an accidental ESC press cannot disturb the job.
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
        for (ClickableWidget button : this.panelButtons) {
            button.render(context, mouseX, mouseY, deltaTicks);
        }
    }

    private void renderPanel(DrawContext context) {
        StockAgent job = AgentManager.get().stockAgent();
        int x = this.panelX;
        int y = this.panelY;

        context.fill(x, y, x + PANEL_WIDTH, y + PANEL_HEIGHT, COLOR_PANEL);
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
        this.drawBar(context, textX, line, PANEL_WIDTH - PADDING * 2, job.progress());
        line += 10;

        if (job.isPaused()) {
            context.drawTextWithShadow(this.textRenderer, Chat.tr("lock.status", Chat.tr("lock.status_paused")), textX, line, COLOR_PAUSED);
            line += 12;
            if (!job.pauseReason().isEmpty()) {
                this.drawWrapped(context, AgentManager.reasonText(job.pauseReason()), textX, line, COLOR_PAUSED);
            }
            return;
        }
        this.drawWrapped(context, job.action(), textX, line, COLOR_MUTED);
    }

    /** Draws wrapped text; lines that would reach into the slider are left out. */
    private void drawWrapped(DrawContext context, Text text, int x, int y, int color) {
        for (OrderedText wrapped : this.textRenderer.wrapLines(text, PANEL_WIDTH - PADDING * 2)) {
            if (y + 9 > this.contentBottom) {
                break;
            }
            context.drawTextWithShadow(this.textRenderer, wrapped, x, y, color);
            y += 10;
        }
    }

    private void drawBar(DrawContext context, int x, int y, int width, double fraction) {
        context.fill(x, y, x + width, y + 5, COLOR_BAR_BG);
        int filled = (int) Math.round(width * Math.max(0.0, Math.min(1.0, fraction)));
        if (filled > 0) {
            context.fill(x, y, x + filled, y + 5, COLOR_BAR);
        }
    }
}

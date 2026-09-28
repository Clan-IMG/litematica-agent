package net.clanimg.litematica_agent.gui;

import net.clanimg.litematica_agent.agent.AgentManager;
import net.clanimg.litematica_agent.agent.AgentSession;
import net.clanimg.litematica_agent.agent.SessionRuntime;
import net.clanimg.litematica_agent.agent.SessionState;
import net.clanimg.litematica_agent.inventory.InventoryHelper;
import net.clanimg.litematica_agent.ui.Chat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Material list in the bottom-left corner while a survival session is being prepared, and a grey overlay on hotbar
 * tools that the agent no longer uses because they are about to break.
 */
public final class AgentHud {
    private static final int ROW_HEIGHT = 18;
    private static final int MAX_ROWS = 12;
    private static final int COLOR_BG = 0x90000000;
    private static final int COLOR_FOUND = 0xFF4ADE80;
    private static final int COLOR_MISSING = 0xFFA3A3A3;
    private static final int COLOR_HEADER = 0xFF5EEAD4;
    private static final int COLOR_WARN = 0xFFF87171;
    private static final int COLOR_WORN = 0xB0404040;
    private static final long FLASH_MILLIS = 1200L;

    private final Map<String, Long> completedAt = new HashMap<>();

    public void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden || client.player == null) {
            return;
        }
        AgentManager manager = AgentManager.get();
        if (manager.config().showMaterialHud && manager.isPreparing()) {
            this.renderMaterials(context, client, manager);
        }
        if (manager.activeAgent() != null || manager.isPreparing()) {
            this.renderWornTools(context, client, manager);
        }
    }

    private void renderMaterials(DrawContext context, MinecraftClient client, AgentManager manager) {
        SessionRuntime runtime = manager.focused();
        if (runtime == null) {
            return;
        }
        AgentSession session = runtime.session();
        TextRenderer font = client.textRenderer;
        List<AgentManager.MaterialStatus> statuses = manager.materialStatus();
        int complete = (int) statuses.stream().filter(AgentManager.MaterialStatus::complete).count();

        Text header;
        int headerColor = COLOR_HEADER;
        if (session.state == SessionState.CHECK_INVENTORY) {
            header = Chat.tr("hud.empty_inventory");
            headerColor = COLOR_WARN;
        } else {
            header = Chat.tr("hud.materials", complete, statuses.size());
        }

        // Bottom left, directly above the chat so neither hides the other.
        double chatScale = client.options.getChatScale().getValue();
        int chatHeight = (int) Math.ceil(ChatHud.getHeight(client.options.getChatHeightUnfocused().getValue()) * chatScale);
        int bottom = context.getScaledWindowHeight() - 40 - chatHeight - 4;
        int maxRows = Math.max(1, Math.min(MAX_ROWS, (bottom - 4 - 14 - 10) / ROW_HEIGHT));
        int rows = Math.min(maxRows, statuses.size());
        boolean more = statuses.size() > rows;
        int width = 190;
        int height = 14 + rows * ROW_HEIGHT + (more ? 10 : 0) + 4;
        int x = 4;
        int y = Math.max(2, bottom - height);

        context.fill(x, y, x + width, y + height, COLOR_BG);
        context.drawTextWithShadow(font, header, x + 4, y + 4, headerColor);

        long now = System.currentTimeMillis();
        int rowY = y + 16;
        for (int i = 0; i < rows; i++) {
            AgentManager.MaterialStatus status = statuses.get(i);
            String key = status.item().toString();
            int color = COLOR_MISSING;
            if (status.complete()) {
                color = COLOR_FOUND;
                Long since = this.completedAt.putIfAbsent(key, now);
                if (since != null && now - since < FLASH_MILLIS && (now / 150) % 2 == 0) {
                    context.fill(x + 2, rowY - 1, x + width - 2, rowY + ROW_HEIGHT - 1, 0x4022C55E);
                }
            } else {
                this.completedAt.remove(key);
            }
            context.drawItem(new ItemStack(status.item()), x + 4, rowY);
            String amount = Math.min(status.found(), status.needed()) + "/" + status.needed();
            context.drawTextWithShadow(font, Text.literal(amount), x + 24, rowY + 4, color);
            Text name = status.item().getName();
            String trimmed = font.trimToWidth(name.getString(), width - 90);
            context.drawTextWithShadow(font, Text.literal(trimmed), x + 84, rowY + 4, color);
            rowY += ROW_HEIGHT;
        }
        if (more) {
            context.drawTextWithShadow(font, Chat.tr("hud.more", statuses.size() - rows), x + 4, rowY, COLOR_MISSING);
        }
    }

    private void renderWornTools(DrawContext context, MinecraftClient client, AgentManager manager) {
        ClientPlayerEntity player = client.player;
        if (player.isInCreativeMode()) {
            return;
        }
        int reserve = manager.config().toolDurabilityReserve;
        int centerX = context.getScaledWindowWidth() / 2;
        int y = context.getScaledWindowHeight() - 19;
        for (int i = 0; i < InventoryHelper.HOTBAR_SIZE; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (InventoryHelper.isTool(stack) && stack.isDamageable() && InventoryHelper.remainingDurability(stack) <= reserve) {
                int x = centerX - 90 + i * 20 + 2;
                context.fill(x, y, x + 16, y + 16, COLOR_WORN);
            }
        }
    }
}

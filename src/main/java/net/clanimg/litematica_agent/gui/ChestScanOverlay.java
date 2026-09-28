package net.clanimg.litematica_agent.gui;

import net.clanimg.litematica_agent.agent.AgentManager;
import net.clanimg.litematica_agent.agent.BuildAgent;
import net.clanimg.litematica_agent.inventory.InventoryHelper;
import net.clanimg.litematica_agent.mixin.HandledScreenAccessor;
import net.clanimg.litematica_agent.storage.ContainerRecord;
import net.clanimg.litematica_agent.ui.Chat;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.Generic3x3ContainerScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.HopperScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adds the storage scan buttons next to container screens and colours the recognised slots.
 */
public final class ChestScanOverlay {
    public static final int COLOR_MATERIAL = 0xFF22C55E;
    public static final int COLOR_TOOL = 0xFF3B82F6;
    public static final int COLOR_FOOD = 0xFFEAB308;
    public static final int COLOR_OTHER = 0xFF9CA3AF;
    private static final long CLICK_MEMORY_MILLIS = 5_000L;
    private static final long REVEAL_STEP_MILLIS = 35L;

    private static @Nullable BlockPos lastClickedBlock;
    private static long lastClickTime;

    private ChestScanOverlay() {
    }

    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient() && world.getBlockState(hit.getBlockPos()).hasBlockEntity()) {
                lastClickedBlock = hit.getBlockPos().toImmutable();
                lastClickTime = System.currentTimeMillis();
            }
            return ActionResult.PASS;
        });
        ScreenEvents.AFTER_INIT.register(ChestScanOverlay::afterInit);
    }

    private static void afterInit(MinecraftClient client, Screen screen, int width, int height) {
        if (!(screen instanceof HandledScreen<?> handled) || !isStorageScreen(screen)) {
            return;
        }
        AgentManager manager = AgentManager.get();
        BuildAgent agent = manager.activeAgent();
        if (agent != null && agent.isOperatingContainer()) {
            installAgentGuard(client, handled);
            return;
        }
        if (!manager.isInWorld() || (manager.sessions().isEmpty() && manager.storage().isEmpty())) {
            return;
        }
        BlockPos pos = lastClickedBlock != null && System.currentTimeMillis() - lastClickTime < CLICK_MEMORY_MILLIS
                ? lastClickedBlock : null;
        new Controller(client, handled, pos).install();
    }

    private static boolean isStorageScreen(Screen screen) {
        return screen instanceof GenericContainerScreen || screen instanceof ShulkerBoxScreen
                || screen instanceof HopperScreen || screen instanceof Generic3x3ContainerScreen;
    }

    /**
     * While the agent itself uses a container, player clicks are blocked; ESC pauses the agent.
     */
    private static void installAgentGuard(MinecraftClient client, HandledScreen<?> screen) {
        ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> false);
        ScreenKeyboardEvents.allowKeyPress(screen).register((s, input) -> {
            if (input.isEscape()) {
                AgentManager.get().pauseActive();
            }
            return false;
        });
        ScreenEvents.afterRender(screen).register((s, context, mouseX, mouseY, delta) -> {
            HandledScreenAccessor accessor = (HandledScreenAccessor) screen;
            context.drawCenteredTextWithShadow(client.textRenderer, Chat.tr("scan.agent_working"),
                    screen.width / 2, accessor.litematicaAgent$getY() - 12, 0xFF5EEAD4);
        });
    }

    public static int colorFor(ItemStack stack) {
        if (InventoryHelper.isTool(stack)) {
            return COLOR_TOOL;
        }
        if (InventoryHelper.isFood(stack)) {
            return COLOR_FOOD;
        }
        if (AgentManager.get().isNeededMaterial(stack.getItem())) {
            return COLOR_MATERIAL;
        }
        return COLOR_OTHER;
    }

    private static final class Controller {
        private final MinecraftClient client;
        private final HandledScreen<?> screen;
        private final ScreenHandler handler;
        private final HandledScreenAccessor accessor;
        private final @Nullable BlockPos pos;
        private final Set<Integer> selected = new HashSet<>();
        private final Map<Integer, Integer> revealIndex = new HashMap<>();
        private final List<ClickableWidget> ownButtons = new ArrayList<>();
        private @Nullable ButtonWidget scanAll;
        private @Nullable ButtonWidget select;
        private @Nullable ButtonWidget confirm;
        private @Nullable ButtonWidget cancel;
        private boolean selecting;
        private long revealStart;
        private Text status = Text.empty();
        private int statusColor = 0xFFE5E7EB;

        Controller(MinecraftClient client, HandledScreen<?> screen, @Nullable BlockPos pos) {
            this.client = client;
            this.screen = screen;
            this.handler = screen.getScreenHandler();
            this.accessor = (HandledScreenAccessor) screen;
            this.pos = pos;
        }

        void install() {
            int x = this.accessor.litematicaAgent$getX() + this.accessor.litematicaAgent$getBackgroundWidth() + 4;
            int y = this.accessor.litematicaAgent$getY() + 4;
            int buttonWidth = Math.max(60, Math.min(110, this.screen.width - x - 4));

            this.scanAll = ButtonWidget.builder(Chat.tr("scan.all"), button -> this.scanAll())
                    .dimensions(x, y, buttonWidth, 20).tooltip(Tooltip.of(Chat.tr("scan.all_tooltip"))).build();
            this.select = ButtonWidget.builder(Chat.tr("scan.select"), button -> this.startSelection())
                    .dimensions(x, y + 22, buttonWidth, 20).tooltip(Tooltip.of(Chat.tr("scan.select_tooltip"))).build();
            this.confirm = ButtonWidget.builder(Chat.tr("scan.confirm"), button -> this.confirmSelection())
                    .dimensions(x, y, buttonWidth, 20).build();
            this.cancel = ButtonWidget.builder(Chat.tr("scan.cancel"), button -> this.stopSelection())
                    .dimensions(x, y + 22, buttonWidth, 20).build();
            this.ownButtons.addAll(List.of(this.scanAll, this.select, this.confirm, this.cancel));
            Screens.getButtons(this.screen).addAll(this.ownButtons);

            if (this.pos == null) {
                this.scanAll.active = false;
                this.select.active = false;
                this.scanAll.setTooltip(Tooltip.of(Chat.tr("scan.unknown_position")));
                this.select.setTooltip(Tooltip.of(Chat.tr("scan.unknown_position")));
            } else {
                ContainerRecord existing = AgentManager.get().storage().get(ContainerRecord.key(this.dimension(),
                        this.pos.getX(), this.pos.getY(), this.pos.getZ()));
                if (existing != null) {
                    this.reveal(this.containerSlotsWithItems(), false);
                    this.setStatus(Chat.tr("scan.known"), 0xFF9CA3AF);
                }
            }
            this.updateVisibility();

            ScreenEvents.afterRender(this.screen).register((s, context, mouseX, mouseY, delta) -> this.render(context, mouseX, mouseY));
            ScreenMouseEvents.allowMouseClick(this.screen).register((s, click) -> this.allowClick(click));
            ScreenKeyboardEvents.allowKeyPress(this.screen).register((s, input) -> !this.selecting || input.isEscape());
        }

        private String dimension() {
            return this.client.world == null ? "" : this.client.world.getRegistryKey().getValue().toString();
        }

        private void scanAll() {
            if (this.pos == null) {
                return;
            }
            int stacks = AgentManager.get().scanContainer(this.pos, this.handler, null);
            this.reveal(this.containerSlotsWithItems(), true);
            this.setStatus(Chat.tr("scan.done", stacks), 0xFF4ADE80);
        }

        private void startSelection() {
            this.selecting = true;
            this.selected.clear();
            this.setStatus(Chat.tr("scan.selecting"), 0xFFFBBF24);
            this.updateVisibility();
        }

        private void confirmSelection() {
            if (this.pos == null) {
                return;
            }
            Set<Integer> chosen = new HashSet<>(this.selected);
            int stacks = AgentManager.get().scanContainer(this.pos, this.handler, chosen);
            this.selecting = false;
            List<Integer> ordered = new ArrayList<>();
            for (Slot slot : this.handler.slots) {
                if (chosen.contains(slot.id)) {
                    ordered.add(slot.id);
                }
            }
            this.reveal(ordered, true);
            this.setStatus(Chat.tr("scan.done", stacks), 0xFF4ADE80);
            this.updateVisibility();
        }

        private void stopSelection() {
            this.selecting = false;
            this.selected.clear();
            this.setStatus(Text.empty(), 0);
            this.updateVisibility();
        }

        private void updateVisibility() {
            this.scanAll.visible = !this.selecting;
            this.select.visible = !this.selecting;
            this.confirm.visible = this.selecting;
            this.cancel.visible = this.selecting;
            this.confirm.setMessage(Chat.tr("scan.confirm_count", this.selected.size()));
        }

        private void setStatus(Text text, int color) {
            this.status = text;
            this.statusColor = color;
        }

        private List<Integer> containerSlotsWithItems() {
            List<Integer> ids = new ArrayList<>();
            for (Slot slot : this.handler.slots) {
                if (this.isContainerSlot(slot) && slot.hasStack()) {
                    ids.add(slot.id);
                }
            }
            return ids;
        }

        private void reveal(List<Integer> slotIds, boolean animated) {
            this.revealIndex.clear();
            for (int i = 0; i < slotIds.size(); i++) {
                this.revealIndex.put(slotIds.get(i), animated ? i : 0);
            }
            this.revealStart = System.currentTimeMillis();
        }

        private boolean isContainerSlot(Slot slot) {
            return this.client.player != null && slot.inventory != this.client.player.getInventory();
        }

        private boolean allowClick(Click click) {
            if (!this.selecting) {
                return true;
            }
            for (ClickableWidget widget : this.ownButtons) {
                if (widget.visible && widget.isMouseOver(click.x(), click.y())) {
                    return true;
                }
            }
            Slot slot = this.slotAt(click.x(), click.y());
            if (slot != null && slot.hasStack()) {
                if (!this.selected.remove(slot.id)) {
                    this.selected.add(slot.id);
                }
                this.updateVisibility();
            }
            return false;
        }

        private @Nullable Slot slotAt(double mouseX, double mouseY) {
            int left = this.accessor.litematicaAgent$getX();
            int top = this.accessor.litematicaAgent$getY();
            for (Slot slot : this.handler.slots) {
                if (!this.isContainerSlot(slot)) {
                    continue;
                }
                int x = left + slot.x;
                int y = top + slot.y;
                if (mouseX >= x - 1 && mouseX < x + 17 && mouseY >= y - 1 && mouseY < y + 17) {
                    return slot;
                }
            }
            return null;
        }

        private void render(DrawContext context, int mouseX, int mouseY) {
            int left = this.accessor.litematicaAgent$getX();
            int top = this.accessor.litematicaAgent$getY();
            long elapsed = System.currentTimeMillis() - this.revealStart;

            for (Slot slot : this.handler.slots) {
                if (!this.isContainerSlot(slot) || !slot.hasStack()) {
                    continue;
                }
                int x = left + slot.x;
                int y = top + slot.y;
                if (this.selecting) {
                    if (this.selected.contains(slot.id)) {
                        context.fill(x, y, x + 16, y + 16, 0x70FFFFFF);
                        context.drawStrokedRectangle(x - 1, y - 1, 18, 18, 0xFFFFFFFF);
                    }
                    continue;
                }
                Integer order = this.revealIndex.get(slot.id);
                if (order == null || elapsed < order * REVEAL_STEP_MILLIS) {
                    continue;
                }
                int color = colorFor(slot.getStack());
                context.fill(x, y, x + 16, y + 16, (color & 0x00FFFFFF) | 0x50000000);
                context.drawStrokedRectangle(x - 1, y - 1, 18, 18, color);
            }

            if (this.selecting) {
                Slot hovered = this.slotAt(mouseX, mouseY);
                if (hovered != null) {
                    context.drawStrokedRectangle(left + hovered.x - 1, top + hovered.y - 1, 18, 18, 0xFFFBBF24);
                }
            }

            int textX = this.scanAll.getX();
            int textY = this.scanAll.getY() + 48;
            int maxWidth = Math.max(40, this.screen.width - textX - 4);
            if (!this.status.getString().isEmpty()) {
                for (var line : this.client.textRenderer.wrapLines(this.status, maxWidth)) {
                    context.drawTextWithShadow(this.client.textRenderer, line, textX, textY, this.statusColor);
                    textY += 10;
                }
                textY += 4;
            }
            this.legend(context, textX, textY, COLOR_MATERIAL, Chat.tr("scan.legend_material"));
            this.legend(context, textX, textY + 11, COLOR_TOOL, Chat.tr("scan.legend_tool"));
            this.legend(context, textX, textY + 22, COLOR_FOOD, Chat.tr("scan.legend_food"));
        }

        private void legend(DrawContext context, int x, int y, int color, Text label) {
            context.fill(x, y + 1, x + 7, y + 8, color);
            context.drawTextWithShadow(this.client.textRenderer, label, x + 10, y, 0xFFE5E7EB);
        }
    }
}

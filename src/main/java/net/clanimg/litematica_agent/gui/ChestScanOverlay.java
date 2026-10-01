package net.clanimg.litematica_agent.gui;

import net.clanimg.litematica_agent.agent.AgentManager;
import net.clanimg.litematica_agent.agent.BuildAgent;
import net.clanimg.litematica_agent.agent.StockAgent;
import net.clanimg.litematica_agent.inventory.InventoryHelper;
import net.clanimg.litematica_agent.mixin.HandledScreenAccessor;
import net.clanimg.litematica_agent.storage.ContainerRecord;
import net.clanimg.litematica_agent.ui.Chat;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.DrawnTextConsumer;
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
import net.minecraft.world.World;
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
    /** Small square buttons (icon only, vanilla hover highlight), like the icon buttons other inventory mods use. */
    private static final int BUTTON_SIZE = 20;
    private static final int BUTTON_GAP = 2;

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
        StockAgent stock = manager.stockAgent();
        if ((agent != null && agent.isOperatingContainer()) || (stock != null && stock.isOperatingContainer())) {
            installAgentGuard(client, handled);
            return;
        }
        if (!manager.isInWorld() || (manager.sessions().isEmpty() && manager.storage().isEmpty())) {
            return;
        }
        BlockPos pos = lastClickedBlock != null && System.currentTimeMillis() - lastClickTime < CLICK_MEMORY_MILLIS
                ? lastClickedBlock : null;
        new Controller(client, handled, pos == null || client.world == null ? null : storagePos(client.world, pos)).install();
    }

    /**
     * Both halves of a double chest are one container, so it is recorded under the same half whichever was clicked;
     * otherwise opening it from the other side would look like an unknown chest and could be recorded twice.
     */
    private static BlockPos storagePos(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
            BlockPos other = pos.offset(ChestBlock.getFacing(state));
            if (other.getX() < pos.getX() || (other.getX() == pos.getX() && other.getZ() < pos.getZ())) {
                return other;
            }
        }
        return pos;
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
        if (InventoryHelper.isUtility(stack)) {
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
        /** Contents of a known container when it was opened, by slot index, to notice what the player put in. */
        private final Map<Integer, ItemStack> contentsAtOpen = new HashMap<>();
        private final List<ClickableWidget> ownButtons = new ArrayList<>();
        /** Character icon drawn centred on each button, in the same order as {@link #ownButtons}. */
        private final List<String> buttonIcons = new ArrayList<>();
        private @Nullable ButtonWidget scanAll;
        private @Nullable ButtonWidget select;
        private @Nullable ButtonWidget confirm;
        private @Nullable ButtonWidget cancel;
        /** Non-null when the container was already scanned before this opening; used to colour slots immediately even
         *  if their contents arrive from the server after the screen was initialised. */
        private @Nullable ContainerRecord knownRecord;
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
            int x = Math.min(this.accessor.litematicaAgent$getX() + this.accessor.litematicaAgent$getBackgroundWidth() + 2,
                    this.screen.width - BUTTON_SIZE - 2);
            int y = this.accessor.litematicaAgent$getY() + 4;
            int step = BUTTON_SIZE + BUTTON_GAP;

            this.scanAll = this.iconButton(x, y, "◉", "scan.all",
                    Chat.tr("scan.all"), button -> this.scanAll());
            this.select = this.iconButton(x, y + step, "✦", "scan.select",
                    Chat.tr("scan.select"), button -> this.startSelection());
            this.confirm = this.iconButton(x, y, "✓", "scan.confirm", Chat.tr("scan.confirm"), button -> this.confirmSelection());
            this.cancel = this.iconButton(x, y + step, "✕", "scan.cancel", Chat.tr("scan.cancel"), button -> this.stopSelection());
            Screens.getButtons(this.screen).addAll(this.ownButtons);

            if (this.pos == null) {
                this.scanAll.active = false;
                this.select.active = false;
                this.scanAll.setTooltip(Tooltip.of(Chat.tr("scan.unknown_position")));
                this.select.setTooltip(Tooltip.of(Chat.tr("scan.unknown_position")));
            } else {
                ContainerRecord existing = this.existingRecord();
                if (existing != null) {
                    // Remember the record so that colours are drawn even if slot contents arrive after init.
                    this.knownRecord = existing;
                    this.reveal(this.recordedSlots(existing), false);
                    for (Slot slot : this.handler.slots) {
                        if (this.isContainerSlot(slot)) {
                            this.contentsAtOpen.put(slot.getIndex(), slot.getStack().copy());
                        }
                    }
                }
            }
            this.updateVisibility();

            // Drawn right after the container background, so the markings lie under the items, the hover highlight
            // and the tooltip instead of on top of them.
            ScreenEvents.afterBackground(this.screen).register((s, context, mouseX, mouseY, delta) -> this.render(context, mouseX, mouseY));
            // The button icons are drawn on top of the vanilla button background, once that has rendered.
            ScreenEvents.afterRender(this.screen).register((s, context, mouseX, mouseY, delta) -> this.renderIcons(context));
            ScreenMouseEvents.allowMouseClick(this.screen).register((s, click) -> this.allowClick(click));
            ScreenKeyboardEvents.allowKeyPress(this.screen).register((s, input) -> !this.selecting || input.isEscape());
            ScreenEvents.remove(this.screen).register(s -> {
                if (this.selecting) {
                    // Closing the chest keeps a started selection instead of silently dropping it.
                    if (!this.selected.isEmpty() || this.existingRecord() != null) {
                        this.confirmSelection();
                    }
                } else if (this.pos != null && !this.contentsAtOpen.isEmpty()) {
                    AgentManager.get().refreshContainer(this.pos, this.handler, this.contentsAtOpen);
                }
            });
        }

        private String dimension() {
            return this.client.world == null ? "" : this.client.world.getRegistryKey().getValue().toString();
        }

        private @Nullable ContainerRecord existingRecord() {
            if (this.pos == null) {
                return null;
            }
            return AgentManager.get().storage().get(ContainerRecord.key(this.dimension(), this.pos.getX(), this.pos.getY(), this.pos.getZ()));
        }

        private void scanAll() {
            if (this.pos == null) {
                return;
            }
            Set<Integer> chosen = AgentManager.get().autoSelectSlots(this.pos, this.handler);
            int stacks = AgentManager.get().scanContainer(this.pos, this.handler, chosen);
            this.reveal(this.inSlotOrder(chosen), true);
            this.setStatus(Chat.tr("scan.done", stacks), 0xFF4ADE80);
        }

        private void startSelection() {
            this.selecting = true;
            this.selected.clear();
            ContainerRecord existing = this.existingRecord();
            if (existing != null) {
                this.selected.addAll(this.recordedSlots(existing));
            }
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
            this.reveal(this.inSlotOrder(chosen), true);
            this.setStatus(Chat.tr("scan.done", stacks), 0xFF4ADE80);
            this.updateVisibility();
        }

        private List<Integer> inSlotOrder(Set<Integer> slotIds) {
            List<Integer> ordered = new ArrayList<>();
            for (Slot slot : this.handler.slots) {
                if (slotIds.contains(slot.id)) {
                    ordered.add(slot.id);
                }
            }
            return ordered;
        }

        /** Handler slot ids of the container slots the record allows the agent to use. */
        private List<Integer> recordedSlots(ContainerRecord record) {
            List<Integer> ids = new ArrayList<>();
            for (Slot slot : this.handler.slots) {
                if (this.isContainerSlot(slot) && slot.hasStack() && record.allows(slot.getIndex())) {
                    ids.add(slot.id);
                }
            }
            return ids;
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
            this.confirm.setTooltip(Tooltip.of(Chat.tr("scan.confirm_count", this.selected.size())));
        }

        /**
         * A small square button with a single-character icon (drawn separately in {@link #renderIcons}) instead of a
         * visible text label. The button keeps its real translatable message - just not drawn - so a screen reader
         * still announces it and game tests can still find it by that name, exactly like a normal labelled button.
         */
        private ButtonWidget iconButton(int x, int y, String icon, String messageKey, Text tooltip, ButtonWidget.PressAction action) {
            ButtonWidget button = new IconButton(x, y, BUTTON_SIZE, Chat.tr(messageKey), action);
            button.setTooltip(Tooltip.of(tooltip));
            this.ownButtons.add(button);
            this.buttonIcons.add(icon);
            return button;
        }

        /** Vanilla button with its label rendering suppressed; see {@link #iconButton}. */
        private static final class IconButton extends ButtonWidget {
            // Fully qualified: ButtonWidget's own hierarchy declares a nested type also called Text, which would
            // otherwise shadow the net.minecraft.text.Text import inside this subclass.
            IconButton(int x, int y, int size, net.minecraft.text.Text message, PressAction onPress) {
                super(x, y, size, size, message, onPress, DEFAULT_NARRATION_SUPPLIER);
            }

            @Override
            protected void drawIcon(DrawContext context, int x, int y, float deltaTicks) {
                // The pixel icon is drawn afterwards in Controller#renderIcons, once the whole screen has rendered.
            }

            @Override
            protected void drawLabel(DrawnTextConsumer consumer) {
                // No text label: the icon alone says what the button does.
            }
        }

        private void renderIcons(DrawContext context) {
            for (int i = 0; i < this.ownButtons.size(); i++) {
                ClickableWidget button = this.ownButtons.get(i);
                if (!button.visible) {
                    continue;
                }
                String icon = this.buttonIcons.get(i);
                int iconX = button.getX() + (button.getWidth() - this.client.textRenderer.getWidth(icon)) / 2;
                int iconY = button.getY() + (button.getHeight() - 8) / 2;
                context.drawTextWithShadow(this.client.textRenderer, icon, iconX, iconY,
                        button.active ? 0xFFE5E7EB : 0xFF6B7280);
            }
            if (this.confirm.visible && !this.selected.isEmpty()) {
                String count = String.valueOf(this.selected.size());
                int badgeX = this.confirm.getX() + this.confirm.getWidth() - this.client.textRenderer.getWidth(count) - 2;
                int badgeY = this.confirm.getY() + this.confirm.getHeight() - 9;
                context.drawTextWithShadow(this.client.textRenderer, count, badgeX, badgeY, 0xFFFFFFFF);
            }
        }

        private void setStatus(Text text, int color) {
            this.status = text;
            this.statusColor = color;
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
                    // Fallback: if this container was already known, colour the slot from the record directly.
                    // This handles the case where slot contents arrive from the server after install().
                    if (this.knownRecord == null || !this.knownRecord.allows(slot.getIndex())) {
                        continue;
                    }
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

            if (!this.status.getString().isEmpty()) {
                int textX = this.scanAll.getX();
                int textY = this.scanAll.getY() + 2 * BUTTON_SIZE + BUTTON_GAP + 6;
                int maxWidth = Math.max(40, this.screen.width - textX - 4);
                for (var line : this.client.textRenderer.wrapLines(this.status, maxWidth)) {
                    context.drawTextWithShadow(this.client.textRenderer, line, textX, textY, this.statusColor);
                    textY += 10;
                }
            }
        }
    }
}

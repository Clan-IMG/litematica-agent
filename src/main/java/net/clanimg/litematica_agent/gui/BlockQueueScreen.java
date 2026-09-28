package net.clanimg.litematica_agent.gui;

import net.clanimg.litematica_agent.agent.AgentManager;
import net.clanimg.litematica_agent.agent.AgentSession;
import net.clanimg.litematica_agent.agent.BuildStrategy;
import net.clanimg.litematica_agent.agent.SessionRuntime;
import net.clanimg.litematica_agent.ui.Chat;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Chooses how the agent works through the schematic: layer by layer, or only the block types clicked here, one after
 * the other in the order they were added. Changes apply immediately, also while the agent is working.
 */
public final class BlockQueueScreen extends Screen implements AgentScreen {
    private static final int CELL = 22;
    private static final int TOP = 58;
    private static final int COLOR_TEXT = 0xFFE5E7EB;
    private static final int COLOR_MUTED = 0xFF9CA3AF;
    private static final int COLOR_QUEUED = 0xFF22C55E;

    private final @Nullable Screen parent;
    private final SessionRuntime runtime;
    private final List<Item> items = new ArrayList<>();
    private final List<Cell> cells = new ArrayList<>();
    private final List<Cell> queueCells = new ArrayList<>();
    private int page;
    private int gridX;

    private record Cell(Item item, int x, int y) {
    }

    public BlockQueueScreen(@Nullable Screen parent, SessionRuntime runtime) {
        super(Chat.tr("queue.title"));
        this.parent = parent;
        this.runtime = runtime;
    }

    private AgentSession session() {
        return this.runtime.session();
    }

    @Override
    protected void init() {
        Map<Item, Integer> remaining = this.runtime.remainingMaterials();
        this.items.clear();
        this.items.addAll(remaining.keySet());
        this.items.sort((a, b) -> Integer.compare(remaining.get(b), remaining.get(a)));
        this.cells.clear();
        this.queueCells.clear();

        int half = this.width / 2;
        boolean blocks = this.session().strategy == BuildStrategy.BLOCKS;
        this.addDrawableChild(ButtonWidget.builder(mark(Chat.tr("queue.layers"), !blocks), button -> this.setStrategy(BuildStrategy.LAYERS))
                .dimensions(half - 154, 24, 150, 20).tooltip(Tooltip.of(Chat.tr("queue.layers_tooltip"))).build());
        this.addDrawableChild(ButtonWidget.builder(mark(Chat.tr("queue.blocks"), blocks), button -> this.setStrategy(BuildStrategy.BLOCKS))
                .dimensions(half + 4, 24, 150, 20).tooltip(Tooltip.of(Chat.tr("queue.blocks_tooltip"))).build());

        // Left half: every block type of the schematic that still has something to build.
        int columns = Math.max(1, Math.min(9, (half - 30) / CELL));
        int rows = Math.max(1, (this.height - TOP - 60) / CELL);
        int perPage = columns * rows;
        int pages = Math.max(1, (this.items.size() + perPage - 1) / perPage);
        this.page = Math.min(this.page, pages - 1);
        int gridX = half - 10 - columns * CELL;
        this.gridX = gridX;
        for (int i = 0; i < perPage; i++) {
            int index = this.page * perPage + i;
            if (index >= this.items.size()) {
                break;
            }
            Item item = this.items.get(index);
            int x = gridX + (i % columns) * CELL;
            int y = TOP + (i / columns) * CELL;
            this.cells.add(new Cell(item, x, y));
            this.addDrawableChild(ButtonWidget.builder(Text.empty(), button -> this.add(item))
                    .dimensions(x, y, 20, 20)
                    .tooltip(Tooltip.of(Chat.tr("queue.item_tooltip", item.getName(), remaining.get(item))))
                    .build());
        }
        if (pages > 1) {
            int pagerY = TOP + rows * CELL + 2;
            this.addDrawableChild(ButtonWidget.builder(Text.literal("<"), button -> this.turn(-1))
                    .dimensions(gridX, pagerY, 20, 20).build()).active = this.page > 0;
            this.addDrawableChild(ButtonWidget.builder(Text.literal(">"), button -> this.turn(1))
                    .dimensions(gridX + 24, pagerY, 20, 20).build()).active = this.page < pages - 1;
        }

        // Right half: the queue; clicking an entry removes it.
        int queueX = half + 10;
        int queueWidth = Math.min(200, half - 30);
        int maxEntries = Math.max(1, (this.height - TOP - 60) / CELL);
        List<String> queue = this.session().blockQueue;
        for (int i = 0; i < Math.min(queue.size(), maxEntries); i++) {
            String id = queue.get(i);
            Item item = Registries.ITEM.get(Identifier.tryParse(id));
            int y = TOP + i * CELL;
            this.queueCells.add(new Cell(item, queueX + 2, y + 2));
            Text label = Chat.tr("queue.entry", i + 1, item.getName(), remaining.getOrDefault(item, 0));
            this.addDrawableChild(ButtonWidget.builder(label, button -> this.remove(id))
                    .dimensions(queueX + 22, y, queueWidth - 22, 20)
                    .tooltip(Tooltip.of(Chat.tr("queue.remove_tooltip"))).build());
        }

        int footerY = this.height - 28;
        this.addDrawableChild(ButtonWidget.builder(Chat.tr("queue.clear"), button -> {
            this.session().blockQueue.clear();
            this.changed();
        }).dimensions(half - 154, footerY, 150, 20).build()).active = !queue.isEmpty();
        this.addDrawableChild(ButtonWidget.builder(Chat.tr("queue.done"), button -> this.close())
                .dimensions(half + 4, footerY, 150, 20).build());
    }

    private static Text mark(Text label, boolean active) {
        return active ? Text.literal("✔ ").append(label).formatted(Formatting.GREEN) : label;
    }

    /** Clicking a block type adds it to the queue and switches to building by block type. */
    private void add(Item item) {
        String id = Registries.ITEM.getId(item).toString();
        if (!this.session().blockQueue.contains(id)) {
            this.session().blockQueue.add(id);
        }
        this.session().strategy = BuildStrategy.BLOCKS;
        this.changed();
    }

    private void remove(String id) {
        this.session().blockQueue.remove(id);
        this.changed();
    }

    private void setStrategy(BuildStrategy strategy) {
        this.session().strategy = strategy;
        this.changed();
    }

    private void turn(int direction) {
        this.page += direction;
        this.clearAndInit();
    }

    private void changed() {
        AgentManager.get().markDirty();
        this.clearAndInit();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 8, COLOR_TEXT);
        int half = this.width / 2;
        context.drawTextWithShadow(this.textRenderer, Chat.tr("queue.available"), this.gridX, TOP - 11, COLOR_MUTED);
        context.drawTextWithShadow(this.textRenderer, Chat.tr("queue.order"), half + 10, TOP - 11, COLOR_MUTED);
        if (this.session().blockQueue.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, Chat.tr("queue.empty"), half + 10, TOP + 6, COLOR_MUTED);
        }
        List<String> queue = this.session().blockQueue;
        for (Cell cell : this.cells) {
            if (queue.contains(Registries.ITEM.getId(cell.item()).toString())) {
                context.drawStrokedRectangle(cell.x() - 1, cell.y() - 1, 22, 22, COLOR_QUEUED);
            }
            context.drawItem(new ItemStack(cell.item()), cell.x() + 2, cell.y() + 2);
        }
        for (Cell cell : this.queueCells) {
            context.drawItem(new ItemStack(cell.item()), cell.x(), cell.y());
        }
    }

    @Override
    public void close() {
        AgentManager.get().save();
        this.client.setScreen(this.parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}

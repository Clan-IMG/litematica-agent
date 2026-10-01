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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chooses how the agent works through the schematic (strategy cycle) and optionally restricts which block types to
 * build (item filter). A single cycling button switches between the three layer strategies; the grid below lets the
 * player pick specific block types (default: all).
 */
public final class BlockQueueScreen extends Screen implements AgentScreen {
    private static final int CELL = 22;
    private static final int TOP = 58;
    private static final int COLOR_TEXT = 0xFFE5E7EB;
    private static final int COLOR_MUTED = 0xFF9CA3AF;
    private static final int COLOR_SELECTED = 0xFF22C55E;
    private static final int COLOR_DESELECTED = 0x40FFFFFF;

    private final @Nullable Screen parent;
    private final SessionRuntime runtime;
    private final List<Item> items = new ArrayList<>();
    private final List<Cell> cells = new ArrayList<>();
    private int page;
    private int gridX;
    /** When non-empty, only these block types are built; empty means "everything". */
    private final Set<String> filter;

    private record Cell(Item item, int x, int y) {
    }

    public BlockQueueScreen(@Nullable Screen parent, SessionRuntime runtime) {
        super(Chat.tr("queue.title"));
        this.parent = parent;
        this.runtime = runtime;
        this.filter = new LinkedHashSet<>(this.session().blockQueue);
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

        int half = this.width / 2;
        BuildStrategy strategy = this.session().strategy;
        // If the old BLOCKS strategy is loaded, convert to LAYERS with the block queue as filter.
        if (strategy == BuildStrategy.BLOCKS) {
            strategy = BuildStrategy.LAYERS;
            this.session().strategy = strategy;
        }
        Text strategyLabel = strategyName(strategy);
        this.addDrawableChild(ButtonWidget.builder(strategyLabel, button -> this.cycleStrategy())
                .dimensions(half - 100, 24, 200, 20)
                .tooltip(Tooltip.of(Chat.tr("queue." + strategy.id() + "_tooltip")))
                .build());

        // Filter hint
        boolean filtering = !this.filter.isEmpty();

        // Grid: every block type of the schematic that still has something to build.
        int columns = Math.max(1, Math.min(9, (this.width - 40) / CELL));
        int rows = Math.max(1, (this.height - TOP - 60) / CELL);
        int perPage = columns * rows;
        int pages = Math.max(1, (this.items.size() + perPage - 1) / perPage);
        this.page = Math.min(this.page, pages - 1);
        int gridX = (this.width - columns * CELL) / 2;
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
            this.addDrawableChild(ButtonWidget.builder(Text.empty(), button -> this.toggle(item))
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

        int footerY = this.height - 28;
        this.addDrawableChild(ButtonWidget.builder(Chat.tr("queue.clear"), button -> {
            this.filter.clear();
            this.changed();
        }).dimensions(half - 154, footerY, 150, 20).build()).active = filtering;
        this.addDrawableChild(ButtonWidget.builder(Chat.tr("queue.done"), button -> this.close())
                .dimensions(half + 4, footerY, 150, 20).build());
    }

    private static Text strategyName(BuildStrategy strategy) {
        return switch (strategy) {
            case LAYERS -> Chat.tr("queue.layers_up");
            case LAYERS_TOP_DOWN -> Chat.tr("queue.layers_down");
            case PROXIMITY -> Chat.tr("queue.proximity");
            case BLOCKS -> Chat.tr("queue.layers_up"); // fallback
        };
    }

    private void cycleStrategy() {
        BuildStrategy next = this.session().strategy.next();
        this.session().strategy = next;
        this.changed();
    }

    /** Clicking a block type toggles it in/out of the filter. */
    private void toggle(Item item) {
        String id = Registries.ITEM.getId(item).toString();
        if (!this.filter.remove(id)) {
            this.filter.add(id);
        }
        this.changed();
    }

    private void turn(int direction) {
        this.page += direction;
        this.clearAndInit();
    }

    private void changed() {
        // The selection is kept in the session's block queue: the agent reads it from there (see BuildAgent#selection)
        // and it is saved with the session.
        this.session().blockQueue.clear();
        this.session().blockQueue.addAll(this.filter);
        AgentManager.get().markDirty();
        this.clearAndInit();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        super.render(context, mouseX, mouseY, deltaTicks);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 8, COLOR_TEXT);
        boolean filtering = !this.filter.isEmpty();
        Text hint = filtering ? Chat.tr("queue.filter_active", this.filter.size()) : Chat.tr("queue.filter_hint");
        context.drawCenteredTextWithShadow(this.textRenderer, hint, this.width / 2, TOP - 11, COLOR_MUTED);

        for (Cell cell : this.cells) {
            String id = Registries.ITEM.getId(cell.item()).toString();
            boolean inFilter = this.filter.contains(id);
            if (filtering && inFilter) {
                context.drawStrokedRectangle(cell.x() - 1, cell.y() - 1, 22, 22, COLOR_SELECTED);
            }
            context.drawItem(new ItemStack(cell.item()), cell.x() + 2, cell.y() + 2);
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

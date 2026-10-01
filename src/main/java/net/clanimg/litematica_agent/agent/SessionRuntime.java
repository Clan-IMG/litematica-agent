package net.clanimg.litematica_agent.agent;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.clanimg.litematica_agent.LitematicaAgentClient;
import net.clanimg.litematica_agent.planning.BuildCategory;
import net.clanimg.litematica_agent.planning.BuildPlan;
import net.clanimg.litematica_agent.placement.StateMatcher;
import net.clanimg.litematica_agent.placement.WaterPlacement;
import net.clanimg.litematica_agent.schematic.BuildTarget;
import net.clanimg.litematica_agent.schematic.SchematicAccess;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EmptyBlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory data of the session that is currently being prepared or built.
 */
public final class SessionRuntime {
    private final AgentSession session;
    private final SchematicPlacement placement;
    private final BuildPlan<BuildTarget> plan;
    private final List<SchematicAccess.UnsupportedBlock> unsupported;
    /** Items still needed by pending targets, kept up to date on every status change instead of being recounted. */
    private final Object2IntOpenHashMap<Item> remaining = new Object2IntOpenHashMap<>();
    /**
     * Pending targets per item and layer, kept up to date like {@link #remaining}. With a block selection the agent
     * skips every layer that has none of the selected items without looking at a single target of it.
     */
    private final Reference2ObjectOpenHashMap<Item, int[]> pendingByLayer = new Reference2ObjectOpenHashMap<>();
    /** Pending targets per item (not item counts: a double slab is one target). */
    private final Object2IntOpenHashMap<Item> pendingTargets = new Object2IntOpenHashMap<>();
    /** Pending targets that need a bucket of water (water sources and blocks placed into water). */
    private int pendingWater;
    private @Nullable Map<Item, Integer> remainingSnapshot;
    /** Tools the build needs, found while the schematic was read in the background. */
    private @Nullable Set<StateMatcher.Tool> toolsAtStart;
    private Map<Item, Integer> layerCache;
    private int layerCacheIndex = -1;
    private long layerCacheTime;

    private SessionRuntime(AgentSession session, SchematicPlacement placement, BuildPlan<BuildTarget> plan,
                           List<SchematicAccess.UnsupportedBlock> unsupported) {
        this.session = session;
        this.placement = placement;
        this.plan = plan;
        this.unsupported = unsupported;
        int layers = plan.layerCount();
        for (int layer = 0; layer < layers; layer++) {
            int[] bounds = plan.layerBounds(layer);
            for (int i = bounds[0]; i < bounds[1]; i++) {
                BuildTarget target = plan.get(i);
                this.remaining.addTo(target.item(), target.count());
                this.pendingTargets.addTo(target.item(), 1);
                this.pendingByLayer.computeIfAbsent(target.item(), item -> new int[layers])[layer]++;
                if (needsBucket(target)) {
                    this.pendingWater++;
                }
            }
        }
        plan.setListener(this::statusChanged);
    }

    private static boolean needsBucket(BuildTarget target) {
        return WaterPlacement.isWaterTarget(target.state()) || WaterPlacement.needsWater(target.state());
    }

    /**
     * Water buckets are refilled on site, so the materials show the few buckets the agent carries, not one per
     * water block (see {@link WaterPlacement#BUCKETS_CARRIED}).
     */
    private static void capWater(Map<Item, Integer> materials, int water) {
        materials.remove(Items.WATER_BUCKET);
        if (water > 0) {
            materials.put(Items.WATER_BUCKET, Math.min(water, WaterPlacement.BUCKETS_CARRIED));
        }
    }

    /**
     * Builds the runtime from a read schematic: sorts the build plan and compares it with the world. For big schematics
     * this takes a while, so it runs on a background thread ({@link SchematicLoader}); the world is only read.
     */
    static SessionRuntime create(AgentSession session, SchematicPlacement placement, SchematicAccess.SchematicReadResult read,
                                 World world) {
        BuildPlan<BuildTarget> plan = new BuildPlan<>(read.targets(),
                BuildTarget::posLong,
                target -> BuildCategory.classify(target.state(), EmptyBlockView.INSTANCE, target.pos()).ordinal());
        SessionRuntime runtime = new SessionRuntime(session, placement, plan, read.unsupported());
        // Off the render thread as well: for a huge plan the chunk index takes a noticeable moment.
        plan.prepareIndexes();
        runtime.refreshFromWorld(world);
        runtime.toolsAtStart = runtime.requiredTools();
        return runtime;
    }

    private void statusChanged(int index, BuildPlan.Status from, BuildPlan.Status to) {
        boolean wasOpen = from == BuildPlan.Status.PENDING;
        if (wasOpen == (to == BuildPlan.Status.PENDING)) {
            return;
        }
        BuildTarget target = this.plan.get(index);
        int delta = wasOpen ? -target.count() : target.count();
        // addTo returns the count before the change.
        int left = this.remaining.addTo(target.item(), delta) + delta;
        if (left <= 0) {
            this.remaining.removeInt(target.item());
        }
        int step = wasOpen ? -1 : 1;
        this.pendingTargets.addTo(target.item(), step);
        this.pendingByLayer.get(target.item())[this.plan.layerOfIndex(index)] += step;
        if (needsBucket(target)) {
            this.pendingWater += wasOpen ? -1 : 1;
        }
        this.remainingSnapshot = null;
    }

    public AgentSession session() {
        return this.session;
    }

    public SchematicPlacement placement() {
        return this.placement;
    }

    public BuildPlan<BuildTarget> plan() {
        return this.plan;
    }

    public List<SchematicAccess.UnsupportedBlock> unsupported() {
        return this.unsupported;
    }

    /**
     * Marks every target in loaded chunks as done or pending according to the world.
     */
    public void refreshFromWorld(World world) {
        for (int i = 0; i < this.plan.size(); i++) {
            BuildTarget target = this.plan.get(i);
            BlockPos pos = target.pos();
            try {
                if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                    continue;
                }
                BlockState state = world.getBlockState(pos);
                if (state.isOf(Blocks.VOID_AIR)) {
                    // Counted as loaded, blocks not there yet: no comparison possible.
                    continue;
                }
                if (StateMatcher.isComplete(state, target.state())) {
                    this.plan.markDone(i);
                } else if (this.plan.status(i) == BuildPlan.Status.DONE) {
                    this.plan.markPending(i);
                }
            } catch (RuntimeException e) {
                // Read from a background thread while a chunk changed: the agent checks this block again later.
                LitematicaAgentClient.LOGGER.debug("Could not compare {} with the world", pos, e);
            }
        }
        this.updateCounters();
    }

    public void updateCounters() {
        this.session.totalBlocks = this.plan.size();
        this.session.doneBlocks = this.plan.doneCount();
    }

    /**
     * Items still needed for the pending targets of one layer. Cached for a second, the lock screen asks every frame.
     */
    public Map<Item, Integer> remainingInLayer(int layerIndex) {
        long now = System.currentTimeMillis();
        if (this.layerCache != null && this.layerCacheIndex == layerIndex && now - this.layerCacheTime < 1000L) {
            return this.layerCache;
        }
        Map<Item, Integer> materials = new LinkedHashMap<>();
        int water = 0;
        int[] bounds = this.plan.layerBounds(layerIndex);
        for (int i = bounds[0]; i < bounds[1]; i++) {
            if (this.plan.status(i) == BuildPlan.Status.PENDING) {
                BuildTarget target = this.plan.get(i);
                materials.merge(target.item(), target.count(), Integer::sum);
                if (needsBucket(target)) {
                    water++;
                }
            }
        }
        capWater(materials, water);
        this.layerCache = Map.copyOf(materials);
        this.layerCacheIndex = layerIndex;
        this.layerCacheTime = now;
        return this.layerCache;
    }

    /** Whether a layer has pending targets of any of the items. */
    public boolean layerHasPending(int layer, Set<Item> items) {
        for (Item item : items) {
            int[] counts = this.pendingByLayer.get(item);
            if (counts != null && counts[layer] > 0) {
                return true;
            }
        }
        return false;
    }

    /** Pending targets of the items in one layer. */
    public int pendingInLayer(int layer, Set<Item> items) {
        int sum = 0;
        for (Item item : items) {
            int[] counts = this.pendingByLayer.get(item);
            if (counts != null) {
                sum += counts[layer];
            }
        }
        return sum;
    }

    /**
     * Whether a layer has a pending target that is going to be built: of the selection (all items when there is
     * none), and not of a material the agent has run out of. Counters only, so a huge layer costs nothing to skip.
     */
    public boolean layerHasBuildable(int layer, @Nullable Set<Item> selection, Set<Item> missing) {
        if (selection != null) {
            for (Item item : selection) {
                int[] counts = this.pendingByLayer.get(item);
                if (counts != null && counts[layer] > 0 && !missing.contains(item)) {
                    return true;
                }
            }
            return false;
        }
        int pending = this.plan.pendingInLayer(layer);
        return pending > 0 && (missing.isEmpty() || pending > this.pendingInLayer(layer, missing));
    }

    /** Pending targets (of any layer) of the items. */
    public int pendingTargets(Set<Item> items) {
        int sum = 0;
        for (Item item : items) {
            sum += this.pendingTargets.getInt(item);
        }
        return sum;
    }

    /** Pending targets of the whole plan whose item is not one of {@code excluded}. */
    public int pendingTargetsExcept(Set<Item> excluded) {
        return this.plan.pendingCount() - this.pendingTargets(excluded);
    }

    /** Tools needed when the schematic was read; cheap to ask for, also for a huge schematic. */
    public Set<StateMatcher.Tool> toolsAtStart() {
        return this.toolsAtStart != null ? this.toolsAtStart : this.requiredTools();
    }

    /** Tools the pending targets need besides their materials (flint and steel for lit candles, ...). */
    public Set<StateMatcher.Tool> requiredTools() {
        Set<StateMatcher.Tool> tools = EnumSet.noneOf(StateMatcher.Tool.class);
        for (int i = 0; i < this.plan.size(); i++) {
            if (this.plan.status(i) == BuildPlan.Status.PENDING) {
                StateMatcher.Tool tool = StateMatcher.toolNeeded(this.plan.get(i).state());
                if (tool != null) {
                    tools.add(tool);
                }
            }
        }
        return tools;
    }

    /**
     * Items still needed for all pending targets. Kept up to date with every placed block, so the HUD, the lock screen
     * and the chest overlay can ask every frame even for huge schematics.
     */
    public Map<Item, Integer> remainingMaterials() {
        if (this.remainingSnapshot == null) {
            Map<Item, Integer> materials = new LinkedHashMap<>(this.remaining);
            capWater(materials, this.pendingWater);
            this.remainingSnapshot = Map.copyOf(materials);
        }
        return this.remainingSnapshot;
    }
}

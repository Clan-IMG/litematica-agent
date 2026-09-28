package net.clanimg.litematica_agent.agent;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.clanimg.litematica_agent.planning.BuildCategory;
import net.clanimg.litematica_agent.planning.BuildPlan;
import net.clanimg.litematica_agent.placement.StateMatcher;
import net.clanimg.litematica_agent.schematic.BuildTarget;
import net.clanimg.litematica_agent.schematic.SchematicAccess;
import net.clanimg.litematica_agent.schematic.SurvivalCheck;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

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
    private Map<Item, Integer> materialsCache;
    private long materialsCacheTime;
    private Map<Item, Integer> layerCache;
    private int layerCacheIndex = -1;
    private long layerCacheTime;

    private SessionRuntime(AgentSession session, SchematicPlacement placement, BuildPlan<BuildTarget> plan,
                           List<SchematicAccess.UnsupportedBlock> unsupported) {
        this.session = session;
        this.placement = placement;
        this.plan = plan;
        this.unsupported = unsupported;
    }

    public static SessionRuntime load(AgentSession session, SchematicPlacement placement, World world) {
        SchematicAccess.SchematicReadResult read = SurvivalCheck.apply(SchematicAccess.readTargets(placement), world);
        BuildPlan<BuildTarget> plan = new BuildPlan<>(read.targets(),
                target -> target.pos().asLong(),
                target -> BuildCategory.classify(target.state(), world, target.pos()).ordinal());
        SessionRuntime runtime = new SessionRuntime(session, placement, plan, read.unsupported());
        runtime.refreshFromWorld(world);
        return runtime;
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
            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                continue;
            }
            BlockState state = world.getBlockState(pos);
            if (StateMatcher.isComplete(state, target.state())) {
                this.plan.markDone(i);
            } else if (this.plan.status(i) == BuildPlan.Status.DONE) {
                this.plan.markPending(i);
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
        int[] bounds = this.plan.layerBounds(layerIndex);
        for (int i = bounds[0]; i < bounds[1]; i++) {
            if (this.plan.status(i) == BuildPlan.Status.PENDING) {
                BuildTarget target = this.plan.get(i);
                materials.merge(target.item(), target.count(), Integer::sum);
            }
        }
        this.layerCache = Map.copyOf(materials);
        this.layerCacheIndex = layerIndex;
        this.layerCacheTime = now;
        return this.layerCache;
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
     * Items still needed for all pending targets. Cached for a second because HUD and chest overlay ask every frame.
     */
    public Map<Item, Integer> remainingMaterials() {
        long now = System.currentTimeMillis();
        if (this.materialsCache != null && now - this.materialsCacheTime < 1000L) {
            return this.materialsCache;
        }
        Map<Item, Integer> materials = new LinkedHashMap<>();
        for (int i = 0; i < this.plan.size(); i++) {
            if (this.plan.status(i) == BuildPlan.Status.PENDING) {
                BuildTarget target = this.plan.get(i);
                materials.merge(target.item(), target.count(), Integer::sum);
            }
        }
        this.materialsCache = Map.copyOf(materials);
        this.materialsCacheTime = now;
        return this.materialsCache;
    }
}

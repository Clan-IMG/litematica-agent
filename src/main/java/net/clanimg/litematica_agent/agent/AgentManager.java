package net.clanimg.litematica_agent.agent;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.clanimg.litematica_agent.LitematicaAgentClient;
import net.clanimg.litematica_agent.config.AgentConfig;
import net.clanimg.litematica_agent.gui.AgentLockScreen;
import net.clanimg.litematica_agent.gui.AgentScreen;
import net.clanimg.litematica_agent.gui.StockLockScreen;
import net.clanimg.litematica_agent.inventory.InventoryHelper;
import net.clanimg.litematica_agent.persistence.JsonStore;
import net.clanimg.litematica_agent.persistence.WorldData;
import net.clanimg.litematica_agent.schematic.BuildTarget;
import net.clanimg.litematica_agent.schematic.PlacementRef;
import net.clanimg.litematica_agent.schematic.SchematicAccess;
import net.clanimg.litematica_agent.storage.ContainerRecord;
import net.clanimg.litematica_agent.storage.StorageDatabase;
import net.clanimg.litematica_agent.ui.Chat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Owns all sessions of the current world, drives the preparation steps and the active {@link BuildAgent}.
 */
public final class AgentManager {
    private static final AgentManager INSTANCE = new AgentManager();
    private static final long AUTOSAVE_MILLIS = 30_000L;

    public record MaterialStatus(Item item, int needed, int found) {
        public boolean complete() {
            return this.found >= this.needed;
        }
    }

    private AgentConfig config = new AgentConfig();
    private @Nullable String worldKey;
    private WorldData worldData = new WorldData();
    private final StorageDatabase storage = new StorageDatabase();
    private final SafetyMonitor safety = new SafetyMonitor(this);
    private @Nullable SessionRuntime focused;
    private @Nullable BuildAgent agent;
    private @Nullable StockAgent stockAgent;
    private @Nullable SessionState promptedState;
    private boolean dirty;
    private long lastSave;
    private int joinTicks = -1;
    private boolean farStorageWarned;
    private @Nullable List<MaterialStatus> statusCache;
    private long statusCacheTime;

    private AgentManager() {
    }

    public static AgentManager get() {
        return INSTANCE;
    }

    public void init() {
        this.config = JsonStore.loadConfig();
    }

    public AgentConfig config() {
        return this.config;
    }

    public void saveConfig() {
        this.config.sanitize();
        JsonStore.saveConfig(this.config);
    }

    public WorldData worldData() {
        return this.worldData;
    }

    public StorageDatabase storage() {
        return this.storage;
    }

    public @Nullable BuildAgent activeAgent() {
        return this.agent;
    }

    /** Session of the active agent, null if none or only depositing. */
    public @Nullable AgentSession activeSession() {
        return this.agent == null ? null : this.agent.session();
    }

    public @Nullable SessionRuntime focused() {
        return this.focused;
    }

    public List<AgentSession> sessions() {
        return this.worldData.sessions;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public boolean isInWorld() {
        return this.worldKey != null;
    }

    // ---------------------------------------------------------------- world lifecycle

    public void onJoin(MinecraftClient client) {
        this.worldKey = JsonStore.currentWorldKey(client);
        this.worldData = JsonStore.loadWorld(this.worldKey);
        this.storage.load(this.worldData.storage);
        for (AgentSession session : this.worldData.sessions) {
            if (session.state == SessionState.BUILDING) {
                session.setPause("pause.game_left", List.of());
            }
        }
        this.focused = null;
        this.agent = null;
        this.stockAgent = null;
        this.promptedState = null;
        this.farStorageWarned = false;
        this.joinTicks = 0;
        this.safety.reset();
    }

    public void onDisconnect() {
        if (this.agent != null) {
            AgentSession session = this.agent.session();
            if (session != null && session.state == SessionState.BUILDING) {
                session.setPause("pause.game_left", List.of());
            }
            this.agent.suspend();
            this.agent = null;
        }
        if (this.stockAgent != null) {
            this.stockAgent.suspend();
            this.stockAgent = null;
        }
        this.save();
        this.worldKey = null;
        this.focused = null;
    }

    public void save() {
        if (this.worldKey == null) {
            return;
        }
        if (this.focused != null) {
            this.focused.updateCounters();
        }
        this.worldData.storage = this.storage.toList();
        JsonStore.saveWorld(this.worldKey, this.worldData);
        this.dirty = false;
        this.lastSave = System.currentTimeMillis();
    }

    // ---------------------------------------------------------------- tick

    public void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || this.worldKey == null || client.world == null) {
            return;
        }
        if (this.joinTicks >= 0 && ++this.joinTicks == 40) {
            this.joinTicks = -1;
            long paused = this.worldData.sessions.stream().filter(s -> s.state == SessionState.PAUSED).count();
            if (paused > 0) {
                Chat.info(Chat.tr("info.sessions_paused", paused).append(" ")
                        .append(Chat.button(Chat.tr("button.list"), "/agent list", Formatting.AQUA, Chat.tr("hover.list"))));
            }
        }

        if (this.focused != null) {
            SessionState state = this.focused.session().state;
            if (state.isPreparation() || state == SessionState.READY) {
                this.tickPreparation(player);
            }
        }

        if (this.agent != null && this.isAgentActive(this.agent) && this.checkScreen(client)) {
            this.agent.tick();
            this.ensureLockScreen(client);
        }

        if (this.stockAgent != null && this.checkStockScreen(client)) {
            this.stockAgent.tick();
            if (client.currentScreen == null) {
                client.setScreen(new StockLockScreen());
            }
            if (this.stockAgent.isFinished()) {
                this.completeStocking();
            }
        }

        this.safety.tick(client);

        if (this.dirty && System.currentTimeMillis() - this.lastSave > AUTOSAVE_MILLIS) {
            this.save();
        }
    }

    private boolean isAgentActive(BuildAgent candidate) {
        if (candidate.isDepositOnly()) {
            return true;
        }
        AgentSession session = candidate.session();
        return session != null && session.state == SessionState.BUILDING;
    }

    private boolean checkScreen(MinecraftClient client) {
        Screen screen = client.currentScreen;
        if (screen == null || screen instanceof AgentScreen) {
            return true;
        }
        if (screen instanceof HandledScreen<?> && this.agent != null && this.agent.isOperatingContainer()) {
            return true;
        }
        if (screen instanceof ChatScreen || screen instanceof GameMenuScreen) {
            // A chat opened by a clicked [START] link, or the pause menu opened by losing window focus.
            client.setScreen(new AgentLockScreen());
            return true;
        }
        if (screen instanceof DeathScreen) {
            this.pause(this.agent, "pause.died", List.of());
            return false;
        }
        this.pause(this.agent, "pause.screen_opened", List.of(screen.getTitle().getString()));
        return false;
    }

    private void ensureLockScreen(MinecraftClient client) {
        if (this.agent != null && this.isAgentActive(this.agent) && client.currentScreen == null) {
            client.setScreen(new AgentLockScreen());
        }
    }

    /**
     * Same idea as {@link #checkScreen}, but for the chest-stocking helper: any screen the player opens themselves
     * cancels the job instead of pausing it, since there is nothing to resume afterwards.
     */
    private boolean checkStockScreen(MinecraftClient client) {
        Screen screen = client.currentScreen;
        if (screen == null || screen instanceof StockLockScreen) {
            return true;
        }
        if (screen instanceof HandledScreen<?> && this.stockAgent.isOperatingContainer()) {
            return true;
        }
        if (screen instanceof ChatScreen || screen instanceof GameMenuScreen) {
            client.setScreen(new StockLockScreen());
            return true;
        }
        this.stockAgent.cancel("interrupted");
        this.completeStocking();
        return false;
    }

    private void completeStocking() {
        MinecraftClient client = MinecraftClient.getInstance();
        StockAgent finished = this.stockAgent;
        this.stockAgent = null;
        finished.suspend();
        if (client.currentScreen instanceof StockLockScreen) {
            client.setScreen(null);
        }
        if (finished.isFailed()) {
            Chat.warn(Chat.tr("stock.failed", finished.failureReason()));
        } else {
            Chat.success(Chat.tr("stock.done", finished.totalLevels(), finished.totalItemsNeeded()));
        }
    }

    // ---------------------------------------------------------------- preparation (steps 2 to 4)

    private void tickPreparation(ClientPlayerEntity player) {
        AgentSession session = this.focused.session();

        if (session.state.isPreparation() && player.isInCreativeMode()) {
            this.setState(session, SessionState.READY);
        }

        switch (session.state) {
            case CHECK_INVENTORY -> {
                if (inventoryReady(player.getInventory())) {
                    this.setState(session, this.storage.isEmpty() ? SessionState.SCANNING_STORAGE : SessionState.CONFIRM_STORAGE);
                } else if (this.promptedState != SessionState.CHECK_INVENTORY) {
                    this.promptedState = SessionState.CHECK_INVENTORY;
                    Chat.warn(Chat.tr("prepare.empty_inventory"));
                }
            }
            case CONFIRM_STORAGE -> {
                if (this.promptedState != SessionState.CONFIRM_STORAGE) {
                    this.promptedState = SessionState.CONFIRM_STORAGE;
                    Chat.info(Chat.tr("prepare.storage_known", this.storage.size()).append(" ")
                            .append(Chat.button(Chat.tr("button.storage_keep"), "/agent storage keep " + session.id,
                                    Formatting.GREEN, Chat.tr("hover.storage_keep")))
                            .append(" ")
                            .append(Chat.button(Chat.tr("button.storage_rescan"), "/agent storage rescan " + session.id,
                                    Formatting.GOLD, Chat.tr("hover.storage_rescan"))));
                }
            }
            case SCANNING_STORAGE -> {
                if (this.promptedState != SessionState.SCANNING_STORAGE) {
                    this.promptedState = SessionState.SCANNING_STORAGE;
                    Chat.info(Chat.tr("prepare.scan_instructions"));
                }
                if (player.age % 20 == 0 && this.allMaterialsFound()) {
                    this.setState(session, SessionState.READY);
                }
            }
            case READY -> {
                if (this.promptedState != SessionState.READY) {
                    this.promptedState = SessionState.READY;
                    this.sendReadyMessage(session, player.isInCreativeMode());
                }
            }
            default -> {
            }
        }
    }

    private void setState(AgentSession session, SessionState state) {
        if (session.state != state) {
            session.state = state;
            this.markDirty();
        }
    }

    private void sendReadyMessage(AgentSession session, boolean creative) {
        MutableText message = creative ? Chat.tr("prepare.ready_creative", session.id) : Chat.tr("prepare.ready_survival", session.id);
        Chat.success(message.append(" ").append(Chat.button(Chat.tr("button.start"), "/agent begin " + session.id,
                Formatting.GREEN, Chat.tr("hover.start"))));
    }

    public static boolean inventoryReady(PlayerInventory inventory) {
        for (int i = 0; i < InventoryHelper.MAIN_SIZE; i++) {
            if (!InventoryHelper.isAllowedAtStart(inventory.getStack(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Required materials of the focused session compared with storage and inventory.
     */
    public List<MaterialStatus> materialStatus() {
        long now = System.currentTimeMillis();
        if (this.statusCache != null && now - this.statusCacheTime < 250L) {
            return this.statusCache;
        }
        this.statusCache = this.computeMaterialStatus();
        this.statusCacheTime = now;
        return this.statusCache;
    }

    private List<MaterialStatus> computeMaterialStatus() {
        List<MaterialStatus> result = new ArrayList<>();
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (this.focused == null || player == null) {
            return result;
        }
        PlayerInventory inventory = player.getInventory();
        for (Map.Entry<Item, Integer> entry : this.focused.remainingMaterials().entrySet()) {
            Item item = entry.getKey();
            int found = this.storage.total(Registries.ITEM.getId(item).toString()) + InventoryHelper.count(inventory, item);
            result.add(new MaterialStatus(item, entry.getValue(), found));
        }
        result.sort(Comparator.comparing(MaterialStatus::complete).thenComparing(status -> -status.needed()));
        return result;
    }

    public boolean allMaterialsFound() {
        for (MaterialStatus status : this.materialStatus()) {
            if (!status.complete()) {
                return false;
            }
        }
        return true;
    }

    public boolean isNeededMaterial(Item item) {
        return this.focused != null && this.focused.remainingMaterials().containsKey(item);
    }

    /** True while the player is preparing a survival session (inventory check, storage scan). */
    public boolean isPreparing() {
        return this.focused != null && this.focused.session().state.isPreparation();
    }

    // ---------------------------------------------------------------- container scanning

    /**
     * Stores the contents of an opened container in the storage database.
     *
     * @param selected slot ids to record, or null for all container slots
     * @return number of recorded stacks
     */
    public int scanContainer(BlockPos pos, ScreenHandler handler, @Nullable Set<Integer> selected) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return 0;
        }
        String dimension = client.world.getRegistryKey().getValue().toString();
        ContainerRecord record = new ContainerRecord(dimension, pos.getX(), pos.getY(), pos.getZ());
        int stacks = 0;
        for (Slot slot : handler.slots) {
            if (slot.inventory == player.getInventory() || !slot.hasStack()) {
                continue;
            }
            if (selected != null && !selected.contains(slot.id)) {
                continue;
            }
            ItemStack stack = slot.getStack();
            record.add(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount());
            stacks++;
        }
        record.scannedAt = System.currentTimeMillis();
        this.storage.put(record);
        this.markDirty();
        this.warnIfStorageFar(pos);
        return stacks;
    }

    private void warnIfStorageFar(BlockPos pos) {
        if (this.farStorageWarned || this.focused == null || !this.worldData.storageHomeCommand.isEmpty()) {
            return;
        }
        double distance = SchematicAccess.distanceTo(this.focused.placement(), Vec3d.ofCenter(pos));
        if (distance > this.config.homeDistance) {
            this.farStorageWarned = true;
            Chat.warn(Chat.tr("warn.storage_far", (int) distance));
        }
    }

    // ---------------------------------------------------------------- commands

    public void startNew() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return;
        }
        SchematicPlacement placement = this.findPlacementForStart(player);
        if (placement == null) {
            Chat.error(Chat.tr("error.no_placement"));
            return;
        }
        double distance = SchematicAccess.distanceTo(placement, player.getEntityPos());
        if (distance > this.config.maxStartDistance) {
            Chat.error(Chat.tr("error.too_far", (int) distance, this.config.maxStartDistance));
            return;
        }
        PlacementRef ref = PlacementRef.of(placement);
        for (AgentSession existing : this.worldData.sessions) {
            if (existing.placement.hashId().equals(ref.hashId())) {
                Chat.warn(Chat.tr("error.session_exists", existing.id).append(" ").append(this.resumeButton(existing)));
                return;
            }
        }

        this.pauseActiveForSwitch();
        AgentSession session = new AgentSession(this.worldData.nextSessionId++, ref);
        SessionRuntime runtime;
        try {
            runtime = SessionRuntime.load(session, placement, client.world);
        } catch (RuntimeException e) {
            LitematicaAgentClient.LOGGER.error("Could not read schematic {}", placement.getName(), e);
            Chat.error(Chat.tr("error.read_failed", placement.getName()));
            return;
        }
        if (runtime.plan().size() == 0) {
            Chat.error(Chat.tr("error.empty_schematic"));
            return;
        }
        this.worldData.sessions.add(session);
        this.focused = runtime;
        this.promptedState = null;
        this.farStorageWarned = false;

        Chat.success(Chat.tr("info.session_created", session.id, session.name(), runtime.plan().size(), runtime.plan().doneCount()));
        if (!runtime.unsupported().isEmpty()) {
            Chat.warn(Chat.tr("warn.unsupported_blocks", runtime.unsupported().size()));
        }
        if (player.isInCreativeMode()) {
            session.state = SessionState.READY;
            Chat.info(Chat.tr("info.creative_detected"));
        } else {
            session.state = SessionState.CHECK_INVENTORY;
            Chat.info(Chat.tr("info.survival_detected"));
        }
        this.save();
    }

    /**
     * {@code /agent stock}: creative-mode helper that builds a tower of double chests in front of the player and
     * fills each one with the exact materials the loaded schematic needs, so a survival session can withdraw from
     * them right away. Independent of the normal build session/{@link BuildAgent} pipeline.
     */
    public void startStocking() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return;
        }
        if (this.agent != null || this.stockAgent != null) {
            Chat.error(Chat.tr("error.agent_busy"));
            return;
        }
        if (!player.isInCreativeMode()) {
            Chat.error(Chat.tr("stock.creative_required"));
            return;
        }
        SchematicPlacement placement = this.findPlacementForStart(player);
        if (placement == null) {
            Chat.error(Chat.tr("error.no_placement"));
            return;
        }
        SchematicAccess.SchematicReadResult read;
        try {
            read = SchematicAccess.readTargets(placement);
        } catch (RuntimeException e) {
            LitematicaAgentClient.LOGGER.error("Could not read schematic {}", placement.getName(), e);
            Chat.error(Chat.tr("error.read_failed", placement.getName()));
            return;
        }
        Map<Item, Integer> materials = new LinkedHashMap<>();
        for (BuildTarget target : read.targets()) {
            materials.merge(target.item(), target.count(), Integer::sum);
        }
        if (materials.isEmpty()) {
            Chat.error(Chat.tr("stock.no_materials"));
            return;
        }

        StockAgent job = StockAgent.create(this, client, materials);
        this.stockAgent = job;
        job.activate();
        Chat.success(Chat.tr("stock.started", job.totalLevels(), materials.size(), job.totalItemsNeeded()));
        client.setScreen(new StockLockScreen());
    }

    public void cancelStocking() {
        if (this.stockAgent == null) {
            return;
        }
        this.stockAgent.cancel("cancelled");
        this.completeStocking();
    }

    public @Nullable StockAgent stockAgent() {
        return this.stockAgent;
    }

    private @Nullable SchematicPlacement findPlacementForStart(ClientPlayerEntity player) {
        SchematicPlacement selected = SchematicAccess.getSelectedPlacement();
        if (selected != null && selected.isEnabled()) {
            return selected;
        }
        SchematicPlacement best = null;
        double bestDistance = Double.MAX_VALUE;
        for (SchematicPlacement placement : SchematicAccess.getPlacements()) {
            if (!placement.isEnabled()) {
                continue;
            }
            double distance = SchematicAccess.distanceTo(placement, player.getEntityPos());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = placement;
            }
        }
        return best;
    }

    public void resume(int id) {
        AgentSession session = this.findSession(id);
        if (session == null) {
            Chat.error(Chat.tr("error.unknown_session", id));
            return;
        }
        if (this.activeSession() == session && session.state == SessionState.BUILDING) {
            this.ensureLockScreen(MinecraftClient.getInstance());
            return;
        }
        if (!this.focus(session)) {
            return;
        }
        switch (session.state) {
            case PAUSED, READY, BUILDING -> this.beginBuilding(session);
            default -> {
                this.promptedState = null;
                Chat.info(Chat.tr("info.preparation_continued", session.id));
            }
        }
    }

    public void begin(int id) {
        AgentSession session = this.findSession(id);
        if (session == null) {
            Chat.error(Chat.tr("error.unknown_session", id));
            return;
        }
        if (session.state.isPreparation()) {
            Chat.warn(Chat.tr("error.not_ready", id));
            return;
        }
        if (this.activeSession() == session && session.state == SessionState.BUILDING) {
            return;
        }
        if (!this.focus(session)) {
            return;
        }
        this.beginBuilding(session);
    }

    private boolean focus(AgentSession session) {
        if (this.focused != null && this.focused.session() == session) {
            return true;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        Optional<SchematicPlacement> placement = SchematicAccess.find(session.placement);
        if (placement.isEmpty()) {
            Chat.error(Chat.tr("error.placement_missing", session.id, session.name()));
            return false;
        }
        this.pauseActiveForSwitch();
        try {
            this.focused = SessionRuntime.load(session, placement.get(), client.world);
        } catch (RuntimeException e) {
            LitematicaAgentClient.LOGGER.error("Could not read schematic {}", session.name(), e);
            Chat.error(Chat.tr("error.read_failed", session.name()));
            return false;
        }
        this.promptedState = null;
        return true;
    }

    private void beginBuilding(AgentSession session) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || this.focused == null) {
            return;
        }
        double distance = SchematicAccess.distanceTo(this.focused.placement(), client.player.getEntityPos());
        boolean useHome = distance > this.config.maxStartDistance;
        if (useHome && this.worldData.buildHomeCommand.isEmpty()) {
            Chat.error(Chat.tr("error.too_far", (int) distance, this.config.maxStartDistance));
            return;
        }
        this.focused.refreshFromWorld(client.world);
        if (this.agent == null || this.agent.runtime() != this.focused) {
            if (this.agent != null) {
                this.agent.suspend();
            }
            this.agent = new BuildAgent(this, this.focused, client);
        }
        session.state = SessionState.BUILDING;
        session.pauseReason = "";
        session.pauseArgs = new ArrayList<>();
        this.safety.reset();
        this.agent.activate();
        if (useHome) {
            this.agent.travelHome(this.worldData.buildHomeCommand);
        }
        Chat.success(Chat.tr("info.building_started", session.id, session.name()));
        this.save();
        client.setScreen(new AgentLockScreen());
    }

    /**
     * {@code /agent stop [id]}: pauses a session.
     */
    public void stop(@Nullable Integer id) {
        AgentSession active = this.activeSession();
        if (id == null) {
            if (active == null || active.state != SessionState.BUILDING) {
                Chat.warn(Chat.tr("error.nothing_running"));
                return;
            }
            this.pause(this.agent, "", List.of());
            return;
        }
        AgentSession session = this.findSession(id);
        if (session == null) {
            Chat.error(Chat.tr("error.unknown_session", id));
            return;
        }
        if (active == session) {
            this.pause(this.agent, "", List.of());
        } else if (session.state == SessionState.BUILDING) {
            session.setPause("", List.of());
            this.save();
        } else {
            Chat.info(Chat.tr("info.not_running", id));
        }
    }

    /**
     * Pauses the building session. The lock screen stays open and shows the reason until the player closes it.
     *
     * @param reason translation key below {@code litematica_agent.}, empty for a manual pause
     */
    public void pause(@Nullable BuildAgent target, String reason, List<String> args) {
        if (target == null) {
            return;
        }
        target.suspend();
        AgentSession session = target.session();
        if (session == null) {
            if (this.agent == target) {
                this.agent = null;
            }
            return;
        }
        session.setPause(reason, args);
        this.safety.onPaused(reason.startsWith("pause.damage"));
        if (target.runtime() != null) {
            target.runtime().updateCounters();
        }
        this.save();
        if (reason.isEmpty()) {
            Chat.info(Chat.tr("info.paused", session.id).append(" ").append(this.resumeButton(session)));
        } else {
            Chat.warn(Chat.tr("info.paused_reason", session.id).append(Chat.trList(reason, args))
                    .append(" ").append(this.resumeButton(session)));
        }
    }

    public void pauseActive() {
        if (this.agent != null) {
            this.pause(this.agent, "", List.of());
        }
    }

    /**
     * Answer to a wrong block in "ask for approval" mode: approved blocks get broken, rejected ones stay and their
     * target is skipped. Building continues either way.
     */
    public void answerApproval(boolean approved) {
        AgentSession session = this.activeSession();
        if (this.agent == null || session == null || !this.agent.answerPendingApproval(approved)) {
            return;
        }
        this.resume(session.id);
    }

    private MutableText resumeButton(AgentSession session) {
        return Chat.button(Chat.tr("button.resume"), "/agent start " + session.id, Formatting.GREEN, Chat.tr("hover.resume"));
    }

    private void pauseActiveForSwitch() {
        if (this.agent != null) {
            AgentSession session = this.agent.session();
            if (session != null && session.state == SessionState.BUILDING) {
                this.pause(this.agent, "pause.switched", List.of());
            }
            this.agent.suspend();
            this.agent = null;
        }
    }

    public void cancel(int id, boolean confirmed) {
        AgentSession session = this.findSession(id);
        if (session == null) {
            Chat.error(Chat.tr("error.unknown_session", id));
            return;
        }
        if (!confirmed) {
            Chat.warn(Chat.tr("confirm.cancel", id, session.name()).append(" ")
                    .append(Chat.button(Chat.tr("button.confirm_cancel"), "/agent cancel " + id + " confirm", Formatting.RED,
                            Chat.tr("hover.confirm_cancel"))));
            return;
        }
        this.cancelNow(session);
    }

    public void cancelNow(AgentSession session) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (this.agent != null && this.agent.session() == session) {
            this.agent.suspend();
            this.agent = null;
        }
        if (this.focused != null && this.focused.session() == session) {
            this.focused = null;
        }
        this.worldData.sessions.remove(session);
        if (client.currentScreen instanceof AgentScreen) {
            client.setScreen(null);
        }
        Chat.info(Chat.tr("info.cancelled", session.id, session.name()));
        this.save();
    }

    void complete(BuildAgent finished) {
        MinecraftClient client = MinecraftClient.getInstance();
        AgentSession session = finished.session();
        finished.suspend();
        if (this.agent == finished) {
            this.agent = null;
        }
        if (session == null) {
            return;
        }
        this.worldData.sessions.remove(session);
        this.focused = null;
        if (client.currentScreen instanceof AgentScreen) {
            client.setScreen(null);
        }

        Chat.success(Chat.tr("info.completed", session.name(), Chat.formatDuration(session.activeMillis),
                Chat.formatDuration(System.currentTimeMillis() - session.createdAt), session.placedBlocks));
        Map<Long, String> failures = finished.failures();
        if (!failures.isEmpty()) {
            Chat.warn(Chat.tr("warn.failed_blocks", failures.size()));
            int shown = 0;
            for (Map.Entry<Long, String> failure : failures.entrySet()) {
                BlockPos pos = BlockPos.fromLong(failure.getKey());
                Chat.warn(Text.literal(" - " + pos.toShortString() + ": ").append(Chat.tr("reason." + reasonKey(failure.getValue()))));
                if (++shown >= 5) {
                    break;
                }
            }
        }
        ClientPlayerEntity player = client.player;
        if (player != null && !player.isInCreativeMode() && hasLeftovers(player.getInventory()) && !this.storage.isEmpty()) {
            Chat.info(Chat.tr("info.leftovers").append(" ").append(Chat.button(Chat.tr("button.deposit"), "/agent deposit",
                    Formatting.AQUA, Chat.tr("hover.deposit"))));
        }
        this.save();
    }

    /**
     * Failure reasons such as {@code path_stuck} are grouped to a few user facing explanations.
     */
    static String reasonKey(String reason) {
        if (reason == null || reason.isEmpty()) {
            return "unknown";
        }
        if (reason.startsWith("path_")) {
            return "path";
        }
        return switch (reason) {
            case "no_support", "falling_without_support", "waiting_for_support" -> "no_support";
            case "no_stand_spot", "unreachable" -> "unreachable";
            case "no_placement_option", "crosshair_mismatch", "aim_timeout" -> "no_placement_option";
            case "placement_not_confirmed", "player_in_the_way" -> "not_confirmed";
            case "helper_not_removed" -> "helper_not_removed";
            case "interaction_failed", "state_changed" -> "interaction_failed";
            case "break_timeout" -> "break_timeout";
            case "wrong_block" -> "wrong_block";
            default -> "unknown";
        };
    }

    private static boolean hasLeftovers(PlayerInventory inventory) {
        for (int i = 0; i < InventoryHelper.MAIN_SIZE; i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty() && !InventoryHelper.isTool(stack) && !InventoryHelper.isFood(stack)) {
                return true;
            }
        }
        return false;
    }

    public void deposit() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return;
        }
        if (this.agent != null) {
            Chat.warn(Chat.tr("error.agent_busy"));
            return;
        }
        BuildAgent helper = BuildAgent.depositOnly(this, client);
        if (!helper.startDepositNow()) {
            helper.suspend();
            Chat.info(Chat.tr("info.nothing_to_deposit"));
            return;
        }
        this.agent = helper;
        client.setScreen(new AgentLockScreen());
    }

    void finishDeposit(BuildAgent finished) {
        MinecraftClient client = MinecraftClient.getInstance();
        finished.suspend();
        if (this.agent == finished) {
            this.agent = null;
        }
        if (client.currentScreen instanceof AgentScreen) {
            client.setScreen(null);
        }
        this.save();
        Chat.success(Chat.tr("info.deposit_done"));
    }

    public void storageKeep(int id) {
        AgentSession session = this.findSession(id);
        if (session == null || session.state != SessionState.CONFIRM_STORAGE) {
            return;
        }
        if (!this.focus(session)) {
            return;
        }
        if (this.allMaterialsFound()) {
            this.setState(session, SessionState.READY);
        } else {
            Chat.warn(Chat.tr("prepare.storage_incomplete"));
            this.setState(session, SessionState.SCANNING_STORAGE);
        }
    }

    public void storageRescan(@Nullable Integer id) {
        this.storage.clear();
        this.markDirty();
        Chat.info(Chat.tr("info.storage_cleared"));
        if (id == null) {
            return;
        }
        AgentSession session = this.findSession(id);
        if (session != null && session.state.isPreparation() && this.focus(session)) {
            this.setState(session, SessionState.SCANNING_STORAGE);
        }
    }

    public boolean isBuilding(BuildAgent candidate) {
        AgentSession session = candidate.session();
        return this.agent == candidate && session != null && session.state == SessionState.BUILDING;
    }

    public @Nullable AgentSession findSession(int id) {
        for (AgentSession session : this.worldData.sessions) {
            if (session.id == id) {
                return session;
            }
        }
        return null;
    }

    public void emergencyDisconnect(String reasonKey, float health) {
        MinecraftClient client = MinecraftClient.getInstance();
        LitematicaAgentClient.LOGGER.warn("Emergency disconnect: {} (health {})", reasonKey, health);
        if (this.agent != null) {
            AgentSession session = this.agent.session();
            if (session != null) {
                session.setPause("pause.emergency", List.of());
            }
            this.agent.suspend();
            this.agent = null;
        }
        this.save();
        client.execute(() -> client.disconnect(Chat.tr("disconnect.emergency", Chat.tr(reasonKey))));
    }
}

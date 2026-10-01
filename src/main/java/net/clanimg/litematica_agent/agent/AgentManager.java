package net.clanimg.litematica_agent.agent;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.clanimg.litematica_agent.LitematicaAgentClient;
import net.clanimg.litematica_agent.config.AgentConfig;
import net.clanimg.litematica_agent.gui.AgentLockScreen;
import net.clanimg.litematica_agent.gui.AgentScreen;
import net.clanimg.litematica_agent.gui.PathParticles;
import net.clanimg.litematica_agent.gui.StockLockScreen;
import net.clanimg.litematica_agent.inventory.InventoryHelper;
import net.clanimg.litematica_agent.movement.MovementController;
import net.clanimg.litematica_agent.movement.RotationController;
import net.clanimg.litematica_agent.persistence.JsonStore;
import net.clanimg.litematica_agent.persistence.WorldData;
import net.clanimg.litematica_agent.placement.StateMatcher;
import net.clanimg.litematica_agent.schematic.BuildTarget;
import net.clanimg.litematica_agent.schematic.PlacementRef;
import net.clanimg.litematica_agent.schematic.SchematicAccess;
import net.clanimg.litematica_agent.storage.ContainerRecord;
import net.clanimg.litematica_agent.storage.StorageDatabase;
import net.clanimg.litematica_agent.storage.StorageHomes;
import net.clanimg.litematica_agent.ui.Chat;
import net.clanimg.litematica_agent.ui.Messages;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Owns all sessions of the current world, drives the preparation steps and the active {@link BuildAgent}.
 */
public final class AgentManager {
    private static final AgentManager INSTANCE = new AgentManager();
    private static final long AUTOSAVE_MILLIS = 30_000L;
    private static final int FOOD_RESERVE = 64;
    private static final int HELPER_RESERVE = 64;

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
    /** A schematic being read in the background; one at a time. */
    private @Nullable CompletableFuture<?> loading;
    private @Nullable SessionState promptedState;
    private boolean dirty;
    private long lastSave;
    private int joinTicks = -1;
    private boolean farStorageWarned;
    private @Nullable List<MaterialStatus> statusCache;
    private long statusCacheTime;
    /** Set while the agent waits for the player to set a home at the storage, see {@link #onCommandSent}. */
    private boolean awaitingStorageHome;
    /** A /sethome within this distance of a scanned chest counts as the home of that storage. */
    private static final double STORAGE_HOME_RADIUS = 16.0;
    /** Whether the lock screen was deliberately closed for the player to watch in freecam; see {@link #allowFreecam}. */
    private boolean freecamGrace;
    private boolean freecamKeyWasDown;
    private long lastFrameNanos;
    /**
     * How soon a paused session quietly tries again by itself, see {@link #tryAutoRecover}. Each try that ends in
     * the same problem doubles the wait up to {@link #MAX_AUTO_RETRY_INTERVAL_MILLIS}: something the player fixes is
     * noticed within seconds, something that cannot be fixed is not recomputed every few seconds for hours.
     */
    private static final long AUTO_RETRY_INTERVAL_MILLIS = 5_000L;
    private static final long MAX_AUTO_RETRY_INTERVAL_MILLIS = 30_000L;
    /**
     * Reasons that need the player's own decision and are never retried automatically - including the end of a build
     * where everything else stands and the rest failed even after the recovery rounds: trying those again and again
     * on its own would only take the controls away from the player every few minutes for nothing.
     */
    private static final Set<String> NO_AUTO_RETRY_REASONS = Set.of(
            "", "pause.died", "pause.emergency", "pause.switched", "pause.screen_opened", "pause.selection_done",
            "pause.blocks_failed");
    private long nextAutoRetryMillis = Long.MAX_VALUE;
    private long autoRetryDelayMillis = AUTO_RETRY_INTERVAL_MILLIS;
    /** Set while {@link #tryAutoRecover} probes a paused session, so a repeat of the very same problem stays quiet. */
    private boolean autoRetryProbing;
    /**
     * Set by an automatic retry until the next pause: that pause is a repeat when it names the same problem as the
     * one the retry started from, whether it comes in the same tick or after a walk and a few placements.
     */
    private boolean autoRetryCycle;
    private String autoRetryPrevReason = "";
    private List<String> autoRetryPrevArgs = List.of();

    private AgentManager() {
    }

    public static AgentManager get() {
        return INSTANCE;
    }

    public void init() {
        this.config = JsonStore.loadConfig();
        Messages.load(this.config.language);
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
        this.cancelLoading();
        this.promptedState = null;
        this.farStorageWarned = false;
        this.joinTicks = 0;
        this.safety.reset();
    }

    /** A schematic still being read belongs to the world that was left: its result is dropped. */
    private void cancelLoading() {
        if (this.loading != null) {
            this.loading.cancel(false);
            this.loading = null;
        }
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
        this.cancelLoading();
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
        if (this.agent != null) {
            this.agent.storeHelpers();
        }
        this.worldData.storage = this.storage.toList();
        JsonStore.saveWorld(this.worldKey, this.worldData);
        this.dirty = false;
        this.lastSave = System.currentTimeMillis();
    }

    /** The agent asked for a home at the storage (pause.storage_home_required); see {@link #onCommandSent}. */
    public void awaitStorageHome() {
        this.awaitingStorageHome = true;
    }

    /**
     * Every command the player sends. While the agent waits for a storage home, the server's own {@code /sethome}
     * typed right at the storage is taken as that home, so there is nothing else to register.
     */
    public void onCommandSent(String command) {
        String[] parts = command.trim().split("\\s+");
        if (!this.awaitingStorageHome || parts.length == 0 || !parts[0].equalsIgnoreCase("sethome")) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return;
        }
        String dimension = client.world.getRegistryKey().getValue().toString();
        ContainerRecord nearest = null;
        double closest = Double.MAX_VALUE;
        for (ContainerRecord container : this.storage.containers()) {
            double distance = container.distanceSq(client.player.getX(), client.player.getY(), client.player.getZ());
            if (container.dimension.equals(dimension) && distance < closest) {
                nearest = container;
                closest = distance;
            }
        }
        if (nearest == null) {
            return;
        }
        if (closest > STORAGE_HOME_RADIUS * STORAGE_HOME_RADIUS) {
            Chat.warn(Chat.tr("home.storage_too_far", (int) Math.sqrt(closest)));
            return;
        }
        String home = parts.length >= 2 ? "home " + parts[1] : "home";
        StorageHomes.configure(this.worldData, nearest, home);
        this.awaitingStorageHome = false;
        this.save();
        MutableText message = Chat.tr("home.storage_detected", "/" + home);
        if (this.focused != null && this.focused.session().state == SessionState.PAUSED) {
            message.append(" ").append(this.resumeButton(this.focused.session()));
        }
        Chat.success(message);
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
                        .append(Chat.button(Chat.tr("button.list"), "/agent list", Chat.tr("hover.list"))));
            }
        }

        if (this.focused != null) {
            SessionState state = this.focused.session().state;
            if (state.isPreparation() || state == SessionState.READY) {
                this.tickPreparation(player);
            }
        }

        this.tryAutoRecover(client);
        if (this.agent != null && this.isAgentActive(this.agent) && this.checkScreen(client)) {
            this.agent.tick();
            // tick() may finish or fail the session as a side effect, which clears this.agent.
            if (this.agent != null) {
                PathParticles.tick(this.agent);
            }
            this.ensureLockScreen(client);
        }
        // The flag must survive through the tick() call above, since a re-pause caused by the probe happens there,
        // not inside tryAutoRecover itself; only now is it safe to drop back to normal (always-announce) pausing.
        this.autoRetryProbing = false;

        if (this.stockAgent != null && !this.stockAgent.isPaused() && this.checkStockScreen(client)) {
            this.stockAgent.tick();
            if (this.stockAgent.isPaused()) {
                // A step did not work out: the lock screen shows why, the chat offers to continue or cancel.
                this.announceStockPause();
            }
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

    /**
     * Called every rendered frame: turns the camera of a working agent in small steps, at most {@code agentFps} times
     * per second. Movement itself stays tied to the 20 game ticks, like for every player.
     */
    public void onFrame() {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null || this.config.agentFps <= AgentConfig.MIN_AGENT_FPS) {
            this.lastFrameNanos = 0L;
            return;
        }
        RotationController rotation = null;
        if (this.agent != null && this.isAgentActive(this.agent)) {
            rotation = this.agent.rotation();
        } else if (this.stockAgent != null && !this.stockAgent.isPaused()) {
            rotation = this.stockAgent.rotation();
        }
        if (rotation == null) {
            this.lastFrameNanos = 0L;
            return;
        }
        long now = System.nanoTime();
        if (this.lastFrameNanos != 0L && now - this.lastFrameNanos < 1_000_000_000L / this.config.agentFps) {
            return;
        }
        // A long gap (first frame, lag) is capped so the camera never jumps.
        double ticks = this.lastFrameNanos == 0L ? 0.0 : Math.min(2.0, (now - this.lastFrameNanos) / 50_000_000.0);
        this.lastFrameNanos = now;
        rotation.frame(player, ticks);
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
        if (client.currentScreen != null || this.agent == null || !this.isAgentActive(this.agent)) {
            return;
        }
        if (this.freecamGrace) {
            this.checkFreecamToggleOff(client);
            return;
        }
        client.setScreen(new AgentLockScreen());
    }

    /**
     * Lets the player leave the lock screen closed to watch in freecam (F4) while the agent keeps building; see
     * {@link net.clanimg.litematica_agent.gui.AgentLockScreen#keyPressed}.
     */
    public void allowFreecam() {
        this.freecamGrace = true;
        this.freecamKeyWasDown = true;
    }

    /**
     * Freecam has no API to ask whether it is still active, so the same key toggling it back off - while no screen
     * is open, since freecam itself does not open one - is the only signal available to bring the panel back.
     */
    private void checkFreecamToggleOff(MinecraftClient client) {
        boolean down = InputUtil.isKeyPressed(client.getWindow(), InputUtil.GLFW_KEY_F4);
        if (down && !this.freecamKeyWasDown) {
            this.freecamGrace = false;
        }
        this.freecamKeyWasDown = down;
    }

    /**
     * Same idea as {@link #checkScreen}, but for the chest-stocking helper: a screen the player opens pauses the job,
     * which can be continued afterwards.
     */
    private boolean checkStockScreen(MinecraftClient client) {
        Screen screen = client.currentScreen;
        if (screen == null || screen instanceof AgentScreen) {
            return true;
        }
        if (screen instanceof HandledScreen<?> && this.stockAgent.isOperatingContainer()) {
            return true;
        }
        if (screen instanceof ChatScreen || screen instanceof GameMenuScreen) {
            client.setScreen(new StockLockScreen());
            return true;
        }
        LitematicaAgentClient.LOGGER.info("Chest stocking paused: {} was opened", screen.getClass().getName());
        this.stockAgent.pause("interrupted");
        this.announceStockPause();
        return false;
    }

    /** Pause button of the stock lock screen; the screen stays open and offers to continue. */
    public void pauseStocking() {
        if (this.stockAgent != null && !this.stockAgent.isPaused()) {
            this.stockAgent.pause("");
        }
    }

    /** Leave button of the stock lock screen: the job pauses and the player gets the controls back. */
    public void leaveStocking() {
        if (this.stockAgent != null) {
            this.pauseStocking();
            this.announceStockPause();
        }
        MinecraftClient.getInstance().setScreen(null);
    }

    public void resumeStocking() {
        if (this.stockAgent == null) {
            Chat.error(Chat.tr("error.nothing_running"));
            return;
        }
        if (this.agent != null) {
            Chat.error(Chat.tr("error.agent_busy"));
            return;
        }
        if (this.stockAgent.isPaused()) {
            this.stockAgent.resume();
        }
        MinecraftClient.getInstance().setScreen(new StockLockScreen());
    }

    private void announceStockPause() {
        StockAgent job = this.stockAgent;
        MutableText message = job.pauseReason().isEmpty() ? Chat.tr("stock.paused")
                : Chat.tr("stock.paused_reason", reasonText(job.pauseReason()));
        Chat.info(message.append(" ")
                .append(Chat.button(Chat.tr("button.resume"), "/agent stock resume", Chat.tr("hover.stock_resume")))
                .append(" ")
                .append(Chat.button(Chat.tr("button.cancel"), "/agent stock cancel", Chat.tr("hover.stock_cancel"))));
    }

    private void completeStocking() {
        MinecraftClient client = MinecraftClient.getInstance();
        StockAgent finished = this.stockAgent;
        this.stockAgent = null;
        finished.suspend();
        if (client.currentScreen instanceof StockLockScreen) {
            client.setScreen(null);
        }
        if (finished.isFailed() && "cancelled".equals(finished.failureReason())) {
            Chat.info(Chat.tr("stock.cancelled"));
        } else if (finished.isFailed()) {
            Chat.warn(Chat.tr("stock.failed", reasonText(finished.failureReason())));
        } else {
            Chat.success(Chat.tr("stock.done", finished.totalLevels(), finished.totalItemsNeeded()));
        }
    }

    // ---------------------------------------------------------------- preparation (steps 2 to 4)

    private void tickPreparation(ClientPlayerEntity player) {
        AgentSession session = this.focused.session();

        if (session.state.isPreparation() && session.state != SessionState.CONFIRM_SKIPS && player.isInCreativeMode()) {
            this.setState(session, SessionState.READY);
        }

        switch (session.state) {
            case CONFIRM_SKIPS -> {
                if (this.promptedState != SessionState.CONFIRM_SKIPS) {
                    this.promptedState = SessionState.CONFIRM_SKIPS;
                    this.promptSkips(session);
                }
            }
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
                                    Chat.tr("hover.storage_keep")))
                            .append(" ")
                            .append(Chat.button(Chat.tr("button.storage_rescan"), "/agent storage rescan " + session.id,
                                    Chat.tr("hover.storage_rescan"))));
                }
            }
            case SCANNING_STORAGE -> {
                if (this.promptedState != SessionState.SCANNING_STORAGE) {
                    this.promptedState = SessionState.SCANNING_STORAGE;
                    Chat.info(Chat.tr("prepare.scan_instructions"));
                    Chat.info(Chat.tr("prepare.start_anytime").append(" ").append(Chat.button(Chat.tr("button.start"),
                            "/agent begin " + session.id, Chat.tr("hover.start_partial"))));
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
                Chat.tr("hover.start"))));
    }

    private void promptSkips(AgentSession session) {
        List<SchematicAccess.UnsupportedBlock> blocks = this.focused.unsupported();
        Chat.warn(Chat.tr("skip.title", blocks.size()));
        int shown = 0;
        for (SchematicAccess.UnsupportedBlock block : blocks) {
            if (shown++ == 5) {
                Chat.send(Chat.tr("skip.more", blocks.size() - 5));
                break;
            }
            Chat.send(Chat.tr("skip.line", block.state().getBlock().getName(), block.pos().toShortString(),
                    Chat.tr("unsupported." + block.reason().name().toLowerCase(Locale.ROOT))));
        }
        Chat.info(Chat.tr("skip.question").append(" ")
                .append(Chat.button(Chat.tr("button.accept_skip"), "/agent accept " + session.id, Chat.tr("hover.accept_skip")))
                .append(" ")
                .append(Chat.button(Chat.tr("button.cancel"), "/agent cancel " + session.id, Chat.tr("hover.cancel"))));
    }

    /**
     * {@code /agent accept <id>}: the player agreed that the blocks which cannot be built are left out.
     */
    public void acceptSkips(int id) {
        AgentSession session = this.findSession(id);
        if (session == null || session.state != SessionState.CONFIRM_SKIPS) {
            return;
        }
        this.focusThen(session, () -> {
            ClientPlayerEntity player = MinecraftClient.getInstance().player;
            if (player == null || session.state != SessionState.CONFIRM_SKIPS) {
                return;
            }
            Chat.info(Chat.tr("skip.accepted", this.focused.unsupported().size()));
            this.beginPreparation(session, player.isInCreativeMode());
        });
    }

    private void beginPreparation(AgentSession session, boolean creative) {
        this.setState(session, creative ? SessionState.READY : SessionState.CHECK_INVENTORY);
        this.promptedState = null;
        Chat.info(Chat.tr(creative ? "info.creative_detected" : "info.survival_detected"));
        Set<StateMatcher.Tool> tools = this.focused == null ? Set.of() : this.focused.toolsAtStart();
        if (!creative && !tools.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (StateMatcher.Tool tool : tools) {
                names.add(Chat.tr("tool." + tool.name().toLowerCase(Locale.ROOT)).getString());
            }
            Chat.info(Chat.tr("prepare.tools_needed", String.join(", ", names)));
        }
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
     * Stores the selected slots of an opened container in the storage database. Only these slots are used later.
     *
     * @param selected slot ids (of the handler) to record
     * @return number of recorded stacks
     */
    public int scanContainer(BlockPos pos, ScreenHandler handler, Set<Integer> selected) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return 0;
        }
        String dimension = client.world.getRegistryKey().getValue().toString();
        ContainerRecord record = new ContainerRecord(dimension, pos.getX(), pos.getY(), pos.getZ());
        record.slots = new ArrayList<>();
        int stacks = 0;
        for (Slot slot : handler.slots) {
            if (slot.inventory == player.getInventory() || !slot.hasStack() || !selected.contains(slot.id)) {
                continue;
            }
            ItemStack stack = slot.getStack();
            record.slots.add(slot.getIndex());
            record.add(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount());
            stacks++;
        }
        record.scannedAt = System.currentTimeMillis();
        this.storage.put(record);
        this.markDirty();
        this.warnIfStorageFar(pos);
        return stacks;
    }

    /**
     * The slots "Scan all" selects. With a focused session only as many stacks as the build still needs beyond the
     * inventory and the other known containers, plus every tool and small reserves of food and (without flight)
     * helper blocks. Without a session every slot.
     *
     * @return slot ids of the handler
     */
    public Set<Integer> autoSelectSlots(BlockPos pos, ScreenHandler handler) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        Set<Integer> selected = new LinkedHashSet<>();
        if (player == null || MinecraftClient.getInstance().world == null) {
            return selected;
        }
        PlayerInventory inventory = player.getInventory();
        String ownKey = ContainerRecord.key(MinecraftClient.getInstance().world.getRegistryKey().getValue().toString(),
                pos.getX(), pos.getY(), pos.getZ());
        Map<String, Integer> elsewhere = new HashMap<>();
        for (ContainerRecord record : this.storage.containers()) {
            if (!record.key().equals(ownKey)) {
                record.items.forEach((id, count) -> elsewhere.merge(id, count, Integer::sum));
            }
        }

        Map<Item, Integer> open = new HashMap<>();
        if (this.focused != null) {
            for (Map.Entry<Item, Integer> entry : this.focused.remainingMaterials().entrySet()) {
                Item item = entry.getKey();
                int missing = entry.getValue() - InventoryHelper.count(inventory, item)
                        - elsewhere.getOrDefault(Registries.ITEM.getId(item).toString(), 0);
                if (missing > 0) {
                    open.put(item, missing);
                }
            }
        }
        int food = FOOD_RESERVE;
        int helpers = this.config.useHelperBlocks && !player.getAbilities().allowFlying ? HELPER_RESERVE : 0;
        Set<String> helperIds = Set.copyOf(this.config.helperBlocks);
        for (int i = 0; i < InventoryHelper.MAIN_SIZE; i++) {
            ItemStack stack = inventory.getStack(i);
            if (InventoryHelper.isFood(stack)) {
                food -= stack.getCount();
            } else if (helperIds.contains(Registries.ITEM.getId(stack.getItem()).toString())) {
                helpers -= stack.getCount();
            }
        }
        for (Map.Entry<String, Integer> entry : elsewhere.entrySet()) {
            if (helperIds.contains(entry.getKey())) {
                helpers -= entry.getValue();
            } else if (InventoryHelper.isFood(new ItemStack(Registries.ITEM.get(Identifier.tryParse(entry.getKey()))))) {
                food -= entry.getValue();
            }
        }

        for (Slot slot : handler.slots) {
            if (slot.inventory == inventory || !slot.hasStack()) {
                continue;
            }
            ItemStack stack = slot.getStack();
            if (this.focused == null) {
                selected.add(slot.id);
                continue;
            }
            int missing = open.getOrDefault(stack.getItem(), 0);
            if (missing > 0) {
                open.put(stack.getItem(), missing - stack.getCount());
                selected.add(slot.id);
            } else if (InventoryHelper.isUtility(stack)) {
                selected.add(slot.id);
            } else if (InventoryHelper.isFood(stack) && food > 0) {
                food -= stack.getCount();
                selected.add(slot.id);
            } else if (helpers > 0 && helperIds.contains(Registries.ITEM.getId(stack.getItem()).toString())) {
                helpers -= stack.getCount();
                selected.add(slot.id);
            }
        }
        return selected;
    }

    /**
     * The player closed a known container: slots they filled become part of the selection and the counts follow what
     * is in the container now, so items put back by hand are used again.
     *
     * @param before contents when the container was opened, by slot index
     */
    public void refreshContainer(BlockPos pos, ScreenHandler handler, Map<Integer, ItemStack> before) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return;
        }
        ContainerRecord record = this.storage.get(ContainerRecord.key(client.world.getRegistryKey().getValue().toString(),
                pos.getX(), pos.getY(), pos.getZ()));
        if (record == null) {
            return;
        }
        record.items.clear();
        for (Slot slot : handler.slots) {
            if (slot.inventory == player.getInventory() || !slot.hasStack()) {
                continue;
            }
            ItemStack stack = slot.getStack();
            ItemStack old = before.getOrDefault(slot.getIndex(), ItemStack.EMPTY);
            boolean filled = !ItemStack.areItemsEqual(old, stack) || stack.getCount() > old.getCount();
            if (filled && record.slots != null && !record.slots.contains(slot.getIndex())) {
                record.slots.add(slot.getIndex());
            }
            if (record.allows(slot.getIndex())) {
                record.add(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount());
            }
        }
        record.scannedAt = System.currentTimeMillis();
        this.markDirty();
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

        if (this.stillLoading()) {
            return;
        }
        this.pauseActiveForSwitch();
        AgentSession session = new AgentSession(this.worldData.nextSessionId++, ref);
        this.loadSession(session, placement, runtime -> {
            ClientPlayerEntity current = MinecraftClient.getInstance().player;
            if (current == null) {
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
            if (runtime.unsupported().isEmpty()) {
                this.beginPreparation(session, current.isInCreativeMode());
            } else {
                // Asked in tickPreparation, before any inventory or storage step.
                session.state = SessionState.CONFIRM_SKIPS;
            }
            this.save();
        });
    }

    /** Whether a schematic is being read in the background right now. */
    public boolean isLoading() {
        return this.loading != null && !this.loading.isDone();
    }

    /** True while a schematic is still being read; the player is asked to wait for it. */
    private boolean stillLoading() {
        if (this.loading != null && !this.loading.isDone()) {
            Chat.warn(Chat.tr("error.loading_busy"));
            return true;
        }
        return false;
    }

    /**
     * Reads the schematic of a session on background threads, so the game keeps running even with huge schematics.
     * {@code then} gets the runtime on the render thread, unless the world was left meanwhile.
     */
    private void loadSession(AgentSession session, SchematicPlacement placement, Consumer<SessionRuntime> then) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        String worldKey = this.worldKey;
        long start = System.currentTimeMillis();
        Chat.info(Chat.tr("info.loading", placement.getName()));
        this.loading = SchematicLoader.load(client, placement, world, read -> SessionRuntime.create(session, placement, read, world))
                .whenCompleteAsync((runtime, error) -> {
                    if (!Objects.equals(worldKey, this.worldKey) || client.world != world) {
                        return;
                    }
                    if (error != null) {
                        LitematicaAgentClient.LOGGER.error("Could not read schematic {}", placement.getName(), error);
                        Chat.error(Chat.tr("error.read_failed", placement.getName()));
                        return;
                    }
                    Chat.info(Chat.tr("info.loaded", runtime.plan().size(),
                            String.format(Locale.ROOT, "%.1f", (System.currentTimeMillis() - start) / 1000.0)));
                    runLogged(() -> then.accept(runtime));
                }, client);
    }

    /** Errors in a step that runs after background work would otherwise vanish inside the future. */
    private static void runLogged(Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            LitematicaAgentClient.LOGGER.error("Litematica Agent step after reading a schematic failed", e);
            Chat.error(Chat.tr("reason.unknown"));
        }
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
        if (this.stockAgent != null && this.stockAgent.isPaused() && this.agent == null) {
            this.resumeStocking();
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
        if (this.stillLoading()) {
            return;
        }
        // The same material list as a survival session of this schematic, e.g. dirt where grass would turn into dirt
        // under a block, so the stocked chests hold exactly what the agent will look for. Read in the background.
        ClientWorld world = client.world;
        String worldKey = this.worldKey;
        Chat.info(Chat.tr("info.loading", placement.getName()));
        this.loading = SchematicLoader.load(client, placement, world, read -> read)
                .whenCompleteAsync((read, error) -> {
                    if (!Objects.equals(worldKey, this.worldKey) || client.world != world) {
                        return;
                    }
                    if (error != null) {
                        LitematicaAgentClient.LOGGER.error("Could not read schematic {}", placement.getName(), error);
                        Chat.error(Chat.tr("error.read_failed", placement.getName()));
                        return;
                    }
                    runLogged(() -> this.startStocking(read));
                }, client);
    }

    private void startStocking(SchematicAccess.SchematicReadResult read) {
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
        Map<Item, Integer> materials = new LinkedHashMap<>();
        for (BuildTarget target : read.targets()) {
            materials.merge(target.item(), target.count(), Integer::sum);
        }
        if (materials.isEmpty()) {
            Chat.error(Chat.tr("stock.no_materials"));
            return;
        }
        if (!read.unsupported().isEmpty()) {
            Chat.warn(Chat.tr("warn.unsupported_blocks", read.unsupported().size()));
        }

        StockAgent job = StockAgent.create(this, client, materials);
        BlockPos blocked = job.firstBlocked(client.world);
        if (blocked != null) {
            Chat.error(Chat.tr("stock.blocked", blocked.toShortString(), job.totalLevels()));
            return;
        }
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
        this.focusThen(session, () -> {
            switch (session.state) {
                case PAUSED, READY, BUILDING, SCANNING_STORAGE, CONFIRM_STORAGE -> this.beginBuilding(session);
                default -> {
                    this.promptedState = null;
                    Chat.info(Chat.tr("info.preparation_continued", session.id));
                }
            }
        });
    }

    public void begin(int id) {
        AgentSession session = this.findSession(id);
        if (session == null) {
            Chat.error(Chat.tr("error.unknown_session", id));
            return;
        }
        if (!canBuild(session.state)) {
            Chat.warn(Chat.tr("error.not_ready", id));
            return;
        }
        if (this.activeSession() == session && session.state == SessionState.BUILDING) {
            return;
        }
        this.focusThen(session, () -> this.beginBuilding(session));
    }

    /**
     * Building may start as soon as the skipped blocks are accepted and the inventory is empty: while scanning the
     * storage the agent simply builds what the materials found so far allow.
     */
    private static boolean canBuild(SessionState state) {
        return state != SessionState.CONFIRM_SKIPS && state != SessionState.CHECK_INVENTORY;
    }

    /**
     * Makes the session the focused one and runs {@code then} once it is ready. A session that is not loaded yet is
     * read in the background first; {@code then} runs on the render thread afterwards.
     */
    private void focusThen(AgentSession session, Runnable then) {
        if (this.focused != null && this.focused.session() == session) {
            then.run();
            return;
        }
        Optional<SchematicPlacement> placement = SchematicAccess.find(session.placement);
        if (placement.isEmpty()) {
            Chat.error(Chat.tr("error.placement_missing", session.id, session.name()));
            return;
        }
        if (this.stillLoading()) {
            return;
        }
        this.pauseActiveForSwitch();
        this.loadSession(session, placement.get(), runtime -> {
            if (this.findSession(session.id) != session) {
                return;
            }
            this.focused = runtime;
            this.promptedState = null;
            then.run();
        });
    }

    private void beginBuilding(AgentSession session) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || this.focused == null) {
            return;
        }
        double distance = SchematicAccess.distanceTo(this.focused.placement(), client.player.getEntityPos());
        boolean useHome = distance > this.config.maxStartDistance;
        if (useHome && this.worldData.buildHomeCommand.isEmpty()) {
            if (!this.autoRetryProbing) {
                // A quiet retry while the player is away (fetching material, say) simply waits for the next one.
                Chat.error(Chat.tr("error.too_far", (int) distance, this.config.maxStartDistance));
            }
            return;
        }
        // No full comparison with the world here, it would stall the game with a huge schematic: the agent keeps
        // checking every target against the world a slice per tick.
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
        this.save();
        // A quiet auto-retry probe (see tryAutoRecover) must not announce itself or yank the screen back open before
        // knowing whether the attempt actually sticks - ensureLockScreen() reopens it on its own once it does.
        if (!this.autoRetryProbing) {
            Chat.success(Chat.tr("info.building_started", session.id, session.name()));
            client.setScreen(new AgentLockScreen());
        }
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
        // Reasons the player can fix in the world (missing material, a wrong block, a missing tool, ...) get quietly
        // retried instead of needing an explicit "Weiter" click; see tryAutoRecover(). A voluntary pause or a fatal
        // one (died, emergency) is never retried on its own.
        boolean repeat = this.autoRetryCycle && reason.equals(this.autoRetryPrevReason) && args.equals(this.autoRetryPrevArgs);
        this.autoRetryCycle = false;
        if (NO_AUTO_RETRY_REASONS.contains(reason)) {
            this.nextAutoRetryMillis = Long.MAX_VALUE;
        } else {
            // A new problem is looked at again soon; the same one again keeps the longer wait tryAutoRecover set up.
            if (!repeat) {
                this.autoRetryDelayMillis = AUTO_RETRY_INTERVAL_MILLIS;
            }
            this.nextAutoRetryMillis = System.currentTimeMillis() + this.autoRetryDelayMillis;
        }
        // While auto-retrying, hitting the same problem again is expected and not worth repeating in chat; the lock
        // screen shows the current details anyway.
        if (repeat) {
            return;
        }
        if (reason.isEmpty()) {
            Chat.info(Chat.tr("info.paused", session.id).append(" ").append(this.resumeButton(session)));
        } else {
            Chat.warn(Chat.tr("info.paused_reason", session.id, Chat.trList(reason, args))
                    .append(" ").append(this.resumeButton(session)));
        }
    }

    /**
     * While a session is paused for a reason the player can resolve in the world (missing material now scanned, a
     * wrong block broken by hand, a tool added, health restored, ...), this quietly tries to continue every few
     * seconds instead of requiring an explicit "Weiter" click. The lock screen itself is not force-opened here: it
     * reappears through the normal {@link #ensureLockScreen} path only once the retry actually keeps the session
     * building, so the player is left alone in the world for as long as the problem persists.
     */
    private void tryAutoRecover(MinecraftClient client) {
        if (this.agent == null) {
            return;
        }
        AgentSession session = this.agent.session();
        if (session == null || session.state != SessionState.PAUSED
                || NO_AUTO_RETRY_REASONS.contains(session.pauseReason)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < this.nextAutoRetryMillis) {
            return;
        }
        // Scheduled before trying, and backing off with every try: a try that ends without a new pause (the player
        // walked too far away to start from here) must not run again on the very next tick.
        this.nextAutoRetryMillis = now + this.autoRetryDelayMillis;
        this.autoRetryDelayMillis = Math.min(MAX_AUTO_RETRY_INTERVAL_MILLIS, this.autoRetryDelayMillis * 2);
        this.autoRetryProbing = true;
        this.autoRetryPrevReason = session.pauseReason;
        this.autoRetryPrevArgs = List.copyOf(session.pauseArgs);
        // autoRetryProbing (no chat, no screen while resuming) is only cleared at the end of tick(client): a re-pause
        // often happens in the agent.tick() call right after this method returns, still within the same tick.
        this.resume(session.id);
        this.autoRetryCycle = true;
    }

    public void pauseActive() {
        if (this.agent != null) {
            this.pause(this.agent, "", List.of());
        }
        if (this.stockAgent != null && !this.stockAgent.isPaused()) {
            this.stockAgent.pause("");
            this.announceStockPause();
        }
    }

    /**
     * Answer to a wrong block in "ask for approval" mode: approved blocks get broken, rejected ones stay and their
     * target is skipped. Building continues either way.
     */
    public void answerApproval(boolean approved) {
        AgentSession session = this.activeSession();
        if (this.agent == null || session == null || !this.agent.answerPendingDecision(approved)) {
            return;
        }
        this.resume(session.id);
    }

    private MutableText resumeButton(AgentSession session) {
        return Chat.button(Chat.tr("button.resume"), "/agent start " + session.id, Chat.tr("hover.resume"));
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
                    .append(Chat.button(Chat.tr("button.confirm_cancel"), "/agent cancel " + id + " confirm",
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
        // The scanned chests hold the stacks selected for this session. Without any session left that selection has
        // no use, and the next session starts with a fresh scan instead of old markings.
        if (this.worldData.sessions.isEmpty() && !this.storage.isEmpty()) {
            this.storage.clear();
            Chat.info(Chat.tr("info.storage_cleared"));
        }
        this.save();
    }

    void complete(BuildAgent finished) {
        MinecraftClient client = MinecraftClient.getInstance();
        AgentSession session = finished.session();
        if (finished.runtime() != null && !finished.runtime().unsupported().isEmpty()) {
            // A filtered plan may be done while source blocks were omitted or substituted. Keep the session
            // reviewable and resumable; never announce that the entire original schematic was completed.
            this.pause(finished, "pause.schematic_incomplete",
                    List.of(String.valueOf(finished.runtime().unsupported().size())));
            return;
        }
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
                Chat.send(Chat.tr("warn.failed_block", pos.toShortString(), Chat.tr("reason." + reasonKey(failure.getValue()))));
                if (++shown >= 5) {
                    break;
                }
            }
        }
        ClientPlayerEntity player = client.player;
        if (player != null && !player.isInCreativeMode() && hasLeftovers(player.getInventory()) && !this.storage.isEmpty()) {
            Chat.info(Chat.tr("info.leftovers").append(" ").append(Chat.button(Chat.tr("button.deposit"), "/agent deposit",
                    Chat.tr("hover.deposit"))));
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
            case "missing_material", "missing_item" -> "missing_material";
            case "missing_tool" -> "missing_tool";
            case "skipped" -> "skipped";
            case "chest_full" -> "chest_full";
            case "chest_not_connected" -> "chest_not_connected";
            case "container_not_opened", "container_closed" -> "container_closed";
            case "fill_failed" -> "fill_failed";
            case "inventory_full" -> "inventory_full";
            case "interrupted" -> "interrupted";
            case "timeout" -> "timeout";
            case "cancelled" -> "cancelled";
            case "no_water_bucket", "no_water_source", "refill_failed", "no_empty_bucket" -> "no_water";
            case "water_not_placed" -> "water_not_placed";
            case "waiting_for_water" -> "waiting_for_water";
            case "no_air" -> "no_air";
            default -> "unknown";
        };
    }

    /** Readable text for a failure reason; reasons without a text of their own are shown as they are. */
    public static Text reasonText(String reason) {
        String key = reasonKey(reason);
        return "unknown".equals(key) && reason != null && !reason.isEmpty() ? Text.literal(reason) : Chat.tr("reason." + key);
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
        this.focusThen(session, () -> {
            if (this.allMaterialsFound()) {
                this.setState(session, SessionState.READY);
            } else {
                Chat.warn(Chat.tr("prepare.storage_incomplete"));
                this.setState(session, SessionState.SCANNING_STORAGE);
            }
        });
    }

    public void storageRescan(@Nullable Integer id) {
        this.storage.clear();
        this.markDirty();
        Chat.info(Chat.tr("info.storage_cleared"));
        if (id == null) {
            return;
        }
        AgentSession session = this.findSession(id);
        if (session != null && session.state.isPreparation()) {
            this.focusThen(session, () -> this.setState(session, SessionState.SCANNING_STORAGE));
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

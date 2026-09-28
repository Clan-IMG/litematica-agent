package net.clanimg.litematica_agent.config;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Global settings, stored in {@code config/litematica_agent/config.json}.
 */
public final class AgentConfig {
    /** What happens with blocks at schematic positions that the agent did not place itself. */
    public enum WrongBlockMode {
        /** Pause and let the player approve or reject breaking the block. */
        ASK,
        /** Break it without asking. */
        ALLOW,
        /** Leave it in place and skip the schematic block at that position. */
        SKIP;

        public String id() {
            return this.name().toLowerCase(Locale.ROOT);
        }

        public static @Nullable WrongBlockMode byId(String id) {
            for (WrongBlockMode mode : values()) {
                if (mode.id().equalsIgnoreCase(id)) {
                    return mode;
                }
            }
            return null;
        }
    }

    /** What the item row of the lock screen shows. */
    public enum MaterialView {
        /** Still needed for the current layer, or for the queued block types. */
        CURRENT,
        /** Still needed for the whole schematic. */
        TOTAL,
        INVENTORY
    }

    public static final int FASTEST_SPEED = 1;
    public static final int DEFAULT_SPEED = 10;
    public static final int SLOWEST_SPEED = 40;
    public static final int MIN_AGENT_FPS = 20;
    public static final int MAX_AGENT_FPS = 120;

    /** Language of all texts: {@code config/litematica_agent/message_<language>.yml}. */
    public String language = "en";
    /**
     * Working pace like the Litematica printer delay: 1 is the fastest pace that still works reliably, 40 is very
     * slow. Place delay, container click delay and camera speed are derived from it.
     */
    public int speed = DEFAULT_SPEED;
    /**
     * How often per second the agent turns the camera, like mouse input every frame (at most the real frame rate).
     * 20 turns it once per game tick.
     */
    public int agentFps = 60;
    public MaterialView materialView = MaterialView.CURRENT;
    /** When the player starts mining, switch to the best tool from hotbar or inventory, sparing nearly broken ones. */
    public boolean autoTool = true;
    public boolean sprint = true;
    public WrongBlockMode wrongBlockMode = WrongBlockMode.ASK;
    /** Use cheap blocks to pillar up and to support floating blocks; they are removed afterwards. */
    public boolean useHelperBlocks = true;
    public List<String> helperBlocks = new ArrayList<>(List.of(
            "minecraft:dirt", "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:netherrack",
            "minecraft:stone", "minecraft:andesite", "minecraft:diorite", "minecraft:granite"));
    /**
     * Allow orientations that need a view direction different from the clicked face (e.g. observers facing up on the
     * floor). Vanilla accepts this; strict anti-cheat plugins might not.
     */
    public boolean allowLookTricks = true;
    /** Stop using a tool when this many uses are left. */
    public int toolDurabilityReserve = 10;
    /** Start eating at or below this food level (6 = sprinting no longer possible). */
    public int eatAtFoodLevel = 6;
    /** Pause when damage is taken and health is at or below this value (half hearts). */
    public float pauseAtHealth = 12.0F;
    /** Leave the world or server when health drops to this value while damage continues (half hearts). */
    public float emergencyHealth = 6.0F;
    public boolean emergencyDisconnect = true;
    /** Beyond this distance the configured home command is used instead of walking. */
    public int homeDistance = 96;
    /** Maximum distance to the schematic when starting a session. */
    public int maxStartDistance = 64;
    public boolean showMaterialHud = true;
    /** Log every failed step with its reason (useful for bug reports). */
    public boolean verboseLogging = false;

    public void sanitize() {
        this.speed = clamp(this.speed, FASTEST_SPEED, SLOWEST_SPEED);
        this.agentFps = clamp(this.agentFps, MIN_AGENT_FPS, MAX_AGENT_FPS);
        if (this.materialView == null) {
            this.materialView = MaterialView.CURRENT;
        }
        this.toolDurabilityReserve = clamp(this.toolDurabilityReserve, 1, 200);
        this.eatAtFoodLevel = clamp(this.eatAtFoodLevel, 1, 19);
        this.homeDistance = clamp(this.homeDistance, 16, 100_000);
        this.maxStartDistance = clamp(this.maxStartDistance, 8, 1024);
        if (this.helperBlocks == null) {
            this.helperBlocks = new ArrayList<>();
        }
        if (this.wrongBlockMode == null) {
            this.wrongBlockMode = WrongBlockMode.ASK;
        }
        if (this.language == null || this.language.isBlank()) {
            this.language = "en";
        }
        this.language = this.language.toLowerCase(Locale.ROOT);
    }

    /** Ticks between two block placements (20 ticks = 1 second). */
    public int placeDelayTicks() {
        return (int) Math.round(this.bySpeed(1, 3, 20));
    }

    /** Ticks between two clicks inside a container. */
    public int containerClickDelayTicks() {
        return (int) Math.round(this.bySpeed(1, 3, 12));
    }

    /**
     * Maximum camera turn per tick in degrees. The slowest value still turns 180 degrees well within the aim timeouts.
     */
    public float rotationSpeed() {
        return (float) this.bySpeed(180, 30, 12);
    }

    /**
     * Share of the remaining turn that is still left after one tick close to the target: the camera slows down on the
     * last degrees like a hand on a mouse. Small values settle within a tick.
     */
    public double rotationSettle() {
        return this.bySpeed(0.05, 0.4, 0.4);
    }

    /** Smallest turn per tick in degrees, so the last degrees do not crawl. */
    public float rotationMinStep() {
        return (float) this.bySpeed(6, 1.5, 1.5);
    }

    /** Linear between the fastest and the default value, and between the default and the slowest value. */
    private double bySpeed(double fastest, double normal, double slowest) {
        int level = clamp(this.speed, FASTEST_SPEED, SLOWEST_SPEED);
        if (level <= DEFAULT_SPEED) {
            return fastest + (normal - fastest) * (level - FASTEST_SPEED) / (DEFAULT_SPEED - FASTEST_SPEED);
        }
        return normal + (slowest - normal) * (level - DEFAULT_SPEED) / (SLOWEST_SPEED - DEFAULT_SPEED);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

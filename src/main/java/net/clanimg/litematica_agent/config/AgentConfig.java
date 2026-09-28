package net.clanimg.litematica_agent.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Global settings, stored in {@code config/litematica_agent/config.json}.
 */
public final class AgentConfig {
    /** Ticks between two block placements (20 ticks = 1 second). */
    public int placeDelayTicks = 3;
    /** Ticks between two clicks inside a container. */
    public int containerClickDelayTicks = 3;
    /** Maximum camera turn per tick in degrees. */
    public float rotationSpeed = 30.0F;
    public boolean sprint = true;
    /** Break blocks at schematic positions that the agent did not place itself. */
    public boolean breakWrongBlocks = false;
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
        this.placeDelayTicks = clamp(this.placeDelayTicks, 1, 40);
        this.containerClickDelayTicks = clamp(this.containerClickDelayTicks, 1, 20);
        this.rotationSpeed = Math.max(5.0F, Math.min(180.0F, this.rotationSpeed));
        this.toolDurabilityReserve = clamp(this.toolDurabilityReserve, 1, 200);
        this.eatAtFoodLevel = clamp(this.eatAtFoodLevel, 1, 19);
        this.homeDistance = clamp(this.homeDistance, 16, 100_000);
        this.maxStartDistance = clamp(this.maxStartDistance, 8, 1024);
        if (this.helperBlocks == null) {
            this.helperBlocks = new ArrayList<>();
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

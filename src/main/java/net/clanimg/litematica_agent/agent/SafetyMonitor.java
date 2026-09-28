package net.clanimg.litematica_agent.agent;

import net.clanimg.litematica_agent.config.AgentConfig;
import net.clanimg.litematica_agent.gui.AgentScreen;
import net.clanimg.litematica_agent.inventory.InventoryHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Locale;

/**
 * Watches health while the agent is in control. Damage without food or at low health pauses the agent; continued
 * damage at critical health leaves the world or server before the player can die.
 */
public final class SafetyMonitor {
    private final AgentManager manager;
    private float lastHealth = -1.0F;
    private boolean pausedBySafety;

    SafetyMonitor(AgentManager manager) {
        this.manager = manager;
    }

    void reset() {
        this.lastHealth = -1.0F;
        this.pausedBySafety = false;
    }

    void onPaused(boolean bySafety) {
        this.pausedBySafety = bySafety;
    }

    void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }
        float health = player.getHealth();
        boolean damaged = this.lastHealth >= 0.0F && health < this.lastHealth - 0.01F;
        this.lastHealth = health;
        if (player.isInCreativeMode() || player.isSpectator() || health <= 0.0F) {
            return;
        }

        AgentSession session = this.manager.activeSession();
        boolean building = session != null && session.state == SessionState.BUILDING;
        // After a safety pause the agent still watches until the player closes the lock screen and takes over.
        boolean watchingPaused = this.pausedBySafety && client.currentScreen instanceof AgentScreen;
        if (!building && !watchingPaused) {
            this.pausedBySafety = false;
            return;
        }
        if (!damaged) {
            return;
        }

        AgentConfig config = this.manager.config();
        if (config.emergencyDisconnect && health <= config.emergencyHealth) {
            this.manager.emergencyDisconnect("reason.emergency_low_health", health);
            return;
        }
        if (building) {
            boolean noFood = InventoryHelper.findFood(player.getInventory()) == null && !this.storageHasFood();
            if (noFood || health <= config.pauseAtHealth) {
                String hearts = String.format(Locale.ROOT, "%.1f", health / 2.0F);
                this.manager.pause(this.manager.activeAgent(), noFood ? "pause.damage_no_food" : "pause.damage", List.of(hearts));
                this.pausedBySafety = true;
            }
        }
    }

    private boolean storageHasFood() {
        for (String id : this.manager.storage().totals().keySet()) {
            Item item = Registries.ITEM.get(Identifier.tryParse(id));
            if (item != null && InventoryHelper.isFood(new ItemStack(item))) {
                return true;
            }
        }
        return false;
    }
}

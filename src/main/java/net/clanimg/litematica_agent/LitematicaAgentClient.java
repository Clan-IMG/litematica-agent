package net.clanimg.litematica_agent;

import net.clanimg.litematica_agent.agent.AgentManager;
import net.clanimg.litematica_agent.command.AgentCommands;
import net.clanimg.litematica_agent.gui.AgentHud;
import net.clanimg.litematica_agent.gui.ChestScanOverlay;
import net.clanimg.litematica_agent.inventory.AutoTool;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LitematicaAgentClient implements ClientModInitializer {
    public static final String MOD_ID = "litematica_agent";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        AgentManager manager = AgentManager.get();
        manager.init();

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> AgentCommands.register(dispatcher));
        ClientTickEvents.START_CLIENT_TICK.register(manager::tick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> manager.onJoin(client)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(manager::onDisconnect));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> manager.onDisconnect());

        AgentHud hud = new AgentHud();
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.of(MOD_ID, "hud"), hud::render);

        ChestScanOverlay.register();
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClient()) {
                AutoTool.onAttackBlock(MinecraftClient.getInstance(), pos);
            }
            return ActionResult.PASS;
        });
        LOGGER.info("Litematica Agent initialized");
    }
}

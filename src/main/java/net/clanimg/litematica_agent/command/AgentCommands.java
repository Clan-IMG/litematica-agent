package net.clanimg.litematica_agent.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.clanimg.litematica_agent.agent.AgentManager;
import net.clanimg.litematica_agent.agent.AgentSession;
import net.clanimg.litematica_agent.agent.SessionState;
import net.clanimg.litematica_agent.config.AgentConfig;
import net.clanimg.litematica_agent.persistence.WorldData;
import net.clanimg.litematica_agent.ui.Chat;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.function.Consumer;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * All {@code /agent} client commands. They run on the client only and are never sent to the server.
 */
public final class AgentCommands {
    private static final SuggestionProvider<FabricClientCommandSource> SESSION_IDS = (context, builder) -> {
        for (AgentSession session : AgentManager.get().sessions()) {
            builder.suggest(session.id, Text.literal(session.name()));
        }
        return builder.buildFuture();
    };

    private AgentCommands() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        AgentManager manager = AgentManager.get();
        dispatcher.register(literal("agent")
                .executes(context -> help())
                .then(literal("help").executes(context -> help()))
                .then(literal("start")
                        .executes(context -> run(manager::startNew))
                        .then(argument("id", IntegerArgumentType.integer(1)).suggests(SESSION_IDS)
                                .executes(context -> run(() -> manager.resume(id(context))))))
                .then(literal("begin")
                        .then(argument("id", IntegerArgumentType.integer(1)).suggests(SESSION_IDS)
                                .executes(context -> run(() -> manager.begin(id(context))))))
                .then(literal("stop")
                        .executes(context -> run(() -> manager.stop(null)))
                        .then(argument("id", IntegerArgumentType.integer(1)).suggests(SESSION_IDS)
                                .executes(context -> run(() -> manager.stop(id(context))))))
                .then(literal("cancel")
                        .then(argument("id", IntegerArgumentType.integer(1)).suggests(SESSION_IDS)
                                .executes(context -> run(() -> manager.cancel(id(context), false)))
                                .then(literal("confirm").executes(context -> run(() -> manager.cancel(id(context), true))))))
                .then(literal("list").executes(context -> list()))
                .then(literal("status").executes(context -> status()))
                .then(literal("deposit").executes(context -> run(manager::deposit)))
                .then(literal("stock").executes(context -> run(manager::startStocking)))
                .then(storage(manager))
                .then(home(manager))
                .then(config(manager)));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> storage(AgentManager manager) {
        return literal("storage")
                .executes(context -> storageInfo())
                .then(literal("info").executes(context -> storageInfo()))
                .then(literal("keep")
                        .then(argument("id", IntegerArgumentType.integer(1)).suggests(SESSION_IDS)
                                .executes(context -> run(() -> manager.storageKeep(id(context))))))
                .then(literal("rescan")
                        .executes(context -> run(() -> manager.storageRescan(null)))
                        .then(argument("id", IntegerArgumentType.integer(1)).suggests(SESSION_IDS)
                                .executes(context -> run(() -> manager.storageRescan(id(context))))));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> home(AgentManager manager) {
        return literal("home")
                .executes(context -> homeInfo())
                .then(literal("storage")
                        .then(argument("command", StringArgumentType.greedyString())
                                .executes(context -> setHome(true, StringArgumentType.getString(context, "command")))))
                .then(literal("build")
                        .then(argument("command", StringArgumentType.greedyString())
                                .executes(context -> setHome(false, StringArgumentType.getString(context, "command")))))
                .then(literal("clear").executes(context -> {
                    WorldData data = manager.worldData();
                    data.storageHomeCommand = "";
                    data.buildHomeCommand = "";
                    manager.save();
                    Chat.info(Chat.tr("home.cleared"));
                    return 1;
                }));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> config(AgentManager manager) {
        return literal("config")
                .executes(context -> configInfo())
                .then(intOption("placeDelayTicks", 1, 40, (config, value) -> config.placeDelayTicks = value))
                .then(intOption("containerClickDelayTicks", 1, 20, (config, value) -> config.containerClickDelayTicks = value))
                .then(floatOption("rotationSpeed", 5.0F, 180.0F, (config, value) -> config.rotationSpeed = value))
                .then(boolOption("sprint", (config, value) -> config.sprint = value))
                .then(boolOption("breakWrongBlocks", (config, value) -> config.breakWrongBlocks = value))
                .then(boolOption("useHelperBlocks", (config, value) -> config.useHelperBlocks = value))
                .then(boolOption("allowLookTricks", (config, value) -> config.allowLookTricks = value))
                .then(intOption("toolDurabilityReserve", 1, 200, (config, value) -> config.toolDurabilityReserve = value))
                .then(intOption("eatAtFoodLevel", 1, 19, (config, value) -> config.eatAtFoodLevel = value))
                .then(floatOption("pauseAtHealth", 1.0F, 20.0F, (config, value) -> config.pauseAtHealth = value))
                .then(floatOption("emergencyHealth", 1.0F, 20.0F, (config, value) -> config.emergencyHealth = value))
                .then(boolOption("emergencyDisconnect", (config, value) -> config.emergencyDisconnect = value))
                .then(intOption("homeDistance", 16, 100_000, (config, value) -> config.homeDistance = value))
                .then(intOption("maxStartDistance", 8, 1024, (config, value) -> config.maxStartDistance = value))
                .then(boolOption("showMaterialHud", (config, value) -> config.showMaterialHud = value))
                .then(boolOption("verboseLogging", (config, value) -> config.verboseLogging = value));
    }

    private interface Setter<T> {
        void set(AgentConfig config, T value);
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> intOption(String name, int min, int max, Setter<Integer> setter) {
        return literal(name).then(argument("value", IntegerArgumentType.integer(min, max)).executes(context -> {
            setter.set(AgentManager.get().config(), IntegerArgumentType.getInteger(context, "value"));
            return configChanged(name, String.valueOf(IntegerArgumentType.getInteger(context, "value")));
        }));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> floatOption(String name, float min, float max, Setter<Float> setter) {
        return literal(name).then(argument("value", FloatArgumentType.floatArg(min, max)).executes(context -> {
            setter.set(AgentManager.get().config(), FloatArgumentType.getFloat(context, "value"));
            return configChanged(name, String.valueOf(FloatArgumentType.getFloat(context, "value")));
        }));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> boolOption(String name, Setter<Boolean> setter) {
        return literal(name).then(argument("value", BoolArgumentType.bool()).executes(context -> {
            setter.set(AgentManager.get().config(), BoolArgumentType.getBool(context, "value"));
            return configChanged(name, String.valueOf(BoolArgumentType.getBool(context, "value")));
        }));
    }

    private static int configChanged(String name, String value) {
        AgentManager.get().saveConfig();
        Chat.success(Chat.tr("config.changed", name, value));
        return 1;
    }

    private static int id(CommandContext<FabricClientCommandSource> context) {
        return IntegerArgumentType.getInteger(context, "id");
    }

    private static int run(Runnable action) {
        if (!AgentManager.get().isInWorld()) {
            return 0;
        }
        action.run();
        return 1;
    }

    private static int help() {
        Chat.info(Chat.tr("help.title"));
        for (String key : new String[]{"start", "start_id", "stop", "cancel", "list", "status", "storage", "home", "config", "deposit", "stock"}) {
            Chat.send(Text.literal("  ").append(Chat.tr("help." + key).formatted(Formatting.GRAY)));
        }
        return 1;
    }

    private static int list() {
        AgentManager manager = AgentManager.get();
        if (manager.sessions().isEmpty()) {
            Chat.info(Chat.tr("list.empty"));
            return 1;
        }
        Chat.info(Chat.tr("list.title"));
        for (AgentSession session : manager.sessions()) {
            double percent = session.totalBlocks == 0 ? 0.0 : session.doneBlocks * 100.0 / session.totalBlocks;
            MutableText line = Text.literal(" #" + session.id + " ").formatted(Formatting.AQUA)
                    .append(Text.literal(session.name() + " ").formatted(Formatting.WHITE))
                    .append(Chat.tr("state." + session.state.name().toLowerCase()).formatted(stateColor(session.state)))
                    .append(Text.literal(String.format(" %.1f%% ", percent)).formatted(Formatting.GRAY));
            if (session.state != SessionState.BUILDING) {
                line.append(Chat.button(Chat.tr("button.resume"), "/agent start " + session.id, Formatting.GREEN, Chat.tr("hover.resume")));
            } else {
                line.append(Chat.button(Chat.tr("button.pause"), "/agent stop " + session.id, Formatting.GOLD, Chat.tr("hover.pause")));
            }
            line.append(" ").append(Chat.button(Chat.tr("button.cancel"), "/agent cancel " + session.id, Formatting.RED, Chat.tr("hover.cancel")));
            Chat.send(line);
        }
        return 1;
    }

    private static Formatting stateColor(SessionState state) {
        return switch (state) {
            case BUILDING -> Formatting.GREEN;
            case PAUSED -> Formatting.GOLD;
            case READY -> Formatting.AQUA;
            default -> Formatting.YELLOW;
        };
    }

    private static int status() {
        AgentManager manager = AgentManager.get();
        AgentSession session = manager.activeSession();
        if (session == null) {
            Chat.info(Chat.tr("status.none"));
            return 1;
        }
        Chat.info(Chat.tr("status.line", session.id, session.name(), Chat.tr("state." + session.state.name().toLowerCase()),
                session.doneBlocks, session.totalBlocks));
        if (session.state == SessionState.PAUSED && !session.pauseReason.isEmpty()) {
            Chat.info(Chat.trList(session.pauseReason, session.pauseArgs));
        }
        return 1;
    }

    private static int storageInfo() {
        AgentManager manager = AgentManager.get();
        Chat.info(Chat.tr("storage.info", manager.storage().size(), manager.storage().totals().size()));
        return 1;
    }

    private static int homeInfo() {
        WorldData data = AgentManager.get().worldData();
        Chat.info(Chat.tr("home.info",
                data.storageHomeCommand.isEmpty() ? "-" : "/" + data.storageHomeCommand,
                data.buildHomeCommand.isEmpty() ? "-" : "/" + data.buildHomeCommand));
        Chat.info(Chat.tr("home.hint"));
        return 1;
    }

    private static int setHome(boolean storage, String command) {
        String cleaned = command.trim();
        if (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        WorldData data = AgentManager.get().worldData();
        if (storage) {
            data.storageHomeCommand = cleaned;
        } else {
            data.buildHomeCommand = cleaned;
        }
        AgentManager.get().save();
        Chat.success(Chat.tr(storage ? "home.storage_set" : "home.build_set", "/" + cleaned));
        return 1;
    }

    private static int configInfo() {
        AgentConfig config = AgentManager.get().config();
        Consumer<String> line = text -> Chat.send(Text.literal("  " + text).formatted(Formatting.GRAY));
        Chat.info(Chat.tr("config.title"));
        line.accept("placeDelayTicks = " + config.placeDelayTicks);
        line.accept("containerClickDelayTicks = " + config.containerClickDelayTicks);
        line.accept("rotationSpeed = " + config.rotationSpeed);
        line.accept("sprint = " + config.sprint);
        line.accept("breakWrongBlocks = " + config.breakWrongBlocks);
        line.accept("useHelperBlocks = " + config.useHelperBlocks);
        line.accept("allowLookTricks = " + config.allowLookTricks);
        line.accept("toolDurabilityReserve = " + config.toolDurabilityReserve);
        line.accept("eatAtFoodLevel = " + config.eatAtFoodLevel);
        line.accept("pauseAtHealth = " + config.pauseAtHealth);
        line.accept("emergencyHealth = " + config.emergencyHealth);
        line.accept("emergencyDisconnect = " + config.emergencyDisconnect);
        line.accept("homeDistance = " + config.homeDistance);
        line.accept("maxStartDistance = " + config.maxStartDistance);
        line.accept("showMaterialHud = " + config.showMaterialHud);
        line.accept("verboseLogging = " + config.verboseLogging);
        return 1;
    }
}

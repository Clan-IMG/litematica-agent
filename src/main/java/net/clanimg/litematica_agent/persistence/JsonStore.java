package net.clanimg.litematica_agent.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.clanimg.litematica_agent.LitematicaAgentClient;
import net.clanimg.litematica_agent.config.AgentConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.WorldSavePath;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Reads and writes the mod's JSON files. Writes go to a temporary file first so a crash never leaves a broken file.
 */
public final class JsonStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private JsonStore() {
    }

    public static Path baseDir() {
        return FabricLoader.getInstance().getConfigDir().resolve(LitematicaAgentClient.MOD_ID);
    }

    public static AgentConfig loadConfig() {
        AgentConfig config = read(baseDir().resolve("config.json"), AgentConfig.class);
        if (config == null) {
            config = new AgentConfig();
        }
        config.sanitize();
        saveConfig(config);
        return config;
    }

    public static void saveConfig(AgentConfig config) {
        write(baseDir().resolve("config.json"), config);
    }

    public static WorldData loadWorld(String worldKey) {
        WorldData data = read(worldFile(worldKey), WorldData.class);
        if (data == null) {
            data = new WorldData();
        }
        data.sanitize();
        return data;
    }

    public static void saveWorld(String worldKey, WorldData data) {
        write(worldFile(worldKey), data);
    }

    private static Path worldFile(String worldKey) {
        return baseDir().resolve("worlds").resolve(worldKey + ".json");
    }

    /**
     * Identifies the current world: the save folder in singleplayer, the server address in multiplayer.
     */
    public static String currentWorldKey(MinecraftClient client) {
        IntegratedServer server = client.getServer();
        if (server != null) {
            Path root = server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
            Path name = root.getFileName();
            return "sp_" + sanitize(name == null ? "world" : name.toString());
        }
        ServerInfo info = client.getCurrentServerEntry();
        if (info != null) {
            return "mp_" + sanitize(info.address);
        }
        return "unknown";
    }

    private static String sanitize(String value) {
        String cleaned = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return cleaned.isEmpty() ? "world" : cleaned;
    }

    private static <T> T read(Path file, Class<T> type) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, type);
        } catch (IOException | RuntimeException e) {
            LitematicaAgentClient.LOGGER.error("Could not read {}", file, e);
            try {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // Keeping the broken file is best effort only.
            }
            return null;
        }
    }

    private static void write(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(value, writer);
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LitematicaAgentClient.LOGGER.error("Could not write {}", file, e);
        }
    }
}

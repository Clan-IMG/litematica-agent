package net.clanimg.litematica_agent.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * Client-side chat messages: only the local player sees them, nothing is sent to the server.
 */
public final class Chat {
    private static final String PREFIX_KEY = "litematica_agent.";

    private Chat() {
    }

    public static MutableText tr(String key, Object... args) {
        return Text.translatable(PREFIX_KEY + key, args);
    }

    public static MutableText trList(String key, List<String> args) {
        return Text.translatable(PREFIX_KEY + key, args.toArray());
    }

    public static void info(Text message) {
        send(message.copy().formatted(Formatting.GRAY));
    }

    public static void success(Text message) {
        send(message.copy().formatted(Formatting.GREEN));
    }

    public static void warn(Text message) {
        send(message.copy().formatted(Formatting.GOLD));
    }

    public static void error(Text message) {
        send(message.copy().formatted(Formatting.RED));
    }

    public static void send(Text message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.inGameHud == null) {
            return;
        }
        MutableText prefix = Text.literal("[").formatted(Formatting.DARK_GRAY)
                .append(Text.literal("Agent").formatted(Formatting.AQUA, Formatting.BOLD))
                .append(Text.literal("] ").formatted(Formatting.DARK_GRAY));
        client.inGameHud.getChatHud().addMessage(prefix.append(message));
    }

    /**
     * A clickable {@code [label]} that runs a client command.
     */
    public static MutableText button(Text label, String command, Formatting color, Text hover) {
        return Text.literal("[").append(label).append("]")
                .formatted(color, Formatting.BOLD)
                .styled(style -> style
                        .withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(hover)));
    }

    public static String formatDuration(long millis) {
        long totalSeconds = millis / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return String.format("%d:%02d:%02d h", hours, minutes, seconds);
        }
        return String.format("%d:%02d min", minutes, seconds);
    }
}

package net.clanimg.litematica_agent.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Client-side chat messages: only the local player sees them, nothing is sent to the server. Colours come from the
 * message files, so all levels are sent the same way.
 */
public final class Chat {
    private Chat() {
    }

    public static MutableText tr(String key, Object... args) {
        return Messages.text(key, args);
    }

    public static MutableText trList(String key, List<String> args) {
        return Messages.text(key, args.toArray());
    }

    public static void info(Text message) {
        send(message);
    }

    public static void success(Text message) {
        send(message);
    }

    public static void warn(Text message) {
        send(message);
    }

    public static void error(Text message) {
        send(message);
    }

    public static void send(Text message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.inGameHud == null) {
            return;
        }
        client.inGameHud.getChatHud().addMessage(Messages.text("format.prefix").append(message));
    }

    /**
     * A clickable button (format {@code format.button}) that runs a client command.
     */
    public static MutableText button(Text label, String command, Text hover) {
        return Messages.text("format.button", label).styled(style -> style
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

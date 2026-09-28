package net.clanimg.litematica_agent.ui;

import net.clanimg.litematica_agent.LitematicaAgentClient;
import net.clanimg.litematica_agent.persistence.JsonStore;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * All texts of the mod, read from {@code config/litematica_agent/message_<language>.yml}. Those files are created
 * from the built-in defaults on first start and may be edited; keys missing there fall back to the built-in texts.
 * Texts use {@code &} colour codes, {@code %s} placeholders and {@code %%} for a percent sign.
 */
public final class Messages {
    public static final String DEFAULT_LANGUAGE = "en";
    private static final List<String> BUILT_IN = List.of("en", "de");
    private static final String KEY_PREFIX = "litematica_agent.";
    private static final String CHECKSUM_PREFIX = "# unchanged-defaults ";

    private static Map<String, String> messages = Map.of();

    private Messages() {
    }

    public static void load(String language) {
        exportBuiltIn();
        Map<String, String> merged = new HashMap<>(readBuiltIn(DEFAULT_LANGUAGE));
        merged.putAll(readBuiltIn(language));
        merged.putAll(readFile(file(language)));
        messages = merged;
    }

    /** Built-in languages plus every {@code message_<language>.yml} the player added to the config folder. */
    public static List<String> availableLanguages() {
        Set<String> result = new TreeSet<>(BUILT_IN);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(JsonStore.baseDir(), "message_*.yml")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                result.add(name.substring("message_".length(), name.length() - ".yml".length()).toLowerCase(Locale.ROOT));
            }
        } catch (IOException ignored) {
            // The config folder does not exist yet: only the built-in languages are available.
        }
        return List.copyOf(result);
    }

    public static MutableText text(String key, Object... args) {
        String template = messages.getOrDefault(key, key);
        MutableText styled = Text.empty();
        StringBuilder plain = new StringBuilder();
        StringBuilder segment = new StringBuilder();
        Style style = Style.EMPTY;
        boolean coloured = false;
        int argIndex = 0;
        for (int i = 0; i < template.length(); i++) {
            char c = template.charAt(i);
            char next = i + 1 < template.length() ? template.charAt(i + 1) : 0;
            Formatting code = c == '&' ? Formatting.byCode(next) : null;
            if (code != null) {
                appendSegment(styled, segment, style);
                if (code == Formatting.RESET) {
                    style = Style.EMPTY;
                } else if (code.isColor()) {
                    style = Style.EMPTY.withColor(code);
                } else {
                    style = style.withFormatting(code);
                }
                coloured = true;
                i++;
                continue;
            }
            String piece;
            if (c == '%' && next == '%') {
                piece = "%";
                i++;
            } else if (c == '%' && next == 's') {
                piece = argument(args, argIndex++);
                i++;
            } else {
                piece = String.valueOf(c);
            }
            segment.append(piece);
            plain.append(piece);
        }
        appendSegment(styled, segment, style);
        if (!coloured) {
            // Menu texts keep a translation key so their widgets stay identifiable (e.g. by the client game tests).
            // The game's language files do not contain these keys, so the fallback text is what gets shown.
            return Text.translatableWithFallback(KEY_PREFIX + key, plain.toString().replace("%", "%%"));
        }
        return styled;
    }

    private static void appendSegment(MutableText target, StringBuilder segment, Style style) {
        if (!segment.isEmpty()) {
            target.append(Text.literal(segment.toString()).setStyle(style));
            segment.setLength(0);
        }
    }

    private static String argument(Object[] args, int index) {
        if (index >= args.length) {
            return "";
        }
        Object arg = args[index];
        return arg instanceof Text text ? text.getString() : String.valueOf(arg);
    }

    // ---------------------------------------------------------------- files

    private static Path file(String language) {
        return JsonStore.baseDir().resolve("message_" + language + ".yml");
    }

    private static String resource(String language) {
        return "/assets/litematica_agent/messages/message_" + language + ".yml";
    }

    /**
     * Keeps the files in the config folder up to date with the built-in texts: an untouched file is replaced by the
     * newer defaults, an edited one keeps its texts and only gets the keys it is missing appended.
     */
    private static void exportBuiltIn() {
        for (String language : BUILT_IN) {
            String builtIn = builtInText(language);
            if (builtIn == null) {
                continue;
            }
            Path target = file(language);
            try {
                Files.createDirectories(target.getParent());
                if (!Files.exists(target)) {
                    Files.writeString(target, withChecksum(builtIn), StandardCharsets.UTF_8);
                    continue;
                }
                String current = normalize(Files.readString(target, StandardCharsets.UTF_8));
                String updated = updatedFile(current, builtIn);
                if (!updated.equals(current)) {
                    Files.writeString(target, updated, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                LitematicaAgentClient.LOGGER.error("Could not update {}", target, e);
            }
        }
    }

    /**
     * The new content of a message file in the config folder: the built-in texts if the file was never edited,
     * otherwise the file with the keys it lacks appended.
     */
    static String updatedFile(String current, String builtIn) {
        if (isUnedited(current)) {
            return withChecksum(builtIn);
        }
        Map<String, String> existing = parse(current);
        StringBuilder added = new StringBuilder();
        parse(builtIn).forEach((key, value) -> {
            if (!existing.containsKey(key)) {
                added.append(key).append(": \"").append(value.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n");
            }
        });
        if (added.isEmpty()) {
            return current;
        }
        String separator = current.endsWith("\n") ? "\n" : "\n\n";
        return current + separator + "# Added by a newer version of the mod\n" + added;
    }

    /** The first line holds a checksum of the rest, so a file nobody edited can be told apart from an edited one. */
    static String withChecksum(String text) {
        return CHECKSUM_PREFIX + checksum(text) + "\n" + text;
    }

    private static boolean isUnedited(String content) {
        int end = content.indexOf('\n');
        if (!content.startsWith(CHECKSUM_PREFIX) || end < 0) {
            return false;
        }
        return content.substring(CHECKSUM_PREFIX.length(), end).trim().equals(checksum(content.substring(end + 1)));
    }

    private static String checksum(String text) {
        return Integer.toHexString(normalize(text).hashCode());
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n");
    }

    private static @Nullable String builtInText(String language) {
        try (InputStream in = Messages.class.getResourceAsStream(resource(language))) {
            return in == null ? null : normalize(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            LitematicaAgentClient.LOGGER.error("Could not read built-in messages for {}", language, e);
            return null;
        }
    }

    private static Map<String, String> readBuiltIn(String language) {
        String text = builtInText(language);
        return text == null ? Map.of() : parse(text);
    }

    private static Map<String, String> readFile(Path file) {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            LitematicaAgentClient.LOGGER.error("Could not read {}", file, e);
            return Map.of();
        }
    }

    /**
     * Reads the small YAML subset the message files use: nested sections by indentation and one-line string values,
     * optionally quoted. Keys are flattened to {@code section.key}.
     */
    static Map<String, String> parse(String content) {
        Map<String, String> result = new LinkedHashMap<>();
        List<String> path = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        for (String raw : content.replace("﻿", "").split("\r?\n")) {
            String line = stripComment(raw);
            if (line.isBlank()) {
                continue;
            }
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }
            String entry = line.trim();
            int colon = entry.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = entry.substring(0, colon).trim();
            String value = entry.substring(colon + 1).trim();
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                path.remove(path.size() - 1);
            }
            if (value.isEmpty()) {
                path.add(key);
                indents.add(indent);
            } else {
                result.put(path.isEmpty() ? key : String.join(".", path) + "." + key, unquote(value));
            }
        }
        return result;
    }

    private static String stripComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote == '"') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            String inner = value.substring(1, value.length() - 1);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);
                if (c == '\\' && i + 1 < inner.length()) {
                    char escaped = inner.charAt(++i);
                    out.append(escaped == 'n' ? '\n' : escaped);
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        return value;
    }
}

package net.clanimg.litematica_agent.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MessagesTest {
    @Test
    void flattensSectionsAndUnquotes() {
        Map<String, String> parsed = Messages.parse("""
                # comment
                format:
                  prefix: "&8[Agent] "
                lock:
                  title: 'It''s # not a comment'
                  plain: value # trailing comment
                top: "a \\"quoted\\" word"
                """);
        assertEquals("&8[Agent] ", parsed.get("format.prefix"));
        assertEquals("It's # not a comment", parsed.get("lock.title"));
        assertEquals("value", parsed.get("lock.plain"));
        assertEquals("a \"quoted\" word", parsed.get("top"));
    }

    @Test
    void untouchedFileIsReplacedByNewDefaults() {
        String oldDefaults = "lock:\n  pause: \"Pause\"\n";
        String newDefaults = "lock:\n  pause: \"Pause\"\n  retry: \"Retry\"\n";
        String exported = Messages.withChecksum(oldDefaults);

        assertEquals(Messages.withChecksum(newDefaults), Messages.updatedFile(exported, newDefaults));
    }

    @Test
    void editedFileKeepsItsTextsAndGetsMissingKeys() {
        String newDefaults = "lock:\n  pause: \"Pause\"\n  retry: \"Retry \\\"now\\\"\"\n";
        String edited = Messages.withChecksum("lock:\n  pause: \"Pause\"\n").replace("\"Pause\"", "\"Halt\"");

        Map<String, String> result = Messages.parse(Messages.updatedFile(edited, newDefaults));
        assertEquals("Halt", result.get("lock.pause"));
        assertEquals("Retry \"now\"", result.get("lock.retry"));
        assertEquals(edited, Messages.updatedFile(Messages.updatedFile(edited, newDefaults), newDefaults)
                .substring(0, edited.length()));
    }

    @Test
    void builtInLanguagesHaveTheSameKeys() throws IOException {
        Map<String, String> english = builtIn("en");
        Map<String, String> german = builtIn("de");
        assertFalse(english.isEmpty());
        assertEquals(english.keySet(), german.keySet());
    }

    private static Map<String, String> builtIn(String language) throws IOException {
        try (InputStream in = MessagesTest.class.getResourceAsStream("/assets/litematica_agent/messages/message_" + language + ".yml")) {
            assertNotNull(in, "missing message_" + language + ".yml");
            return Messages.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}

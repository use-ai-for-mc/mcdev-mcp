package dev.mcdevmcp.tools.runtime;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeContractFixturesTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();

    @Test
    void rejectsMissingTerminalLf() {
        assertRejected("{}", "exactly one LF");
    }

    @Test
    void rejectsCrLf() {
        assertRejected("{}\r\n", "CR bytes");
    }

    @Test
    void rejectsEmptyAndInvalidUtf8Resources() {
        assertRejectedBytes(new byte[0], "resource is empty");
        assertRejectedBytes(new byte[]{'{', (byte) 0xc3, '(', '}', '\n'}, "Invalid UTF-8");
    }

    @Test
    void rejectsBlankAndSpacedLines() {
        assertRejected("{}\n\n{}\n", "Blank JSONL line 2");
        assertRejected("{ \"value\":1}\n", "compact JSON object");
    }

    @Test
    void rejectsMalformedAndConcatenatedObjects() {
        assertRejected("{\"value\":}\n", "Invalid JSONL object at line 1 in test.jsonl");
        assertRejected("{}{}\n", "one compact JSON object");
    }

    @Test
    void parsesAValidPhysicalJsonLine() throws Exception {
        var documents = RuntimeContractFixtures.parse(MAPPER, "{\"value\":1}\n".getBytes(StandardCharsets.UTF_8), "test.jsonl", Map.class);

        assertEquals(1, documents.size());
        assertEquals("1", documents.getFirst().get("value").toString());
    }

    private static void assertRejected(String text, String expectedMessage) {
        assertRejectedBytes(text.getBytes(StandardCharsets.UTF_8), expectedMessage);
    }

    private static void assertRejectedBytes(byte[] bytes, String expectedMessage) {
        var exception = assertThrows(IOException.class, () -> RuntimeContractFixtures.parse(MAPPER, bytes, "test.jsonl", Map.class));

        assertTrue(exception.getMessage().contains(expectedMessage));
    }
}

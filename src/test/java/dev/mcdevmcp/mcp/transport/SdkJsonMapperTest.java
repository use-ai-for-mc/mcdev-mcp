package dev.mcdevmcp.mcp.transport;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SdkJsonMapperTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();

    @Test
    void mapsTopLevelRecordsAndStructuredJdkValues() throws Exception {
        var expected = new SdkJsonProbe(URI.create("https://example.test/api"), SdkJsonMode.FAST, List.of(new SdkJsonItem("first", 1), new SdkJsonItem("second", 2)), new BigInteger("1234567890123456789012345678901234567890"), new BigDecimal("1234567890.012345678901234567890"), Duration.ofMillis(1234), Instant.parse("2026-07-10T12:34:56.123456789Z"));

        assertEquals(expected, MAPPER.readValue(MAPPER.writeValueAsString(expected), SdkJsonProbe.class));
    }

    @Test
    void ignoresUnknownFieldsForStructuredRecords() throws Exception {
        assertEquals(new SdkJsonUnknownFieldProbe("known"), MAPPER.readValue("{\"name\":\"known\",\"unknown\":true}", SdkJsonUnknownFieldProbe.class));
    }
}

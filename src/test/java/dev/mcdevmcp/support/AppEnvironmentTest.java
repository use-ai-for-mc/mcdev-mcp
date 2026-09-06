package dev.mcdevmcp.support;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AppEnvironmentTest {
    @Test
    void acceptsExactIntegralAsciiDecimalDebugBridgePorts() {
        Map.ofEntries(Map.entry("1", 1), Map.entry("65535", 65535), Map.entry("9876", 9876), Map.entry(" 9876 ", 9876), Map.entry("+009876", 9876), Map.entry("9876.0", 9876), Map.entry("9876.", 9876), Map.entry(".9876e4", 9876), Map.entry("9.876e3", 9876), Map.entry("987600e-2", 9876)).forEach((text, expected) -> {
            var port = new AppEnvironment(Map.of("DEBUGBRIDGE_PORT", text)).debugBridgePort();
            assertEquals(expected.intValue(), port.orElseThrow(), text);
        });
    }

    @Test
    void rejectsAbsentMalformedFractionalAndOutOfRangeDebugBridgePorts() {
        assertFalse(new AppEnvironment(Map.of()).debugBridgePort().isPresent());
        List<String> rejected = List.of("", "   ", "98 76", "0", "-0", "-0.0", "-1", "65536", "9999.5", "65535.0000000000001", "2147483648", "1e2147483648", "1e+", "NaN", "Infinity", "-Infinity", "0x2694", "0b10011010010100", "0o23224", "9876d", "9_876", "9,876", "９８７６");
        for (String text : rejected) {
            assertFalse(new AppEnvironment(Map.of("DEBUGBRIDGE_PORT", text)).debugBridgePort().isPresent(), text);
        }
    }
}

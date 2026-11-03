package dev.mcdevmcp.bridge;

import dev.mcdevmcp.bridge.payload.EmptyBridgePayload;
import dev.mcdevmcp.support.AppEnvironment;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class BridgeProbeTest {
    @Test
    void exposesOnlySessionStateAndCanProbeStatusWithoutMutation() throws Exception {
        try (var harness = new BridgeTestHarness(McpJsonDefaults.getMapper(), new AppEnvironment(Map.of()), (_, request) -> {
            Map<String, Object> status = Map.of("version", "1.21.11", "mappingStatus", "mojang", "obfuscated", false, "refs", 0L);
            return new BridgeResponse(request.id(), true, true, request.endpoint().wireName().equals("status") ? status : request.payload(), null, null);
        })) {
            BridgeProbe probe = new BridgeProbe(harness.session());

            assertFalse(probe.connectedPort().isPresent());
            assertTrue(probe.status().success());
            assertEquals(9876, probe.connectedPort().orElseThrow());
            assertEquals("1.21.11", probe.sessionInfo().orElseThrow().version().value());
            assertEquals(2, harness.requests().size());
            assertEquals("status", harness.requests().get(0).endpoint().wireName());
            assertEquals(EmptyBridgePayload.class, harness.requests().get(0).payload().getClass());
            assertEquals("status", harness.requests().get(1).endpoint().wireName());
            assertEquals(EmptyBridgePayload.class, harness.requests().get(1).payload().getClass());
        }
    }
}
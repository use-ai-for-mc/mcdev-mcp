package dev.mcdevmcp.parity;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.bridge.BridgeResponse;
import dev.mcdevmcp.bridge.BridgeSession;
import dev.mcdevmcp.bridge.SessionInfo;
import dev.mcdevmcp.bridge.payload.EmptyBridgePayload;
import dev.mcdevmcp.support.AppEnvironment;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.StructuredTaskScope;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the production WebSocket client against a real WebSocket peer.
 */
@Timeout(30)
class ScriptedBridgeSessionTest {
    @Test
    @SuppressWarnings("preview")
    void connectsOverAWebSocketAndKeepsConcurrentSendsApart(@TempDir Path temporaryDirectory) throws Exception {
        List<String> diagnostics = new CopyOnWriteArrayList<>();
        try (var bridge = ScriptedDebugBridge.start(temporaryDirectory);
             var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
             var session = new BridgeSession(client, McpJsonDefaults.getMapper(), new AppEnvironment(Map.of("DEBUGBRIDGE_PORT", Integer.toString(bridge.port()))), diagnostics::add)) {
            SessionInfo info = session.connect(null);
            assertEquals(bridge.port(), info.port());
            assertEquals("1.21.11", info.version().value());

            try (var scope = StructuredTaskScope.open()) {
                List<StructuredTaskScope.Subtask<BridgeResponse>> sends = new ArrayList<>();
                for (int index = 0; index < 32; index++) {
                    sends.add(scope.fork(() -> session.send(new BridgeEndpoint("snapshot"), new EmptyBridgePayload(), Duration.ofSeconds(5))));
                }
                scope.join();
                assertTrue(sends.stream().allMatch(send -> send.get().success()));
            }

            assertEquals(33, bridge.invocations().size());
            assertEquals(List.of(), diagnostics);
        }
    }
}

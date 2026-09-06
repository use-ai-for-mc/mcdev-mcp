package dev.mcdevmcp.bridge;

import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.bridge.payload.EmptyBridgePayload;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class BridgeLifecycleTest {
    private static final BridgeJson JSON = new BridgeJson(McpJsonDefaults.getMapper());

    @Test
    void closeAndPeerFailureRejectEveryPublishedRequestAndBoundDiagnostics() {
        List<String> diagnostics = new CopyOnWriteArrayList<>();
        CompletableFuture<BridgeResponse> delayed = new CompletableFuture<>();
        BridgeClient client = BridgeClient.testing(JSON, ignored -> delayed, diagnostics::add);

        CompletableFuture<BridgeResponse> request = client.send(new BridgeEndpoint("status"), new EmptyBridgePayload(), Duration.ofMillis(1)).toCompletableFuture();
        client.peerClosed(new IllegalStateException("peer"));
        assertThrows(Exception.class, request::join);
        assertEquals(0, client.pendingRequestCount());
        assertTrue(client.isClosed());

        client.receiveText("{\"id\":\"x".repeat(2_000) + "\",\"success\":true}", true);
        assertTrue(diagnostics.stream().allMatch(message -> message.length() <= 600));
    }

    @Test
    void timesOutAndHandlesFragmentedMalformedAndUnmatchedMessages() {
        List<String> diagnostics = new CopyOnWriteArrayList<>();
        CompletableFuture<BridgeResponse> delayed = new CompletableFuture<>();
        BridgeClient client = BridgeClient.testing(JSON, ignored -> delayed, diagnostics::add);

        CompletableFuture<BridgeResponse> timeout = client.send(new BridgeEndpoint("status"), new EmptyBridgePayload(), Duration.ofMillis(1)).toCompletableFuture();
        assertThrows(Exception.class, () -> timeout.get(6, TimeUnit.SECONDS));
        client.receiveText("{\"id\":\"req_99\",", false);
        client.receiveText("\"success\":true,\"result\":{}}", true);
        client.receiveText("{not-json", true);
        assertEquals(0, client.pendingRequestCount());
        assertEquals(2, diagnostics.size());
        client.close();
    }

    @Test
    void resetFencesDelayedScansAndClosesFailedAndReplacedCandidates() {
        CompletableFuture<BridgeClient> delayedOpen = new CompletableFuture<>();
        BridgeClient stale = BridgeClient.testing(JSON, ignored -> CompletableFuture.completedFuture(new BridgeResponse("req_1", true, true, Map.of("version", "stale", "mappingStatus", "mojang", "obfuscated", false, "refs", 0L), "", null)));
        BridgeSession resetSession = new BridgeSession(JSON, new AppEnvironment(Map.of()), _ -> delayedOpen);
        CompletableFuture<SessionInfo> connecting = resetSession.connect(null).toCompletableFuture();
        resetSession.reset();
        delayedOpen.complete(stale);
        assertThrows(Exception.class, connecting::join);
        assertTrue(stale.isClosed());
        assertFalse(resetSession.connectedPort().isPresent());

        AtomicInteger attempts = new AtomicInteger();
        BridgeClient failed = BridgeClient.testing(JSON, ignored -> CompletableFuture.completedFuture(new BridgeResponse("req_1", false, false, null, "", "no")));
        BridgeClient first = FakeDebugBridge.client(JSON, "first");
        BridgeClient replacement = FakeDebugBridge.client(JSON, "first");
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), _ -> {
            int attempt = attempts.incrementAndGet();
            BridgeClient candidate = attempt == 1 ? failed : attempt == 2 ? first : replacement;
            return CompletableFuture.completedFuture(candidate);
        });

        assertEquals(9877, session.connect(null).toCompletableFuture().join().port());
        assertTrue(failed.isClosed());
        assertEquals(9876, session.adoptPort(9876).toCompletableFuture().join().port());
        assertTrue(first.isClosed());
        session.close();
        assertTrue(replacement.isClosed());
    }

    @Test
    void explicitConnectFencesAnEarlierDelayedAutoScan() {
        CompletableFuture<BridgeClient> delayedScan = new CompletableFuture<>();
        BridgeClient explicit = FakeDebugBridge.client(JSON, "explicit");
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), port -> port == 9876 ? delayedScan : CompletableFuture.completedFuture(explicit));

        CompletableFuture<SessionInfo> scanning = session.connect(null).toCompletableFuture();
        assertEquals(9999, session.connect(9999).toCompletableFuture().join().port());
        BridgeClient stale = FakeDebugBridge.client(JSON, "stale");
        delayedScan.complete(stale);

        assertThrows(Exception.class, scanning::join);
        assertTrue(stale.isClosed());
        assertEquals(9999, session.connectedPort().orElseThrow());
        session.close();
    }

    @Test
    void resetClosesAnOpenedCandidateAwaitingStatusAndRejectsTheExplicitAttempt() {
        CompletableFuture<BridgeResponse> delayedStatus = new CompletableFuture<>();
        BridgeClient candidate = BridgeClient.testing(JSON, ignored -> delayedStatus);
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), _ -> CompletableFuture.completedFuture(candidate));
        CompletableFuture<SessionInfo> connecting = session.connect(9999).toCompletableFuture();
        assertEquals(1, candidate.pendingRequestCount());

        session.reset();

        assertThrows(Exception.class, connecting::join);
        assertTrue(candidate.isClosed());
        assertEquals(0, candidate.pendingRequestCount());
        assertFalse(session.connectedPort().isPresent());
        session.close();
    }

    @Test
    void listenerLatchesCloseBeforeTheClientIsAttached() {
        BridgeClient.ConnectionListener listener = new BridgeClient.ConnectionListener();
        listener.onClose(null, 1006, "early");
        BridgeClient client = FakeDebugBridge.client(JSON, "early");

        Throwable failure = listener.attach(client);

        assertNotNull(failure);
        assertTrue(client.isClosed());
    }

    @Test
    void oversizedFragmentTailIsDiscardedUntilTheFinalFragment() {
        List<String> diagnostics = new CopyOnWriteArrayList<>();
        BridgeClient client = BridgeClient.testing(JSON, request -> CompletableFuture.completedFuture(new BridgeResponse(request.id(), true, true, Map.of(), "", null)), diagnostics::add);

        client.receiveText("x".repeat(8 * 1024 * 1024 + 1), false);
        client.receiveText("{\"id\":\"discarded\",\"success\":true,\"result\":{}}", true);
        assertEquals(1, diagnostics.size());
        client.receiveText("{\"id\":\"unmatched\",\"success\":true,\"result\":{}}", true);
        assertEquals(2, diagnostics.size());
        client.close();
    }

    @Test
    @SuppressWarnings("ALL")
    void peerClosureClearsSessionAndLaterSendReconnects() {
        Path firstDirectory = Path.of("run-first").toAbsolutePath().normalize();
        Path secondDirectory = Path.of("run-second").toAbsolutePath().normalize();
        BridgeClient first = new FakeDebugBridge(Map.of("version", "first", "gameDir", firstDirectory.toString())).client();
        BridgeClient second = new FakeDebugBridge(Map.of("version", "second", "gameDir", secondDirectory.toString())).client();
        AtomicInteger opens = new AtomicInteger();
        List<String> diagnostics = new CopyOnWriteArrayList<>();
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), _ -> CompletableFuture.completedFuture(opens.getAndIncrement() == 0 ? first : second), diagnostics::add);

        assertEquals("first", session.connect(null).toCompletableFuture().join().version().value());
        first.peerClosed(new IllegalStateException("gone"));
        assertFalse(session.connectedPort().isPresent());
        assertEquals("first", session.sessionInfo().orElseThrow().version().value());
        BridgePayload payload = new EmptyBridgePayload();
        assertTrue(session.send(new BridgeEndpoint("echo"), payload, Duration.ofSeconds(1)).toCompletableFuture().join().success());
        assertEquals("second", session.sessionInfo().orElseThrow().version().value());
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.getFirst().contains("identity changed"));
        session.close();
    }
}

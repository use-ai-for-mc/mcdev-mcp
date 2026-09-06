package dev.mcdevmcp.bridge;

import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.bridge.payload.EmptyBridgePayload;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class BridgeSessionTest {
    private static final BridgeJson JSON = new BridgeJson(McpJsonDefaults.getMapper());

    @Test
    void statusDomainPathsRequireNativeAbsolutePaths() throws Exception {
        Path nativePath = Files.createTempDirectory("mcdev-status").toAbsolutePath().normalize();
        SessionInfo info = new SessionInfo(9876, new MinecraftVersion("1.21.11"), BridgeMappingStatus.MOJANG, false, 0, Optional.of(nativePath), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(nativePath, info.gameDir().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new SessionInfo(9876, new MinecraftVersion("1.21.11"), BridgeMappingStatus.MOJANG, false, 0, Optional.of(Path.of("relative-game")), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @Test
    void coalescesImplicitConnectionsScansPortsAndResets() {
        AtomicInteger attempts = new AtomicInteger();
        FakeDebugBridge bridge = new FakeDebugBridge(Map.of());
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of("DEBUGBRIDGE_PORT", "invalid")), port -> {
            attempts.incrementAndGet();
            return port == 9878 ? CompletableFuture.completedFuture(bridge.client()) : CompletableFuture.failedFuture(new IllegalStateException("not listening"));
        });

        CompletableFuture<SessionInfo> first = session.connect(null).toCompletableFuture();
        CompletableFuture<SessionInfo> second = session.connect(null).toCompletableFuture();

        assertEquals(9878, first.join().port());
        assertEquals(0L, first.join().refs());
        assertTrue(first.join().gameDir().orElseThrow().isAbsolute());
        assertEquals(first.join(), second.join());
        assertEquals(3, attempts.get());
        assertEquals(9878, session.connectedPort().orElseThrow());
        session.reset();
        assertFalse(session.connectedPort().isPresent());
        session.close();
    }

    @Test
    void connectionVerificationAndExplicitProbeSendEmptyPayloads() {
        List<BridgeRequest> requests = new ArrayList<>();
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), port -> {
            assertTrue(port == 9876 || port == 9999);
            return CompletableFuture.completedFuture(capturingClient(requests));
        });

        assertEquals(9876, session.connect(null).toCompletableFuture().join().port());
        assertEquals(9999, session.probe(9999).toCompletableFuture().join().port());

        assertEquals(List.of("status", "status"), requests.stream().map(request -> request.endpoint().wireName()).toList());
        assertTrue(requests.stream().allMatch(request -> request.payload().getClass() == EmptyBridgePayload.class));
        session.close();
    }

    private static BridgeClient capturingClient(List<BridgeRequest> requests) {
        Map<String, Object> status = Map.of("version", "1.21.11", "mappingStatus", "mojang", "obfuscated", false, "refs", 0L);
        return BridgeClient.testing(JSON, request -> {
            requests.add(request);
            Object result = request.endpoint().wireName().equals("status") ? status : request.payload();
            return CompletableFuture.completedFuture(new BridgeResponse(request.id(), true, true, result, null, null));
        });
    }

    @Test
    @SuppressWarnings("ALL")
    void explicitAdoptionRemainsDeliberateAndSendUsesTheConnectedClient() {
        FakeDebugBridge bridge = new FakeDebugBridge(Map.of());
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), _ -> CompletableFuture.completedFuture(bridge.client()));

        assertEquals(9999, session.adoptPort(9999).toCompletableFuture().join().port());
        BridgePayload payload = new TestPayload(7);
        BridgeResponse response = session.send(new BridgeEndpoint("echo"), payload, Duration.ofMillis(1)).toCompletableFuture().join();

        assertTrue(response.success());
        assertEquals("req_2", response.id());
        assertEquals(Duration.ofSeconds(5).plusMillis(1), BridgeClient.effectiveTimeout(Duration.ofMillis(1)));
        session.close();
    }

    @Test
    void explicitConnectPinsItsPortUntilReset() {
        List<Integer> openedPorts = new ArrayList<>();
        List<BridgeClient> openedClients = new ArrayList<>();
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), port -> {
            openedPorts.add(port);
            BridgeClient client = FakeDebugBridge.client(JSON, "1.21.11");
            openedClients.add(client);
            return CompletableFuture.completedFuture(client);
        });

        assertEquals(9999, session.connect(9999).toCompletableFuture().join().port());
        openedClients.getLast().peerClosed(new IllegalStateException("gone"));
        assertEquals(9999, session.connect(null).toCompletableFuture().join().port());
        session.reset();
        assertEquals(9876, session.connect(null).toCompletableFuture().join().port());

        assertEquals(List.of(9999, 9999, 9876), openedPorts);
        session.close();
    }

    @Test
    void adoptedPortDoesNotDisableLaterAutoScan() {
        List<Integer> openedPorts = new ArrayList<>();
        List<BridgeClient> openedClients = new ArrayList<>();
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), port -> {
            openedPorts.add(port);
            BridgeClient client = FakeDebugBridge.client(JSON, "1.21.11");
            openedClients.add(client);
            return CompletableFuture.completedFuture(client);
        });

        assertEquals(9876, session.connect(null).toCompletableFuture().join().port());
        assertEquals(9999, session.adoptPort(9999).toCompletableFuture().join().port());
        openedClients.getLast().peerClosed(new IllegalStateException("gone"));
        assertEquals(9876, session.connect(null).toCompletableFuture().join().port());

        assertEquals(List.of(9876, 9999, 9876), openedPorts);
        session.close();
    }

    @Test
    void implicitReconnectToAPinnedPortIsShared() {
        BridgeClient firstClient = FakeDebugBridge.client(JSON, "first");
        CompletableFuture<BridgeClient> delayedReconnect = new CompletableFuture<>();
        AtomicInteger opens = new AtomicInteger();
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), _ -> opens.getAndIncrement() == 0 ? CompletableFuture.completedFuture(firstClient) : delayedReconnect);
        assertEquals(9999, session.connect(9999).toCompletableFuture().join().port());
        firstClient.peerClosed(new IllegalStateException("gone"));

        CompletableFuture<SessionInfo> firstReconnect = session.connect(null).toCompletableFuture();
        CompletableFuture<SessionInfo> secondReconnect = session.connect(null).toCompletableFuture();
        assertSame(firstReconnect, secondReconnect);
        delayedReconnect.complete(FakeDebugBridge.client(JSON, "second"));

        assertEquals(9999, firstReconnect.join().port());
        assertEquals(2, opens.get());
        session.close();
    }

    @Test
    void environmentPortAcceptsNodeStyleWhitespaceAndIntegralDecimalText() {
        List<Integer> openedPorts = new ArrayList<>();
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of("DEBUGBRIDGE_PORT", " 9999.0 ")), port -> {
            openedPorts.add(port);
            return CompletableFuture.completedFuture(FakeDebugBridge.client(JSON, "1.21.11"));
        });

        assertEquals(9999, session.connect(null).toCompletableFuture().join().port());
        assertEquals(List.of(9999), openedPorts);
        session.close();
    }

    @Test
    void invalidEnvironmentPortSpellingsFallBackToTheDocumentedScanBase() {
        for (String configured : List.of("9999.5", "65536", "0x2694")) {
            List<Integer> openedPorts = new ArrayList<>();
            BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of("DEBUGBRIDGE_PORT", configured)), port -> {
                openedPorts.add(port);
                return CompletableFuture.completedFuture(FakeDebugBridge.client(JSON, "1.21.11"));
            });

            assertEquals(9876, session.connect(null).toCompletableFuture().join().port(), configured);
            assertEquals(List.of(9876), openedPorts, configured);
            session.close();
        }
    }

    @Test
    void disconnectPreservesRememberedIdentityAndProbeDoesNotAdoptItsCandidate() {
        List<Integer> openedPorts = new ArrayList<>();
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), port -> {
            openedPorts.add(port);
            return CompletableFuture.completedFuture(FakeDebugBridge.client(JSON, "1.21.11"));
        });

        SessionInfo connected = session.connect(null).toCompletableFuture().join();
        session.disconnect();

        assertFalse(session.connectedPort().isPresent());
        assertEquals(connected, session.sessionInfo().orElseThrow());
        SessionInfo probed = session.probe(9999).toCompletableFuture().join();
        assertEquals(9999, probed.port());
        assertFalse(session.connectedPort().isPresent());
        assertEquals(connected, session.sessionInfo().orElseThrow());
        assertEquals(List.of(9876, 9999), openedPorts);
        session.close();
    }

    @Test
    void cancellingSendDuringImplicitConnectLeavesTheOpeningAliveAndNeverSendsItsEndpoint() {
        CompletableFuture<BridgeClient> opening = new CompletableFuture<>();
        AtomicInteger endpointSends = new AtomicInteger();
        Map<String, Object> status = Map.of("version", "1.21.11", "mappingStatus", "mojang", "obfuscated", false, "refs", 0L, "gameDir", Path.of("run").toAbsolutePath().normalize().toString());
        BridgeClient client = BridgeClient.testing(JSON, request -> {
            if (request.endpoint().wireName().equals("status")) {
                return CompletableFuture.completedFuture(new BridgeResponse(request.id(), true, true, status, "", null));
            }
            endpointSends.incrementAndGet();
            return CompletableFuture.completedFuture(new BridgeResponse(request.id(), true, true, request.payload(), "", null));
        });
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), _ -> opening);

        CompletableFuture<BridgeResponse> call = session.send(new BridgeEndpoint("echo"), new EmptyBridgePayload(), Duration.ofSeconds(1)).toCompletableFuture();

        assertTrue(call.cancel(true));
        assertFalse(opening.isCancelled());
        assertTrue(opening.complete(client));
        assertEquals(0, endpointSends.get());
        assertEquals(9876, session.connectedPort().orElseThrow());
        session.close();
    }

    @Test
    void cancellingOneOfTwoSendsDoesNotCancelTheirSharedImplicitConnect() {
        CompletableFuture<BridgeClient> opening = new CompletableFuture<>();
        AtomicInteger endpointSends = new AtomicInteger();
        Map<String, Object> status = Map.of("version", "1.21.11", "mappingStatus", "mojang", "obfuscated", false, "refs", 0L, "gameDir", Path.of("run").toAbsolutePath().normalize().toString());
        BridgeClient client = BridgeClient.testing(JSON, request -> {
            if (request.endpoint().wireName().equals("status")) {
                return CompletableFuture.completedFuture(new BridgeResponse(request.id(), true, true, status, "", null));
            }
            endpointSends.incrementAndGet();
            return CompletableFuture.completedFuture(new BridgeResponse(request.id(), true, true, request.endpoint().wireName(), "", null));
        });
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), _ -> opening);

        CompletableFuture<BridgeResponse> cancelled = session.send(new BridgeEndpoint("first"), new EmptyBridgePayload(), Duration.ofSeconds(1)).toCompletableFuture();
        CompletableFuture<BridgeResponse> surviving = session.send(new BridgeEndpoint("second"), new EmptyBridgePayload(), Duration.ofSeconds(1)).toCompletableFuture();
        assertTrue(cancelled.cancel(true));

        assertFalse(opening.isCancelled());
        assertTrue(opening.complete(client));
        assertTrue(cancelled.isCancelled());
        assertEquals("second", surviving.join().result());
        assertEquals(1, endpointSends.get());
        session.close();
    }

    @Test
    void cancellingSendAfterConnectRemovesTheClientRequest() {
        CompletableFuture<BridgeResponse> delayed = new CompletableFuture<>();
        Map<String, Object> status = Map.of("version", "1.21.11", "mappingStatus", "mojang", "obfuscated", false, "refs", 0L, "gameDir", Path.of("run").toAbsolutePath().normalize().toString());
        BridgeClient client = BridgeClient.testing(JSON, request -> request.endpoint().wireName().equals("status") ? CompletableFuture.completedFuture(new BridgeResponse(request.id(), true, true, status, "", null)) : delayed);
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), _ -> CompletableFuture.completedFuture(client));
        session.connect(null).toCompletableFuture().join();

        CompletableFuture<BridgeResponse> call = session.send(new BridgeEndpoint("echo"), new EmptyBridgePayload(), Duration.ofSeconds(1)).toCompletableFuture();
        assertEquals(1, client.pendingRequestCount());

        assertTrue(call.cancel(true));
        assertEquals(0, client.pendingRequestCount());
        delayed.complete(new BridgeResponse("req_2", true, true, null, "", null));
        assertEquals(0, client.pendingRequestCount());
        session.close();
    }

    @Test
    void failedPortAdoptionPreservesTheRememberedSessionIdentity() {
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), port -> port == 9876 ? CompletableFuture.completedFuture(FakeDebugBridge.client(JSON, "remembered")) : CompletableFuture.failedFuture(new IllegalStateException("not listening")));
        SessionInfo remembered = session.connect(null).toCompletableFuture().join();

        assertThrows(CompletionException.class, () -> session.adoptPort(9999).toCompletableFuture().join());

        assertFalse(session.connectedPort().isPresent());
        assertEquals(remembered, session.sessionInfo().orElseThrow());
        session.close();
    }

    private record TestPayload(int value) implements BridgePayload {
    }
}

package dev.mcdevmcp.bridge;

import dev.mcdevmcp.bridge.payload.EmptyBridgePayload;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.support.AppEnvironment;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.mcdevmcp.bridge.BlockingCalls.awaitUntil;
import static org.junit.jupiter.api.Assertions.*;

final class BridgeSessionTest {
    private static final BridgeJson JSON = new BridgeJson(McpJsonDefaults.getMapper());

    private static BridgeClient capturingClient(List<BridgeRequest> requests) {
        Map<String, Object> status = Map.of("version", "1.21.11", "mappingStatus", "mojang", "obfuscated", false, "refs", 0L);
        return BridgeClient.testing(JSON, request -> {
            requests.add(request);
            Object result = request.endpoint().wireName().equals("status") ? status : request.payload();
            return new BridgeResponse(request.id(), true, true, result, null, null);
        });
    }

    @Test
    void statusDomainPathsRequireNativeAbsolutePaths() throws Exception {
        Path nativePath = Files.createTempDirectory("mcdev-status").toAbsolutePath().normalize();
        SessionInfo info = new SessionInfo(9876, new MinecraftVersion("1.21.11"), BridgeMappingStatus.MOJANG, false, 0, Optional.of(nativePath), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        assertEquals(nativePath, info.gameDir().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new SessionInfo(9876, new MinecraftVersion("1.21.11"), BridgeMappingStatus.MOJANG, false, 0, Optional.of(Path.of("relative-game")), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
    }

    @Test
    void coalescesImplicitConnectionsScansPortsAndResets() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch bothConnecting = new CountDownLatch(1);
        FakeDebugBridge bridge = new FakeDebugBridge(Map.of());
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of("DEBUGBRIDGE_PORT", "invalid")), port -> {
            attempts.incrementAndGet();
            if (port != 9878) {
                throw new IllegalStateException("not listening");
            }
            bothConnecting.await();
            return bridge.client();
        });

        var first = BlockingCalls.start(() -> session.connect(null));
        awaitUntil(() -> attempts.get() == 3);
        var second = BlockingCalls.start(() -> session.connect(null));
        second.awaitBlocked();
        bothConnecting.countDown();

        assertEquals(9878, first.get().port());
        assertEquals(0L, first.get().refs());
        assertTrue(first.get().gameDir().orElseThrow().isAbsolute());
        assertEquals(first.get(), second.get());
        assertEquals(3, attempts.get());
        assertEquals(9878, session.connectedPort().orElseThrow());
        session.reset();
        assertFalse(session.connectedPort().isPresent());
        session.close();
    }

    @Test
    void connectionVerificationAndExplicitProbeSendEmptyPayloads() throws Exception {
        List<BridgeRequest> requests = new CopyOnWriteArrayList<>();
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), port -> {
            assertTrue(port == 9876 || port == 9999);
            return capturingClient(requests);
        });

        assertEquals(9876, session.connect(null).port());
        assertEquals(9999, session.probe(9999).port());

        assertEquals(List.of("status", "status"), requests.stream().map(request -> request.endpoint().wireName()).toList());
        assertTrue(requests.stream().allMatch(request -> request.payload().getClass() == EmptyBridgePayload.class));
        session.close();
    }

    @Test
    void explicitAdoptionRemainsDeliberateAndSendUsesTheConnectedClient() throws Exception {
        FakeDebugBridge bridge = new FakeDebugBridge(Map.of());
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), _ -> bridge.client());

        assertEquals(9999, session.adoptPort(9999).port());
        BridgePayload payload = new TestPayload(7);
        BridgeResponse response = session.send(new BridgeEndpoint("echo"), payload, Duration.ofMillis(1));

        assertTrue(response.success());
        assertEquals("req_2", response.id());
        assertEquals(Duration.ofSeconds(5).plusMillis(1), BridgeClient.effectiveTimeout(Duration.ofMillis(1)));
        session.close();
    }

    @Test
    void explicitConnectPinsItsPortUntilReset() throws Exception {
        List<Integer> openedPorts = new CopyOnWriteArrayList<>();
        List<BridgeClient> openedClients = new CopyOnWriteArrayList<>();
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), port -> {
            openedPorts.add(port);
            BridgeClient client = FakeDebugBridge.client(JSON, "1.21.11");
            openedClients.add(client);
            return client;
        });

        assertEquals(9999, session.connect(9999).port());
        openedClients.getLast().peerClosed(new IllegalStateException("gone"));
        assertEquals(9999, session.connect(null).port());
        session.reset();
        assertEquals(9876, session.connect(null).port());

        assertEquals(List.of(9999, 9999, 9876), openedPorts);
        session.close();
    }

    @Test
    void adoptedPortDoesNotDisableLaterAutoScan() throws Exception {
        List<Integer> openedPorts = new CopyOnWriteArrayList<>();
        List<BridgeClient> openedClients = new CopyOnWriteArrayList<>();
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), port -> {
            openedPorts.add(port);
            BridgeClient client = FakeDebugBridge.client(JSON, "1.21.11");
            openedClients.add(client);
            return client;
        });

        assertEquals(9876, session.connect(null).port());
        assertEquals(9999, session.adoptPort(9999).port());
        openedClients.getLast().peerClosed(new IllegalStateException("gone"));
        assertEquals(9876, session.connect(null).port());

        assertEquals(List.of(9876, 9999, 9876), openedPorts);
        session.close();
    }

    @Test
    void implicitReconnectToAPinnedPortIsShared() throws Exception {
        BridgeClient firstClient = FakeDebugBridge.client(JSON, "first");
        CompletableFuture<BridgeClient> delayedReconnect = new CompletableFuture<>();
        AtomicInteger opens = new AtomicInteger();
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), _ -> opens.getAndIncrement() == 0 ? firstClient : BlockingCalls.opened(delayedReconnect));
        assertEquals(9999, session.connect(9999).port());
        firstClient.peerClosed(new IllegalStateException("gone"));

        var firstReconnect = BlockingCalls.start(() -> session.connect(null));
        awaitUntil(() -> opens.get() == 2);
        var secondReconnect = BlockingCalls.start(() -> session.connect(null));
        secondReconnect.awaitBlocked();
        delayedReconnect.complete(FakeDebugBridge.client(JSON, "second"));

        assertEquals(9999, firstReconnect.get().port());
        assertEquals(firstReconnect.get(), secondReconnect.get());
        assertEquals(2, opens.get());
        session.close();
    }

    @Test
    void environmentPortAcceptsNodeStyleWhitespaceAndIntegralDecimalText() throws Exception {
        List<Integer> openedPorts = new CopyOnWriteArrayList<>();
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of("DEBUGBRIDGE_PORT", " 9999.0 ")), port -> {
            openedPorts.add(port);
            return FakeDebugBridge.client(JSON, "1.21.11");
        });

        assertEquals(9999, session.connect(null).port());
        assertEquals(List.of(9999), openedPorts);
        session.close();
    }

    @Test
    void invalidEnvironmentPortSpellingsFallBackToTheDocumentedScanBase() throws Exception {
        for (String configured : List.of("9999.5", "65536", "0x2694")) {
            List<Integer> openedPorts = new CopyOnWriteArrayList<>();
            BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of("DEBUGBRIDGE_PORT", configured)), port -> {
                openedPorts.add(port);
                return FakeDebugBridge.client(JSON, "1.21.11");
            });

            assertEquals(9876, session.connect(null).port(), configured);
            assertEquals(List.of(9876), openedPorts, configured);
            session.close();
        }
    }

    @Test
    void disconnectPreservesRememberedIdentityAndProbeDoesNotAdoptItsCandidate() throws Exception {
        List<Integer> openedPorts = new CopyOnWriteArrayList<>();
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), port -> {
            openedPorts.add(port);
            return FakeDebugBridge.client(JSON, "1.21.11");
        });

        SessionInfo connected = session.connect(null);
        session.disconnect();

        assertFalse(session.connectedPort().isPresent());
        assertEquals(connected, session.sessionInfo().orElseThrow());
        SessionInfo probed = session.probe(9999);
        assertEquals(9999, probed.port());
        assertFalse(session.connectedPort().isPresent());
        assertEquals(connected, session.sessionInfo().orElseThrow());
        assertEquals(List.of(9876, 9999), openedPorts);
        session.close();
    }

    @Test
    void cancellingSendDuringImplicitConnectLeavesTheOpeningAliveAndNeverSendsItsEndpoint() throws Exception {
        CompletableFuture<BridgeClient> opening = new CompletableFuture<>();
        AtomicInteger endpointSends = new AtomicInteger();
        Map<String, Object> status = Map.of("version", "1.21.11", "mappingStatus", "mojang", "obfuscated", false, "refs", 0L, "gameDir", Path.of("run").toAbsolutePath().normalize().toString());
        BridgeClient client = BridgeClient.testing(JSON, request -> {
            if (request.endpoint().wireName().equals("status")) {
                return new BridgeResponse(request.id(), true, true, status, "", null);
            }
            endpointSends.incrementAndGet();
            return new BridgeResponse(request.id(), true, true, request.payload(), "", null);
        });
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), _ -> BlockingCalls.opened(opening));

        var call = BlockingCalls.start(() -> session.send(new BridgeEndpoint("echo"), new EmptyBridgePayload(), Duration.ofSeconds(1)));
        call.awaitBlocked();

        assertTrue(call.cancel());
        call.awaitStopped();
        assertFalse(opening.isCancelled());
        assertTrue(opening.complete(client));
        assertEquals(0, endpointSends.get());
        awaitUntil(() -> session.connectedPort().orElse(-1) == 9876);
        session.close();
    }

    @Test
    void cancellingOneOfTwoSendsDoesNotCancelTheirSharedImplicitConnect() throws Exception {
        CompletableFuture<BridgeClient> opening = new CompletableFuture<>();
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger endpointSends = new AtomicInteger();
        Map<String, Object> status = Map.of("version", "1.21.11", "mappingStatus", "mojang", "obfuscated", false, "refs", 0L, "gameDir", Path.of("run").toAbsolutePath().normalize().toString());
        BridgeClient client = BridgeClient.testing(JSON, request -> {
            if (request.endpoint().wireName().equals("status")) {
                return new BridgeResponse(request.id(), true, true, status, "", null);
            }
            endpointSends.incrementAndGet();
            return new BridgeResponse(request.id(), true, true, request.endpoint().wireName(), "", null);
        });
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), _ -> {
            opens.incrementAndGet();
            return BlockingCalls.opened(opening);
        });

        var cancelled = BlockingCalls.start(() -> session.send(new BridgeEndpoint("first"), new EmptyBridgePayload(), Duration.ofSeconds(1)));
        var surviving = BlockingCalls.start(() -> session.send(new BridgeEndpoint("second"), new EmptyBridgePayload(), Duration.ofSeconds(1)));
        cancelled.awaitBlocked();
        surviving.awaitBlocked();
        assertTrue(cancelled.cancel());
        cancelled.awaitStopped();

        assertFalse(opening.isCancelled());
        assertTrue(opening.complete(client));
        assertTrue(cancelled.task().isCancelled());
        assertEquals("second", surviving.get().result());
        assertEquals(1, opens.get());
        assertEquals(1, endpointSends.get());
        session.close();
    }

    @Test
    void cancellingSendAfterConnectRemovesTheClientRequest() throws Exception {
        CompletableFuture<BridgeResponse> delayed = new CompletableFuture<>();
        Map<String, Object> status = Map.of("version", "1.21.11", "mappingStatus", "mojang", "obfuscated", false, "refs", 0L, "gameDir", Path.of("run").toAbsolutePath().normalize().toString());
        BridgeClient client = BridgeClient.testing(JSON, request -> request.endpoint().wireName().equals("status") ? new BridgeResponse(request.id(), true, true, status, "", null) : delayed.get());
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), _ -> client);
        session.connect(null);

        var call = BlockingCalls.start(() -> session.send(new BridgeEndpoint("echo"), new EmptyBridgePayload(), Duration.ofSeconds(1)));
        awaitUntil(() -> client.pendingRequestCount() == 1);

        assertTrue(call.cancel());
        awaitUntil(() -> client.pendingRequestCount() == 0);
        delayed.complete(new BridgeResponse("req_2", true, true, null, "", null));
        assertEquals(0, client.pendingRequestCount());
        session.close();
    }

    @Test
    void failedPortAdoptionPreservesTheRememberedSessionIdentity() throws Exception {
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), port -> {
            if (port != 9876) {
                throw new IllegalStateException("not listening");
            }
            return FakeDebugBridge.client(JSON, "remembered");
        });
        SessionInfo remembered = session.connect(null);

        assertEquals("not listening", assertThrows(IllegalStateException.class, () -> session.adoptPort(9999)).getMessage());

        assertFalse(session.connectedPort().isPresent());
        assertEquals(remembered, session.sessionInfo().orElseThrow());
        session.close();
    }

    @Test
    void disconnectCancelsAPendingExplicitConnectAndClosesItsLateClient() throws Exception {
        CompletableFuture<BridgeClient> opening = new CompletableFuture<>();
        CountDownLatch opened = new CountDownLatch(1);
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), _ -> {
            opened.countDown();
            return BlockingCalls.opened(opening);
        });
        var connecting = BlockingCalls.start(() -> session.connect(9999));
        assertTrue(opened.await(5, TimeUnit.SECONDS));

        session.disconnect();

        Throwable failure = connecting.failure();
        assertInstanceOf(CancellationException.class, failure);
        assertEquals("DebugBridge session disconnected", failure.getMessage());
        BridgeClient late = FakeDebugBridge.client(JSON, "late");
        opening.complete(late);
        awaitUntil(late::isClosed);
        assertFalse(session.connectedPort().isPresent());
        session.close();
    }

    @Test
    void interruptingAnExplicitConnectAbandonsItsOwnAttempt() throws Exception {
        CompletableFuture<BridgeClient> opening = new CompletableFuture<>();
        CountDownLatch opened = new CountDownLatch(1);
        BridgeSession session = new BridgeSession(JSON, new AppEnvironment(Map.of()), _ -> {
            opened.countDown();
            return BlockingCalls.opened(opening);
        });
        var connecting = BlockingCalls.start(() -> session.connect(9999));
        assertTrue(opened.await(5, TimeUnit.SECONDS));

        assertTrue(connecting.cancel());
        connecting.awaitStopped();

        BridgeClient late = FakeDebugBridge.client(JSON, "late");
        opening.complete(late);
        awaitUntil(late::isClosed);
        assertFalse(session.connectedPort().isPresent());
        session.close();
    }

    private record TestPayload(int value) implements BridgePayload {
    }
}
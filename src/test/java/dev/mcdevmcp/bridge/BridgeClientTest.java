package dev.mcdevmcp.bridge;

import dev.mcdevmcp.bridge.payload.EmptyBridgePayload;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;

import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.mcdevmcp.bridge.BlockingCalls.awaitUntil;
import static org.junit.jupiter.api.Assertions.*;

final class BridgeClientTest {
    private static final BridgeJson JSON = new BridgeJson(McpJsonDefaults.getMapper());

    @Test
    void correlatesConcurrentResponsesWithoutCrossCompletionAndIgnoresLateMessages() throws Exception {
        ConcurrentHashMap<String, CompletableFuture<BridgeResponse>> responses = new ConcurrentHashMap<>();
        BridgeClient client = BridgeClient.testing(JSON, request -> responses.computeIfAbsent(request.id(), _ -> new CompletableFuture<>()).get());

        var first = BlockingCalls.start(() -> client.send(new BridgeEndpoint("one"), new EmptyBridgePayload(), Duration.ofSeconds(1)));
        awaitUntil(() -> client.pendingRequestCount() == 1);
        var second = BlockingCalls.start(() -> client.send(new BridgeEndpoint("two"), new EmptyBridgePayload(), Duration.ofSeconds(1)));
        awaitUntil(() -> client.pendingRequestCount() == 2);
        responses.computeIfAbsent("req_2", _ -> new CompletableFuture<>()).complete(new BridgeResponse("req_2", true, true, "second", "", null));
        responses.computeIfAbsent("req_1", _ -> new CompletableFuture<>()).complete(new BridgeResponse("req_1", true, true, "first", "", null));

        assertEquals("first", first.get().result());
        assertEquals("second", second.get().result());
        client.receiveMessage("{\"id\":\"req_99\",\"success\":true,\"result\":null}");
        assertEquals(0, client.pendingRequestCount());
        client.close();
    }

    @Test
    void closeRejectsOutstandingCallsAndCapsEndpointTimeouts() throws Exception {
        CompletableFuture<BridgeResponse> delayed = new CompletableFuture<>();
        BridgeClient client = BridgeClient.testing(JSON, _ -> delayed.get());
        var call = BlockingCalls.start(() -> client.send(new BridgeEndpoint("status"), new EmptyBridgePayload(), Duration.ofDays(1)));
        awaitUntil(() -> client.pendingRequestCount() == 1);

        assertEquals(Duration.ofSeconds(10), BridgeClient.effectiveTimeout(null));
        assertEquals(Duration.ofSeconds(15), BridgeClient.effectiveTimeout(Duration.ofSeconds(10)));
        assertEquals(Duration.ofMinutes(5), BridgeClient.effectiveTimeout(Duration.ofDays(1)));
        assertEquals("Request timed out after 15000ms. The game may be frozen or the script may be in an infinite loop.", BridgeClient.timeoutMessage(Duration.ofSeconds(10), Duration.ofSeconds(15)));
        assertEquals("Request timed out after 300000ms (capped from 86405000ms by BridgeSession ceiling of 300000ms). The game may be frozen or the script may be in an infinite loop.", BridgeClient.timeoutMessage(Duration.ofDays(1), Duration.ofMinutes(5)));
        client.close();

        assertEquals("DebugBridge client is closed", call.failure().getMessage());
        assertEquals(0, client.pendingRequestCount());
        assertThrows(IllegalStateException.class, () -> client.send(new BridgeEndpoint("status"), new EmptyBridgePayload(), null));
    }

    @Test
    void requestTimeoutFailsTheCallWithTheTimeoutTextAndDropsThePendingRequest() {
        CompletableFuture<BridgeResponse> delayed = new CompletableFuture<>();
        BridgeClient client = BridgeClient.testing(JSON, _ -> delayed.get());

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> client.send(new BridgeEndpoint("status"), new EmptyBridgePayload(), Duration.ofMillis(1)));

        assertEquals(BridgeClient.timeoutMessage(Duration.ofMillis(1), Duration.ofMillis(5_001)), failure.getMessage());
        assertEquals(0, client.pendingRequestCount());
        client.close();
    }

    @Test
    void cancellingACallRemovesItsPendingRequest() throws Exception {
        CompletableFuture<BridgeResponse> delayed = new CompletableFuture<>();
        BridgeClient client = BridgeClient.testing(JSON, _ -> delayed.get());
        var call = BlockingCalls.start(() -> client.send(new BridgeEndpoint("status"), new EmptyBridgePayload(), Duration.ofSeconds(1)));

        awaitUntil(() -> client.pendingRequestCount() == 1);
        assertTrue(call.cancel());
        awaitUntil(() -> client.pendingRequestCount() == 0);
        call.awaitStopped();
        delayed.complete(new BridgeResponse("req_1", true, true, null, "", null));
        assertEquals(0, client.pendingRequestCount());
        client.close();
    }

    @Test
    void anInterruptedSenderUnpublishesItsRequestAndSeesTheInterruption() throws Exception {
        CompletableFuture<BridgeResponse> delayed = new CompletableFuture<>();
        BridgeClient client = BridgeClient.testing(JSON, _ -> delayed.get());
        var call = BlockingCalls.start(() -> client.send(new BridgeEndpoint("status"), new EmptyBridgePayload(), Duration.ofSeconds(1)));
        awaitUntil(() -> client.pendingRequestCount() == 1);

        call.thread().interrupt();

        assertInstanceOf(InterruptedException.class, call.failure());
        assertEquals(0, client.pendingRequestCount());
        client.close();
    }

    @Test
    @SuppressWarnings("preview")
    void parallelSendsKeepEachPublishedRequestAttachedToItsOwnPayload() throws Exception {
        BridgeClient client = BridgeClient.testing(JSON, request -> new BridgeResponse(request.id(), true, true, request.endpoint().wireName(), "", null));

        try (var scope = StructuredTaskScope.open()) {
            List<StructuredTaskScope.Subtask<BridgeResponse>> calls = new ArrayList<>();
            for (int index = 0; index < 128; index++) {
                int value = index;
                calls.add(scope.fork(() -> client.send(new BridgeEndpoint("endpoint-" + value), new TestPayload(value), Duration.ofSeconds(1))));
            }
            scope.join();

            for (int index = 0; index < calls.size(); index++) {
                assertEquals("endpoint-" + index, calls.get(index).get().result());
            }
        }
        assertEquals(0, client.pendingRequestCount());
        client.close();
    }

    @Test
    void invalidTimeoutIsRejectedBeforePublishingARequest() {
        BridgeClient client = BridgeClient.testing(JSON, request -> new BridgeResponse(request.id(), true, true, null, "", null));

        assertThrows(IllegalArgumentException.class, () -> client.send(new BridgeEndpoint("status"), new EmptyBridgePayload(), Duration.ZERO));
        assertEquals(0, client.pendingRequestCount());
        client.close();
    }

    @Test
    void webSocketSendsAreSerializedAndAnAbandonedSendIsAwaitedByTheNextSender() throws Exception {
        var socket = new SingleSendWebSocket();
        BridgeClient client = BridgeClient.overWebSocket(socket, JSON);

        var abandoned = BlockingCalls.start(() -> client.send(new BridgeEndpoint("first"), new EmptyBridgePayload(), Duration.ofSeconds(1)));
        awaitUntil(() -> socket.sends.size() == 1);
        assertTrue(abandoned.cancel());
        abandoned.awaitStopped();
        var next = BlockingCalls.start(() -> client.send(new BridgeEndpoint("second"), new EmptyBridgePayload(), Duration.ofSeconds(1)));
        next.awaitBlocked();
        assertEquals(1, socket.sends.size(), "the next send must wait for the abandoned one");

        socket.sends.getFirst().complete(socket);
        awaitUntil(() -> socket.sends.size() == 2);
        socket.sends.getLast().complete(socket);
        client.receiveMessage("{\"id\":\"req_2\",\"success\":true,\"result\":\"second\"}");

        assertEquals("second", next.get().result());
        assertEquals(0, socket.rejectedSends.get());
        client.close();
    }

    private record TestPayload(int value) implements BridgePayload {
    }

    /**
     * Rejects a text send while an earlier one is pending, as the JDK WebSocket does.
     */
    private static final class SingleSendWebSocket implements WebSocket {
        private final List<CompletableFuture<WebSocket>> sends = new CopyOnWriteArrayList<>();
        private final AtomicInteger rejectedSends = new AtomicInteger();

        @Override
        public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            if (!sends.isEmpty() && !sends.getLast().isDone()) {
                rejectedSends.incrementAndGet();
                return CompletableFuture.failedFuture(new IllegalStateException("Send pending"));
            }
            var send = new CompletableFuture<WebSocket>();
            sends.add(send);
            return send;
        }

        @Override
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void request(long n) {
        }

        @Override
        public String getSubprotocol() {
            return "";
        }

        @Override
        public boolean isOutputClosed() {
            return false;
        }

        @Override
        public boolean isInputClosed() {
            return false;
        }

        @Override
        public void abort() {
        }
    }
}

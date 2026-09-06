package dev.mcdevmcp.bridge;

import io.modelcontextprotocol.json.McpJsonDefaults;
import dev.mcdevmcp.bridge.payload.EmptyBridgePayload;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

final class BridgeClientTest {
    @Test
    void correlatesConcurrentResponsesWithoutCrossCompletionAndIgnoresLateMessages() {
        ConcurrentHashMap<String, CompletableFuture<BridgeResponse>> responses = new ConcurrentHashMap<>();
        BridgeClient client = BridgeClient.testing(new BridgeJson(McpJsonDefaults.getMapper()), request -> {
            CompletableFuture<BridgeResponse> response = new CompletableFuture<>();
            responses.put(request.id(), response);
            return response;
        });

        CompletableFuture<BridgeResponse> first = client.send(new BridgeEndpoint("one"), new EmptyBridgePayload(), Duration.ofSeconds(1)).toCompletableFuture();
        CompletableFuture<BridgeResponse> second = client.send(new BridgeEndpoint("two"), new EmptyBridgePayload(), Duration.ofSeconds(1)).toCompletableFuture();
        responses.get("req_2").complete(new BridgeResponse("req_2", true, true, "second", "", null));
        responses.get("req_1").complete(new BridgeResponse("req_1", true, true, "first", "", null));

        assertEquals("first", first.join().result());
        assertEquals("second", second.join().result());
        client.receiveMessage("{\"id\":\"req_99\",\"success\":true,\"result\":null}");
        assertEquals(0, client.pendingRequestCount());
        client.close();
    }

    @Test
    void closeRejectsOutstandingCallsAndCapsEndpointTimeouts() {
        CompletableFuture<BridgeResponse> delayed = new CompletableFuture<>();
        BridgeClient client = BridgeClient.testing(new BridgeJson(McpJsonDefaults.getMapper()), ignored -> delayed);
        CompletableFuture<BridgeResponse> call = client.send(new BridgeEndpoint("status"), new EmptyBridgePayload(), Duration.ofDays(1)).toCompletableFuture();

        assertEquals(Duration.ofSeconds(10), BridgeClient.effectiveTimeout(null));
        assertEquals(Duration.ofSeconds(15), BridgeClient.effectiveTimeout(Duration.ofSeconds(10)));
        assertEquals(Duration.ofMinutes(5), BridgeClient.effectiveTimeout(Duration.ofDays(1)));
        assertEquals("Request timed out after 15000ms. The game may be frozen or the script may be in an infinite loop.", BridgeClient.timeoutMessage(Duration.ofSeconds(10), Duration.ofSeconds(15)));
        assertEquals("Request timed out after 300000ms (capped from 86405000ms by BridgeSession ceiling of 300000ms). The game may be frozen or the script may be in an infinite loop.", BridgeClient.timeoutMessage(Duration.ofDays(1), Duration.ofMinutes(5)));
        client.close();

        assertThrows(Exception.class, call::join);
        assertFalse(client.pendingRequestCount() > 0);
    }

    @Test
    void cancellingACallRemovesItsPendingRequest() {
        CompletableFuture<BridgeResponse> delayed = new CompletableFuture<>();
        BridgeClient client = BridgeClient.testing(new BridgeJson(McpJsonDefaults.getMapper()), ignored -> delayed);
        CompletableFuture<BridgeResponse> call = client.send(new BridgeEndpoint("status"), new EmptyBridgePayload(), Duration.ofSeconds(1)).toCompletableFuture();

        assertEquals(1, client.pendingRequestCount());
        assertTrue(call.cancel(true));
        assertEquals(0, client.pendingRequestCount());
        delayed.complete(new BridgeResponse("req_1", true, true, null, "", null));
        assertEquals(0, client.pendingRequestCount());
        client.close();
    }

    @Test
    void parallelSendsKeepEachPublishedRequestAttachedToItsOwnPayload() {
        BridgeClient client = BridgeClient.testing(new BridgeJson(McpJsonDefaults.getMapper()), request -> CompletableFuture.completedFuture(new BridgeResponse(request.id(), true, true, request.endpoint().wireName(), "", null)));

        try (var executor = Executors.newFixedThreadPool(8)) {
            List<CompletableFuture<BridgeResponse>> calls = IntStream.range(0, 128).mapToObj(index -> CompletableFuture.supplyAsync(() -> client.send(new BridgeEndpoint("endpoint-" + index), new TestPayload(index), Duration.ofSeconds(1)).toCompletableFuture().join(), executor)).toList();

            for (int index = 0; index < calls.size(); index++) {
                assertEquals("endpoint-" + index, calls.get(index).join().result());
            }
        }
        assertEquals(0, client.pendingRequestCount());
        client.close();
    }

    @Test
    void invalidTimeoutIsRejectedBeforePublishingARequest() {
        BridgeClient client = BridgeClient.testing(new BridgeJson(McpJsonDefaults.getMapper()), request -> CompletableFuture.completedFuture(new BridgeResponse(request.id(), true, true, null, "", null)));

        assertThrows(IllegalArgumentException.class, () -> client.send(new BridgeEndpoint("status"), new EmptyBridgePayload(), Duration.ZERO));
        assertEquals(0, client.pendingRequestCount());
        client.close();
    }

    private record TestPayload(int value) implements BridgePayload {
    }
}

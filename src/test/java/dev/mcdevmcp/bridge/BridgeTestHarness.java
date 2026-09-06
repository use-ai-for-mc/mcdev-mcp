package dev.mcdevmcp.bridge;

import dev.mcdevmcp.support.AppEnvironment;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class BridgeTestHarness implements AutoCloseable {
    private final CopyOnWriteArrayList<BridgeRequest> requests = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Integer> openedPorts = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Duration> effectiveTimeouts = new CopyOnWriteArrayList<>();
    private final AtomicInteger connectionCount = new AtomicInteger();
    private final AtomicReference<BridgeClient> activeClient = new AtomicReference<>();
    private final BridgeSession session;

    public BridgeTestHarness(McpJsonMapper mapper, AppEnvironment environment, Responder responder) {
        var json = new BridgeJson(mapper);
        session = new BridgeSession(json, environment, port -> {
            int connection = connectionCount.incrementAndGet();
            openedPorts.add(port);
            BridgeClient client = BridgeClient.testing(json, request -> {
                requests.add(request);
                return responder.respond(connection, request);
            }, ignored -> {
            }, effectiveTimeouts::add);
            activeClient.set(client);
            return CompletableFuture.completedFuture(client);
        });
    }

    public BridgeSession session() {
        return session;
    }

    public List<BridgeRequest> requests() {
        return List.copyOf(requests);
    }

    public List<Integer> openedPorts() {
        return List.copyOf(openedPorts);
    }

    public List<Duration> effectiveTimeouts() {
        return List.copyOf(effectiveTimeouts);
    }

    public int connectionCount() {
        return connectionCount.get();
    }

    public void disconnect() {
        BridgeClient client = activeClient.getAndSet(null);
        if (client != null) {
            client.peerClosed(new IllegalStateException("Test peer disconnected"));
        }
    }

    @Override
    public void close() {
        session.close();
    }
}

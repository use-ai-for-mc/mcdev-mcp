package dev.mcdevmcp.bridge;

import dev.mcdevmcp.bridge.payload.EmptyBridgePayload;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.support.AppEnvironment;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class BridgeSession implements AutoCloseable {
    private static final int DEFAULT_PORT = 9876;
    private static final int PORTS_TO_SCAN = 11;
    private static final Duration PROBE_TIMEOUT = Duration.ofMillis(1_500);
    private static final BridgeEndpoint STATUS = new BridgeEndpoint("status");

    private final AppEnvironment environment;
    private final Connector connector;
    private final Consumer<String> diagnostics;
    private final BridgeResultDecoder resultDecoder;
    private final Set<ConnectAttempt> connectionAttempts = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<BridgeClient> candidates = Collections.newSetFromMap(new IdentityHashMap<>());
    private ConnectAttempt implicitConnect;
    private Connected connected;
    private SessionInfo lastSessionInfo;
    private Integer configuredPort;
    private long generation;
    private boolean closed;

    public BridgeSession() {
        BridgeJson json = new BridgeJson(McpJsonDefaults.getMapper());
        this(json, AppEnvironment.system(), defaultConnector(json), _ -> {
        });
    }

    public BridgeSession(HttpClient client, McpJsonMapper mapper, AppEnvironment environment, Consumer<String> diagnostics) {
        BridgeJson json = new BridgeJson(Objects.requireNonNull(mapper, "mapper"));
        this(json, environment, defaultConnector(Objects.requireNonNull(client, "client"), json), diagnostics);
    }

    BridgeSession(BridgeJson json, AppEnvironment environment, Connector connector) {
        this(json, environment, connector, _ -> {
        });
    }

    BridgeSession(BridgeJson json, AppEnvironment environment, Connector connector, Consumer<String> diagnostics) {
        Objects.requireNonNull(json, "json");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.connector = Objects.requireNonNull(connector, "connector");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.resultDecoder = new BridgeResultDecoder(json.mapper());
    }

    private static Connector defaultConnector(BridgeJson json) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        return defaultConnector(client, json);
    }

    private static Connector defaultConnector(HttpClient client, BridgeJson json) {
        return port -> BridgeClient.connect(client, URI.create("ws://127.0.0.1:" + port), json);
    }

    private static int requireExplicitPort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("DebugBridge port must be in range: " + port);
        }
        return port;
    }

    private static SessionInfo toSessionInfo(int port, BridgeStatusWire status) {
        if (status.version() == null || status.mappingStatus() == null || status.obfuscated() == null || status.refs() == null) {
            throw new IllegalArgumentException("DebugBridge status response is missing required fields");
        }
        return new SessionInfo(port, new MinecraftVersion(status.version()), BridgeMappingStatus.fromWire(status.mappingStatus()), status.obfuscated(), status.refs(), path(status.gameDir()), path(status.logsDir()), path(status.latestLog()), Optional.ofNullable(status.latestLogExists()), path(status.debugLog()), Optional.ofNullable(status.debugLogExists()), Optional.ofNullable(status.sessionControlEnabled()));
    }

    private static Optional<Path> path(String value) {
        return value == null ? Optional.empty() : Optional.of(Path.of(value));
    }

    private static boolean identityChanged(SessionInfo previous, SessionInfo next) {
        return previous.gameDir().isPresent() && next.gameDir().isPresent() ? !previous.gameDir().equals(next.gameDir()) : !previous.version().equals(next.version());
    }

    private static String display(SessionInfo info) {
        return "port " + info.port() + ", game " + BridgePayloadValidator.safeDisplay(info.gameDir().map(Path::toString).orElse(info.version().value()));
    }

    /**
     * Connects to an explicit port, pinning it until reset, or joins the shared implicit
     * connect. Interrupting a caller abandons an explicit attempt but leaves a shared one
     * running for the other callers.
     */
    public SessionInfo connect(Integer explicitPort) throws IOException, InterruptedException {
        if (explicitPort != null) {
            ConnectAttempt owned;
            synchronized (this) {
                ensureOpen();
                int port = requireExplicitPort(explicitPort);
                supersede();
                configuredPort = port;
                long token = generation;
                owned = startAttempt(() -> connectPort(token, port));
            }
            return owned.awaitOwned();
        }
        ConnectAttempt shared;
        synchronized (this) {
            ensureOpen();
            if (connected != null) {
                return connected.info();
            }
            if (implicitConnect == null) {
                long token = generation;
                Integer pinned = configuredPort;
                implicitConnect = pinned == null ? startAttempt(() -> scanPorts(token, basePort())) : startAttempt(() -> connectPort(token, pinned));
            }
            shared = implicitConnect;
        }
        return shared.awaitShared();
    }

    public SessionInfo adoptPort(int port) throws IOException, InterruptedException {
        ConnectAttempt owned;
        synchronized (this) {
            ensureOpen();
            int explicit = requireExplicitPort(port);
            Integer preservedConfiguredPort = configuredPort;
            disconnect();
            configuredPort = preservedConfiguredPort;
            long token = generation;
            owned = startAttempt(() -> connectPort(token, explicit));
        }
        return owned.awaitOwned();
    }

    public BridgeResponse send(BridgeEndpoint endpoint, BridgePayload payload, Duration endpointTimeout) throws IOException, InterruptedException {
        Objects.requireNonNull(endpoint, "endpoint");
        connect(null);
        Connected current;
        synchronized (this) {
            if (connected == null) {
                throw new IllegalStateException("DebugBridge session is disconnected");
            }
            current = connected;
        }
        return current.send(endpoint, payload, endpointTimeout);
    }

    public synchronized OptionalInt connectedPort() {
        return connected == null ? OptionalInt.empty() : OptionalInt.of(connected.info().port());
    }

    public synchronized Optional<SessionInfo> sessionInfo() {
        return Optional.ofNullable(lastSessionInfo);
    }

    @SuppressWarnings("preview")
    public SessionInfo probe(int port) throws IOException, InterruptedException {
        int explicit = requireExplicitPort(port);
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<SessionInfo>anySuccessfulOrThrow(), config -> config.withName("debugbridge-probe").withTimeout(PROBE_TIMEOUT))) {
            scope.fork(() -> probePort(explicit));
            return scope.join();
        } catch (ExecutionException exception) {
            throw BridgeClient.failure(exception.getCause());
        }
    }

    public synchronized void disconnect() {
        generation++;
        implicitConnect = null;
        List<ConnectAttempt> pendingAttempts = List.copyOf(connectionAttempts);
        connectionAttempts.clear();
        List<BridgeClient> pendingCandidates = List.copyOf(candidates);
        candidates.clear();
        Connected previous = connected;
        connected = null;
        pendingAttempts.forEach(ConnectAttempt::cancel);
        pendingCandidates.forEach(BridgeClient::close);
        if (previous != null) {
            previous.close();
        }
    }

    public synchronized void reset() {
        disconnect();
        lastSessionInfo = null;
        configuredPort = null;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        reset();
    }

    private SessionInfo scanPorts(long token, int base) throws InterruptedException {
        for (int offset = 0; offset < PORTS_TO_SCAN; offset++) {
            if (stale(token)) {
                throw new CancellationException("DebugBridge session changed during scan");
            }
            try {
                return connectPort(token, base + offset);
            } catch (CancellationException exception) {
                throw exception;
            } catch (IOException | RuntimeException _) {
                // Try the next port in the documented scan window.
            }
        }
        throw new IllegalStateException("No DebugBridge instance accepted status on ports " + base + "-" + (base + PORTS_TO_SCAN - 1));
    }

    private SessionInfo connectPort(long token, int port) throws IOException, InterruptedException {
        BridgeClient client = openCandidate(port);
        boolean adopted = false;
        try {
            if (candidateRejected(token, client)) {
                throw new CancellationException("DebugBridge session changed during connect");
            }
            client.onClosed(this::clearDeadClient);
            BridgeResponse response = client.send(STATUS, new EmptyBridgePayload(), null);
            SessionInfo info = acceptStatus(token, port, client, response);
            adopted = true;
            return info;
        } finally {
            if (!adopted) {
                releaseCandidate(client);
                client.close();
            }
        }
    }

    private SessionInfo probePort(int port) throws IOException, InterruptedException {
        try (BridgeClient client = openCandidate(port)) {
            BridgeResponse response = client.send(STATUS, new EmptyBridgePayload(), PROBE_TIMEOUT);
            BridgeStatusWire wire = resultDecoder.decode(STATUS, BridgePayloadValidator.requireResult("status", response), BridgeResultTypes.STATUS);
            return toSessionInfo(port, wire);
        }
    }

    private SessionInfo acceptStatus(long token, int port, BridgeClient client, BridgeResponse response) {
        BridgeStatusWire status = resultDecoder.decode(STATUS, BridgePayloadValidator.requireResult("status", response), BridgeResultTypes.STATUS);
        SessionInfo info = toSessionInfo(port, status);
        synchronized (this) {
            if (stale(token) || client.isClosed()) {
                candidates.remove(client);
                client.close();
                throw new CancellationException("DebugBridge session changed or closed during status");
            }
            Connected previous = connected;
            if (lastSessionInfo != null && identityChanged(lastSessionInfo, info)) {
                diagnostics.accept("DebugBridge session identity changed from " + display(lastSessionInfo) + " to " + display(info));
            }
            if (previous != null && !previous.uses(client)) {
                previous.close();
            }
            candidates.remove(client);
            connected = new Connected(client, info);
            lastSessionInfo = info;
            return info;
        }
    }

    private BridgeClient openCandidate(int port) throws IOException, InterruptedException {
        BridgeClient client = connector.open(port);
        if (client == null) {
            throw new IllegalStateException("DebugBridge connector returned no client for port " + port);
        }
        return client;
    }

    private synchronized boolean stale(long token) {
        return closed || generation != token;
    }

    private synchronized ConnectAttempt startAttempt(Callable<SessionInfo> work) {
        ConnectAttempt attempt = new ConnectAttempt(work);
        connectionAttempts.add(attempt);
        attempt.start();
        return attempt;
    }

    private synchronized void finished(ConnectAttempt attempt) {
        connectionAttempts.remove(attempt);
        if (implicitConnect == attempt) {
            implicitConnect = null;
        }
    }

    private synchronized void clearDeadClient(BridgeClient client) {
        candidates.remove(client);
        if (connected != null && connected.uses(client)) {
            connected = null;
        }
    }

    private synchronized boolean candidateRejected(long token, BridgeClient client) {
        if (stale(token)) {
            return true;
        }
        candidates.add(client);
        return false;
    }

    private synchronized void releaseCandidate(BridgeClient client) {
        candidates.remove(client);
    }

    private int basePort() {
        return environment.debugBridgePort().orElse(DEFAULT_PORT);
    }

    private void supersede() {
        reset();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("DebugBridge session is closed");
        }
    }

    /**
     * Opens a client for a port, blocking. An interrupted connector throws and
     * closes a client that finishes opening afterwards.
     */
    @FunctionalInterface
    interface Connector {
        BridgeClient open(int port) throws IOException, InterruptedException;
    }

    private value record Connected(BridgeClient client, SessionInfo info) {
        private boolean uses(BridgeClient candidate) {
            return client == candidate;
        }

        private BridgeResponse send(BridgeEndpoint endpoint, BridgePayload payload, Duration endpointTimeout) throws IOException, InterruptedException {
            return client.send(endpoint, payload, endpointTimeout);
        }

        private void close() {
            client.close();
        }
    }

    /**
     * A connection attempt owned by the session, not by a caller: callers only wait for it,
     * so a shared implicit connect outlives a cancelled sender, and disconnect cancels it.
     */
    private final class ConnectAttempt {
        private final FutureTask<SessionInfo> task;

        private ConnectAttempt(Callable<SessionInfo> work) {
            task = new FutureTask<>(work) {
                @Override
                protected void done() {
                    finished(ConnectAttempt.this);
                }
            };
        }

        private void start() {
            Thread.ofVirtual().name("debugbridge-connect").start(task);
        }

        private void cancel() {
            task.cancel(true);
        }

        private SessionInfo awaitShared() throws IOException, InterruptedException {
            try {
                return task.get();
            } catch (CancellationException exception) {
                throw new CancellationException("DebugBridge session disconnected");
            } catch (ExecutionException exception) {
                throw BridgeClient.failure(exception.getCause());
            }
        }

        private SessionInfo awaitOwned() throws IOException, InterruptedException {
            try {
                return awaitShared();
            } catch (InterruptedException exception) {
                cancel();
                throw exception;
            }
        }
    }
}

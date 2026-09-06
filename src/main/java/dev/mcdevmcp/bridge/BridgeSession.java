package dev.mcdevmcp.bridge;

import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.bridge.payload.EmptyBridgePayload;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class BridgeSession implements AutoCloseable {
    private static final int DEFAULT_PORT = 9876;
    private static final int PORTS_TO_SCAN = 11;
    private static final BridgeEndpoint STATUS = new BridgeEndpoint("status");

    private final AppEnvironment environment;
    private final Connector connector;
    private final Consumer<String> diagnostics;
    private final BridgeResultDecoder resultDecoder;
    private final Set<CompletableFuture<SessionInfo>> connectionAttempts = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<BridgeClient> candidates = Collections.newSetFromMap(new IdentityHashMap<>());
    private CompletableFuture<SessionInfo> implicitConnect;
    private Connected connected;
    private SessionInfo lastSessionInfo;
    private Integer configuredPort;
    private long generation;
    private boolean closed;

    public BridgeSession() {
        this(new BridgeJson(McpJsonDefaults.getMapper()), AppEnvironment.system(), defaultConnector(new BridgeJson(McpJsonDefaults.getMapper())), ignored -> {
        });
    }

    public BridgeSession(HttpClient client, McpJsonMapper mapper, AppEnvironment environment, Consumer<String> diagnostics) {
        this(new BridgeJson(Objects.requireNonNull(mapper, "mapper")), environment, defaultConnector(Objects.requireNonNull(client, "client"), new BridgeJson(mapper)), diagnostics);
    }

    BridgeSession(BridgeJson json, AppEnvironment environment, Connector connector) {
        this(json, environment, connector, ignored -> {
        });
    }

    BridgeSession(BridgeJson json, AppEnvironment environment, Connector connector, Consumer<String> diagnostics) {
        Objects.requireNonNull(json, "json");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.connector = Objects.requireNonNull(connector, "connector");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.resultDecoder = new BridgeResultDecoder(json.mapper());
    }

    private static void closeQuietly(BridgeClient client) {
        if (client != null) {
            client.close();
        }
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

    public synchronized CompletionStage<SessionInfo> connect(Integer explicitPort) {
        ensureOpen();
        if (explicitPort != null) {
            int port = requireExplicitPort(explicitPort);
            supersede();
            configuredPort = port;
            return openPort(port, generation);
        }
        if (connected != null) {
            return CompletableFuture.completedFuture(connected.info());
        }
        if (implicitConnect != null) {
            return implicitConnect;
        }
        CascadingFuture<SessionInfo> started = newAttempt();
        implicitConnect = started;
        if (configuredPort == null) {
            scanPort(generation, basePort(), 0, started, null);
        }
        else {
            CompletableFuture<SessionInfo> opening = started.start(() -> openPort(configuredPort, generation));
            if (opening != null) {
                opening.whenComplete((info, failure) -> {
                    started.finish(opening, info, failure);
                    clearImplicit(started);
                });
            }
        }
        return started;
    }

    public synchronized CompletionStage<SessionInfo> adoptPort(int port) {
        ensureOpen();
        int explicit = requireExplicitPort(port);
        Integer preservedConfiguredPort = configuredPort;
        disconnect();
        configuredPort = preservedConfiguredPort;
        return openPort(explicit, generation);
    }

    @SuppressWarnings("resource")
    public CompletionStage<BridgeResponse> send(BridgeEndpoint endpoint, BridgePayload payload, Duration endpointTimeout) {
        Objects.requireNonNull(endpoint, "endpoint");
        CascadingFuture<BridgeResponse> result = new CascadingFuture<>();
        CompletableFuture<SessionInfo> connection = result.observe(() -> connect(null));
        if (connection == null) {
            return result;
        }
        connection.whenComplete((_, connectionFailure) -> {
            if (connectionFailure != null) {
                result.finish(connection, null, connectionFailure);
                return;
            }
            CompletableFuture<BridgeResponse> request = result.transition(connection, () -> {
                synchronized (this) {
                    if (connected == null) {
                        throw new IllegalStateException("DebugBridge session is disconnected");
                    }
                    return connected.client().send(endpoint, payload, endpointTimeout);
                }
            });
            if (request != null) {
                request.whenComplete((response, requestFailure) -> result.finish(request, response, requestFailure));
            }
        });
        return result;
    }

    public synchronized OptionalInt connectedPort() {
        return connected == null ? OptionalInt.empty() : OptionalInt.of(connected.info().port());
    }

    public synchronized Optional<SessionInfo> sessionInfo() {
        return Optional.ofNullable(lastSessionInfo);
    }

    public CompletionStage<SessionInfo> probe(int port) {
        int explicit = requireExplicitPort(port);
        var result = new CompletableFuture<SessionInfo>();
        result.orTimeout(1_500, TimeUnit.MILLISECONDS);
        CompletableFuture<BridgeClient> opening = openCandidate(explicit).toCompletableFuture();
        result.whenComplete((_, _) -> {
            if (!opening.isDone()) {
                opening.cancel(true);
            }
        });
        opening.whenComplete((client, openFailure) -> {
            if (openFailure != null || client == null) {
                result.completeExceptionally(openFailure == null ? new IllegalStateException("DebugBridge port " + explicit + " did not open") : openFailure);
                return;
            }
            result.whenComplete((_, _) -> client.close());
            CompletableFuture<BridgeResponse> status = client.send(STATUS, new EmptyBridgePayload(), Duration.ofMillis(1_500)).toCompletableFuture();
            result.whenComplete((_, _) -> {
                if (result.isCancelled()) {
                    status.cancel(true);
                }
            });
            status.whenComplete((response, statusFailure) -> {
                if (statusFailure != null) {
                    result.completeExceptionally(statusFailure);
                    return;
                }
                try {
                    BridgeStatusWire wire = resultDecoder.decode(STATUS, BridgePayloadValidator.requireResult("status", response), BridgeResultTypes.STATUS);
                    result.complete(toSessionInfo(explicit, wire));
                } catch (RuntimeException exception) {
                    result.completeExceptionally(exception);
                }
            });
        });
        return result;
    }

    public synchronized void disconnect() {
        generation++;
        implicitConnect = null;
        Set<CompletableFuture<SessionInfo>> pendingAttempts = Set.copyOf(connectionAttempts);
        connectionAttempts.clear();
        Set<BridgeClient> pendingCandidates = Set.copyOf(candidates);
        candidates.clear();
        Connected previous = connected;
        connected = null;
        CancellationException cancellation = new CancellationException("DebugBridge session disconnected");
        pendingAttempts.forEach(attempt -> attempt.completeExceptionally(cancellation));
        pendingCandidates.forEach(BridgeClient::close);
        if (previous != null) {
            previous.client().close();
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

    private void scanPort(long token, int port, int offset, CascadingFuture<SessionInfo> result, CompletableFuture<?> previous) {
        if (stale(token)) {
            result.finish(previous, null, new CancellationException("DebugBridge session changed during scan"));
            return;
        }
        if (offset >= PORTS_TO_SCAN) {
            result.finish(previous, null, new IllegalStateException("No DebugBridge instance accepted status on ports " + basePort() + "-" + (basePort() + PORTS_TO_SCAN - 1)));
            clearImplicit(result);
            return;
        }
        CompletableFuture<BridgeClient> opening = previous == null ? result.start(() -> openCandidate(port)) : result.transition(previous, () -> openCandidate(port));
        if (opening == null) {
            return;
        }
        opening.whenComplete((client, failure) -> {
            if (result.doesNotOwn(opening)) {
                closeQuietly(client);
                return;
            }
            if (stale(token)) {
                closeQuietly(client);
                result.finish(opening, null, new CancellationException("DebugBridge session changed during scan"));
                return;
            }
            if (failure != null || client == null) {
                scanPort(token, port + 1, offset + 1, result, opening);
                return;
            }
            if (candidateRejected(token, client)) {
                closeQuietly(client);
                result.finish(opening, null, new CancellationException("DebugBridge session changed during scan"));
                return;
            }
            CompletableFuture<SessionInfo> status = result.transition(opening, () -> verifyStatus(token, port, client));
            if (status == null) {
                releaseCandidate(client);
                closeQuietly(client);
                return;
            }
            status.whenComplete((info, statusFailure) -> {
                if (result.doesNotOwn(status)) {
                    releaseCandidate(client);
                    closeQuietly(client);
                    return;
                }
                if (statusFailure == null) {
                    if (result.finish(status, info, null)) {
                        clearImplicit(result);
                    }
                    else {
                        closeQuietly(client);
                    }
                }
                else {
                    releaseCandidate(client);
                    closeQuietly(client);
                    scanPort(token, port + 1, offset + 1, result, status);
                }
            });
        });
    }

    private CompletionStage<SessionInfo> openPort(int port, long token) {
        CascadingFuture<SessionInfo> result = newAttempt();
        CompletableFuture<BridgeClient> opening = result.start(() -> openCandidate(port));
        if (opening == null) {
            return result;
        }
        opening.whenComplete((client, failure) -> {
            if (result.doesNotOwn(opening)) {
                closeQuietly(client);
                return;
            }
            if (failure != null || client == null) {
                result.finish(opening, null, failure == null ? new IllegalStateException("DebugBridge port " + port + " did not open") : failure);
                return;
            }
            if (stale(token)) {
                closeQuietly(client);
                result.finish(opening, null, new CancellationException("DebugBridge session changed during connect"));
                return;
            }
            if (candidateRejected(token, client)) {
                closeQuietly(client);
                result.finish(opening, null, new CancellationException("DebugBridge session changed during connect"));
                return;
            }
            CompletableFuture<SessionInfo> status = result.transition(opening, () -> verifyStatus(token, port, client));
            if (status == null) {
                releaseCandidate(client);
                closeQuietly(client);
                return;
            }
            status.whenComplete((info, statusFailure) -> {
                if (result.doesNotOwn(status)) {
                    releaseCandidate(client);
                    closeQuietly(client);
                    return;
                }
                if (statusFailure == null) {
                    if (!result.finish(status, info, null)) {
                        closeQuietly(client);
                    }
                }
                else {
                    releaseCandidate(client);
                    closeQuietly(client);
                    result.finish(status, null, statusFailure);
                }
            });
        });
        return result;
    }

    private CompletionStage<SessionInfo> verifyStatus(long token, int port, BridgeClient client) {
        client.onClosed(this::clearDeadClient);
        CascadingFuture<SessionInfo> result = new CascadingFuture<>();
        CompletableFuture<BridgeResponse> request = result.start(() -> client.send(STATUS, new EmptyBridgePayload(), null));
        if (request == null) {
            return result;
        }
        request.whenComplete((response, failure) -> {
            if (failure != null) {
                result.finish(request, null, failure);
            }
            else {
                result.finishMapped(request, () -> acceptStatus(token, port, client, response));
            }
        });
        return result;
    }

    @SuppressWarnings("resource")
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
            if (previous != null && previous.client() != client) {
                previous.client().close();
            }
            candidates.remove(client);
            connected = new Connected(client, info);
            lastSessionInfo = info;
            return info;
        }
    }

    private synchronized boolean stale(long token) {
        return closed || generation != token;
    }

    private synchronized void clearImplicit(CompletableFuture<SessionInfo> result) {
        if (implicitConnect == result) {
            implicitConnect = null;
        }
    }

    @SuppressWarnings("resource")
    private synchronized void clearDeadClient(BridgeClient client) {
        candidates.remove(client);
        if (connected != null && connected.client() == client) {
            connected = null;
        }
    }

    private synchronized CascadingFuture<SessionInfo> newAttempt() {
        CascadingFuture<SessionInfo> attempt = new CascadingFuture<>();
        connectionAttempts.add(attempt);
        attempt.whenComplete((_, _) -> {
            removeAttempt(attempt);
            clearImplicit(attempt);
        });
        return attempt;
    }

    private synchronized void removeAttempt(CompletableFuture<SessionInfo> attempt) {
        connectionAttempts.remove(attempt);
    }

    private synchronized boolean candidateRejected(long token, BridgeClient client) {
        if (closed || generation != token) {
            return true;
        }
        candidates.add(client);
        return false;
    }

    private synchronized void releaseCandidate(BridgeClient client) {
        candidates.remove(client);
    }

    private CompletionStage<BridgeClient> openCandidate(int port) {
        try {
            CompletionStage<BridgeClient> opened = connector.open(port);
            return opened == null ? CompletableFuture.failedFuture(new IllegalStateException("DebugBridge connector returned no stage for port " + port)) : opened;
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
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

    @FunctionalInterface
    interface Connector {
        CompletionStage<BridgeClient> open(int port);
    }

    private record Connected(BridgeClient client, SessionInfo info) {
    }

    private static final class CascadingFuture<T> extends CompletableFuture<T> {
        private CompletableFuture<?> active;
        private boolean cancelActive;

        private synchronized <R> CompletableFuture<R> start(Supplier<? extends CompletionStage<R>> starter) {
            if (isDone()) {
                return null;
            }
            return replace(starter);
        }

        private synchronized <R> CompletableFuture<R> observe(Supplier<? extends CompletionStage<R>> starter) {
            if (isDone()) {
                return null;
            }
            return replace(starter, false);
        }

        private synchronized <R> CompletableFuture<R> transition(CompletableFuture<?> previous, Supplier<? extends CompletionStage<R>> starter) {
            if (isDone() || active != previous) {
                return null;
            }
            return replace(starter);
        }

        private synchronized boolean doesNotOwn(CompletableFuture<?> operation) {
            return isDone() || active != operation;
        }

        private synchronized boolean finish(CompletableFuture<?> operation, T value, Throwable failure) {
            if (isDone() || active != operation) {
                return false;
            }
            active = null;
            return failure == null ? super.complete(value) : super.completeExceptionally(failure);
        }

        private synchronized void finishMapped(CompletableFuture<?> operation, Supplier<T> mapper) {
            if (isDone() || active != operation) {
                return;
            }
            try {
                T value = mapper.get();
                active = null;
                super.complete(value);
            } catch (RuntimeException failure) {
                active = null;
                super.completeExceptionally(failure);
            }
        }

        @Override
        public synchronized boolean cancel(boolean mayInterruptIfRunning) {
            if (isDone()) {
                return false;
            }
            CompletableFuture<?> operation = active;
            boolean cancelOperation = cancelActive;
            active = null;
            cancelActive = false;
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled && cancelOperation && operation != null) {
                operation.cancel(mayInterruptIfRunning);
            }
            return cancelled;
        }

        private <R> CompletableFuture<R> replace(Supplier<? extends CompletionStage<R>> starter) {
            return replace(starter, true);
        }

        private <R> CompletableFuture<R> replace(Supplier<? extends CompletionStage<R>> starter, boolean cancelOnCompletion) {
            try {
                CompletionStage<R> stage = Objects.requireNonNull(starter.get(), "Cascaded operation returned no stage");
                CompletableFuture<R> operation = stage.toCompletableFuture();
                active = operation;
                cancelActive = cancelOnCompletion;
                return operation;
            } catch (RuntimeException failure) {
                active = null;
                cancelActive = false;
                super.completeExceptionally(failure);
                return null;
            }
        }
    }
}

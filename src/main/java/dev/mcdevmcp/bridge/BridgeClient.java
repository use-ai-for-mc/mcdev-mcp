package dev.mcdevmcp.bridge;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

public final class BridgeClient implements AutoCloseable {
    private static final Duration DEFAULT_ENDPOINT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RESPONSE_GRACE = Duration.ofSeconds(5);
    private static final Duration MAXIMUM_ENDPOINT_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAXIMUM_MESSAGE_CHARACTERS = 8 * 1024 * 1024;

    private final Object stateLock = new Object();
    private final Transport transport;
    private final BridgeJson json;
    private final ScheduledExecutorService scheduler;
    private final Consumer<String> diagnostics;
    private final Consumer<Duration> timeoutObserver;
    private final AtomicLong requestCounter = new AtomicLong();
    private final Map<String, PendingRequest> pending = new ConcurrentHashMap<>();
    private final StringBuilder fragments = new StringBuilder();
    private boolean discardingFragments;
    private Consumer<BridgeClient> closedCallback = ignored -> {
    };
    private boolean closed;

    private BridgeClient(Transport transport, BridgeJson json, ScheduledExecutorService scheduler, Consumer<String> diagnostics, Consumer<Duration> timeoutObserver) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.json = Objects.requireNonNull(json, "json");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.timeoutObserver = Objects.requireNonNull(timeoutObserver, "timeoutObserver");
    }

    public static CompletionStage<BridgeClient> connect(HttpClient client, URI uri, BridgeJson json) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(uri, "uri");
        // noinspection resource
        ScheduledExecutorService scheduler = scheduler();
        ConnectionListener listener = new ConnectionListener();
        return client.newWebSocketBuilder().buildAsync(uri, listener).thenApply(socket -> {
            BridgeClient connected = new BridgeClient(new WebSocketTransport(socket), json, scheduler, ignored -> {
            }, ignored -> {
            });
            Throwable earlyFailure = listener.attach(connected);
            if (earlyFailure != null) {
                throw new CompletionException(earlyFailure);
            }
            return connected;
        }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                scheduler.shutdownNow();
            }
        });
    }

    static BridgeClient testing(BridgeJson json, Function<BridgeRequest, CompletionStage<BridgeResponse>> responder) {
        return testing(json, responder, ignored -> {
        });
    }

    static BridgeClient testing(BridgeJson json, Function<BridgeRequest, CompletionStage<BridgeResponse>> responder, Consumer<String> diagnostics) {
        return testing(json, responder, diagnostics, ignored -> {
        });
    }

    static BridgeClient testing(BridgeJson json, Function<BridgeRequest, CompletionStage<BridgeResponse>> responder, Consumer<String> diagnostics, Consumer<Duration> timeoutObserver) {
        Objects.requireNonNull(responder, "responder");
        AtomicReference<BridgeClient> reference = new AtomicReference<>();
        // noinspection Convert2Lambda
        Transport transport = new Transport() {
            @Override
            public CompletionStage<?> send(BridgeRequest request, String ignored) {
                BridgeClient client = reference.get();
                try {
                    CompletionStage<BridgeResponse> response = responder.apply(request);
                    if (response == null) {
                        return CompletableFuture.failedFuture(new IllegalStateException("DebugBridge test responder returned null"));
                    }
                    response.whenComplete((value, failure) -> {
                        if (failure != null) {
                            client.completeById(request.id(), failure);
                        }
                        else {
                            client.completeById(request.id(), value);
                        }
                    });
                    return CompletableFuture.completedFuture(null);
                } catch (RuntimeException exception) {
                    return CompletableFuture.failedFuture(exception);
                }
            }
        };
        BridgeClient client = new BridgeClient(transport, json, scheduler(), diagnostics, timeoutObserver);
        reference.set(client);
        return client;
    }

    static Duration effectiveTimeout(Duration requested) {
        if (requested == null) {
            return DEFAULT_ENDPOINT_TIMEOUT;
        }
        if (requested.isZero() || requested.isNegative()) {
            throw new IllegalArgumentException("Bridge endpoint timeout must be positive");
        }
        Duration extended = extendedTimeout(requested);
        return extended.compareTo(MAXIMUM_ENDPOINT_TIMEOUT) > 0 ? MAXIMUM_ENDPOINT_TIMEOUT : extended;
    }

    static String timeoutMessage(Duration requested, Duration effective) {
        Duration uncapped = requested == null ? DEFAULT_ENDPOINT_TIMEOUT : extendedTimeout(requested);
        String capNote = uncapped.compareTo(MAXIMUM_ENDPOINT_TIMEOUT) > 0 ? " (capped from " + uncapped.toMillis() + "ms by BridgeSession ceiling of " + MAXIMUM_ENDPOINT_TIMEOUT.toMillis() + "ms)" : "";
        return "Request timed out after " + effective.toMillis() + "ms" + capNote + ". The game may be frozen or the script may be in an infinite loop.";
    }

    private static ScheduledExecutorService scheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> Thread.ofPlatform().daemon(true).name("debugbridge-timeout").unstarted(runnable));
    }

    private static Duration extendedTimeout(Duration requested) {
        try {
            return requested.plus(RESPONSE_GRACE);
        } catch (ArithmeticException exception) {
            return Duration.ofMillis(Long.MAX_VALUE);
        }
    }

    public CompletionStage<BridgeResponse> send(BridgeEndpoint endpoint, BridgePayload payload, Duration endpointTimeout) {
        Objects.requireNonNull(endpoint, "endpoint");
        Duration effectiveTimeout = effectiveTimeout(endpointTimeout);
        timeoutObserver.accept(effectiveTimeout);
        BridgeRequest request;
        PendingRequest pendingRequest;
        synchronized (stateLock) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("DebugBridge client is closed"));
            }
            request = new BridgeRequest("req_" + requestCounter.incrementAndGet(), endpoint, payload);
            pendingRequest = new PendingRequest(request, new CompletableFuture<>());
            pending.put(request.id(), pendingRequest);
            pendingRequest.future.whenComplete((_, _) -> {
                if (pendingRequest.future.isCancelled() && pending.remove(request.id(), pendingRequest)) {
                    pendingRequest.cancelTimeout();
                }
            });
            try {
                pendingRequest.timeout = scheduler.schedule(() -> completeExceptionally(request.id(), pendingRequest, new IllegalStateException(timeoutMessage(endpointTimeout, effectiveTimeout))), effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException exception) {
                completeExceptionally(request.id(), pendingRequest, new IllegalStateException("DebugBridge client is closed", exception));
                return pendingRequest.future;
            }
        }
        try {
            CompletionStage<?> sent = transport.send(request, json.writeRequest(request));
            if (sent == null) {
                completeExceptionally(request.id(), pendingRequest, new IllegalStateException("DebugBridge transport returned no send stage"));
            }
            else {
                sent.whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        completeExceptionally(request.id(), pendingRequest, failure);
                    }
                });
            }
        } catch (RuntimeException exception) {
            completeExceptionally(request.id(), pendingRequest, exception);
        }
        return pendingRequest.future;
    }

    void receiveText(CharSequence text, boolean last) {
        Objects.requireNonNull(text, "text");
        synchronized (fragments) {
            if (isClosed()) {
                return;
            }
            if (discardingFragments) {
                if (last) {
                    discardingFragments = false;
                }
                return;
            }
            if (text.length() > MAXIMUM_MESSAGE_CHARACTERS - fragments.length()) {
                fragments.setLength(0);
                discardingFragments = !last;
                diagnostics.accept("DebugBridge message exceeds the wire limit");
                return;
            }
            fragments.append(text);
            if (!last) {
                return;
            }
            String message = fragments.toString();
            fragments.setLength(0);
            receiveMessage(message);
        }
    }

    void receiveMessage(String message) {
        try {
            BridgeResponse response = json.readResponse(message);
            completeById(response.id(), response);
        } catch (IllegalArgumentException exception) {
            diagnostics.accept("Ignoring malformed DebugBridge response: " + BridgePayloadValidator.safeDisplay(exception.getMessage()));
        }
    }

    void peerClosed(Throwable failure) {
        terminate(new IllegalStateException("DebugBridge peer closed", failure));
    }

    void onClosed(Consumer<BridgeClient> callback) {
        Objects.requireNonNull(callback, "callback");
        boolean notify;
        synchronized (stateLock) {
            closedCallback = callback;
            notify = closed;
        }
        if (notify) {
            callback.accept(this);
        }
    }

    int pendingRequestCount() {
        return pending.size();
    }

    boolean isClosed() {
        synchronized (stateLock) {
            return closed;
        }
    }

    @Override
    public void close() {
        terminate(new IllegalStateException("DebugBridge client is closed"));
    }

    private void completeById(String id, Object value) {
        PendingRequest pendingRequest = pending.get(id);
        if (pendingRequest == null) {
            diagnostics.accept("Ignoring unmatched DebugBridge response " + BridgePayloadValidator.safeDisplay(id));
            return;
        }
        if (value instanceof BridgeResponse response) {
            complete(id, pendingRequest, response);
        }
        else if (value instanceof Throwable failure) {
            completeExceptionally(id, pendingRequest, failure);
        }
        else {
            completeExceptionally(id, pendingRequest, new IllegalStateException("DebugBridge test transport completed with an invalid value"));
        }
    }

    private void complete(String id, PendingRequest pendingRequest, BridgeResponse response) {
        if (pending.remove(id, pendingRequest)) {
            pendingRequest.cancelTimeout();
            pendingRequest.future.complete(response);
        }
    }

    private void completeExceptionally(String id, PendingRequest pendingRequest, Throwable failure) {
        if (pending.remove(id, pendingRequest)) {
            pendingRequest.cancelTimeout();
            pendingRequest.future.completeExceptionally(failure);
        }
    }

    private void terminate(Throwable failure) {
        List<PendingRequest> outstanding;
        Consumer<BridgeClient> callback;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            outstanding = new ArrayList<>(pending.values());
            callback = closedCallback;
        }
        for (PendingRequest pendingRequest : outstanding) {
            completeExceptionally(pendingRequest.request.id(), pendingRequest, failure);
        }
        scheduler.shutdownNow();
        try {
            transport.close();
        } catch (RuntimeException ignored) {
            // A peer can already be gone.
        }
        callback.accept(this);
    }

    interface Transport {
        CompletionStage<?> send(BridgeRequest request, String text);

        default void close() {
        }
    }

    static final class ConnectionListener implements WebSocket.Listener {
        private final Object lock = new Object();
        private final List<TextFragment> queuedText = new ArrayList<>();
        private BridgeClient client;
        private Throwable terminalFailure;
        private int queuedCharacters;

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            boolean overflow = false;
            synchronized (lock) {
                if (client == null) {
                    if (terminalFailure == null) {
                        if (data.length() > MAXIMUM_MESSAGE_CHARACTERS - queuedCharacters) {
                            terminalFailure = new IllegalStateException("DebugBridge message exceeds the wire limit before connection setup completed");
                            queuedText.clear();
                            queuedCharacters = 0;
                            overflow = true;
                        }
                        else {
                            queuedText.add(new TextFragment(data.toString(), last));
                            queuedCharacters += data.length();
                        }
                    }
                }
                else {
                    client.receiveText(data, last);
                }
            }
            if (overflow) {
                socket.abort();
            }
            else {
                socket.request(1);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            terminate(new IllegalStateException("DebugBridge closed: " + statusCode));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket socket, Throwable error) {
            terminate(new IllegalStateException("DebugBridge socket failed", error));
        }

        Throwable attach(BridgeClient connected) {
            Throwable failure;
            synchronized (lock) {
                if (client != null) {
                    throw new IllegalStateException("DebugBridge listener is already attached");
                }
                client = Objects.requireNonNull(connected, "connected");
                queuedText.forEach(fragment -> client.receiveText(fragment.text(), fragment.last()));
                queuedText.clear();
                queuedCharacters = 0;
                failure = terminalFailure;
            }
            if (failure != null) {
                connected.peerClosed(failure);
            }
            return failure;
        }

        private void terminate(Throwable failure) {
            BridgeClient connected;
            synchronized (lock) {
                if (terminalFailure != null) {
                    return;
                }
                terminalFailure = Objects.requireNonNull(failure, "failure");
                queuedText.clear();
                queuedCharacters = 0;
                connected = client;
            }
            if (connected != null) {
                connected.peerClosed(failure);
            }
        }
    }

    private record WebSocketTransport(WebSocket socket) implements Transport {
        @Override
        public CompletionStage<?> send(BridgeRequest request, String text) {
            return socket.sendText(text, true);
        }

        @Override
        public void close() {
            socket.abort();
        }
    }

    private record TextFragment(String text, boolean last) {
    }

    private static final class PendingRequest {
        private final BridgeRequest request;
        private final CompletableFuture<BridgeResponse> future;
        private ScheduledFuture<?> timeout;

        private PendingRequest(BridgeRequest request, CompletableFuture<BridgeResponse> future) {
            this.request = request;
            this.future = future;
        }

        private void cancelTimeout() {
            if (timeout != null) {
                timeout.cancel(false);
            }
        }
    }
}

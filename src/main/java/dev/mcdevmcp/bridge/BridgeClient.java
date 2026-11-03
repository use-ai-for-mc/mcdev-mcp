package dev.mcdevmcp.bridge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

public final class BridgeClient implements AutoCloseable {
    private static final Duration DEFAULT_ENDPOINT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RESPONSE_GRACE = Duration.ofSeconds(5);
    private static final Duration MAXIMUM_ENDPOINT_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAXIMUM_MESSAGE_CHARACTERS = 8 * 1024 * 1024;

    private final Object stateLock = new Object();
    private final Transport transport;
    private final BridgeJson json;
    private final Consumer<String> diagnostics;
    private final Consumer<Duration> timeoutObserver;
    private final AtomicLong requestCounter = new AtomicLong();
    private final Map<String, PendingRequest> pending = new ConcurrentHashMap<>();
    private final StringBuilder fragments = new StringBuilder();
    private boolean discardingFragments;
    private Consumer<BridgeClient> closedCallback = _ -> {
    };
    private boolean closed;

    private BridgeClient(Transport transport, BridgeJson json, Consumer<String> diagnostics, Consumer<Duration> timeoutObserver) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.json = Objects.requireNonNull(json, "json");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.timeoutObserver = Objects.requireNonNull(timeoutObserver, "timeoutObserver");
    }

    /**
     * Opens a WebSocket client. An interrupted caller leaves the handshake to the HTTP
     * client, which aborts a socket that opens afterwards.
     */
    public static BridgeClient connect(HttpClient client, URI uri, BridgeJson json) throws IOException, InterruptedException {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(uri, "uri");
        ConnectionListener listener = new ConnectionListener();
        CompletableFuture<WebSocket> opening = client.newWebSocketBuilder().buildAsync(uri, listener);
        WebSocket socket;
        try {
            socket = opening.get();
        } catch (InterruptedException exception) {
            opening.thenAccept(WebSocket::abort);
            throw exception;
        } catch (ExecutionException exception) {
            throw failure(exception.getCause());
        }
        BridgeClient connected = overWebSocket(socket, json);
        Throwable earlyFailure = listener.attach(connected);
        if (earlyFailure != null) {
            throw failure(earlyFailure);
        }
        return connected;
    }

    static BridgeClient overWebSocket(WebSocket socket, BridgeJson json) {
        return new BridgeClient(new WebSocketTransport(Objects.requireNonNull(socket, "socket")), json, _ -> {
        }, _ -> {
        });
    }

    static BridgeClient testing(BridgeJson json, Peer peer) {
        return testing(json, peer, _ -> {
        });
    }

    static BridgeClient testing(BridgeJson json, Peer peer, Consumer<String> diagnostics) {
        return testing(json, peer, diagnostics, _ -> {
        });
    }

    /**
     * A client whose peer answers every request on its own thread, independently of the
     * caller waiting for the response, as a real DebugBridge does.
     */
    static BridgeClient testing(BridgeJson json, Peer peer, Consumer<String> diagnostics, Consumer<Duration> timeoutObserver) {
        Objects.requireNonNull(peer, "peer");
        AtomicReference<BridgeClient> reference = new AtomicReference<>();
        Set<Thread> answering = ConcurrentHashMap.newKeySet();
        Transport transport = new Transport() {
            @Override
            public void send(BridgeRequest request, String text, Duration timeout) {
                BridgeClient client = reference.get();
                Thread answer = Thread.ofVirtual().name("debugbridge-test-peer").unstarted(() -> {
                    try {
                        BridgeResponse response = peer.answer(request);
                        if (response == null) {
                            client.fail(request.id(), new IllegalStateException("DebugBridge test peer returned null"));
                        }
                        else {
                            client.complete(request.id(), response);
                        }
                    } catch (InterruptedException _) {
                        // The client closed while the peer was still answering.
                    } catch (Throwable failure) {
                        client.fail(request.id(), failure);
                    } finally {
                        answering.remove(Thread.currentThread());
                    }
                });
                answering.add(answer);
                answer.start();
            }

            @Override
            public void close() {
                answering.forEach(Thread::interrupt);
            }
        };
        BridgeClient client = new BridgeClient(transport, json, diagnostics, timeoutObserver);
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

    private static Duration extendedTimeout(Duration requested) {
        try {
            return requested.plus(RESPONSE_GRACE);
        } catch (ArithmeticException exception) {
            return Duration.ofMillis(Long.MAX_VALUE);
        }
    }

    private static long remainingNanos(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    static IOException failure(Throwable cause) {
        return switch (cause) {
            case IOException io -> io;
            case RuntimeException runtime -> throw runtime;
            case Error error -> throw error;
            default -> new IOException(cause);
        };
    }

    /**
     * Sends one request and blocks until its response arrives, the effective timeout
     * elapses or the caller is interrupted; the request is unpublished on every exit.
     */
    public BridgeResponse send(BridgeEndpoint endpoint, BridgePayload payload, Duration endpointTimeout) throws IOException, InterruptedException {
        Objects.requireNonNull(endpoint, "endpoint");
        Duration effectiveTimeout = effectiveTimeout(endpointTimeout);
        timeoutObserver.accept(effectiveTimeout);
        PendingRequest pendingRequest;
        synchronized (stateLock) {
            if (closed) {
                throw new IllegalStateException("DebugBridge client is closed");
            }
            BridgeRequest request = new BridgeRequest("req_" + requestCounter.incrementAndGet(), endpoint, payload);
            pendingRequest = new PendingRequest(request, new CompletableFuture<>());
            pending.put(request.id(), pendingRequest);
        }
        long deadline = System.nanoTime() + effectiveTimeout.toNanos();
        try {
            if (Thread.interrupted()) {
                throw new InterruptedException("DebugBridge send cancelled");
            }
            transport.send(pendingRequest.request(), json.writeRequest(pendingRequest.request()), effectiveTimeout);
            return pendingRequest.response().get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            throw new IllegalStateException(timeoutMessage(endpointTimeout, effectiveTimeout));
        } catch (ExecutionException exception) {
            throw failure(exception.getCause());
        } finally {
            pending.remove(pendingRequest.request().id(), pendingRequest);
        }
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
            complete(response.id(), response);
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

    private void complete(String id, BridgeResponse response) {
        PendingRequest pendingRequest = pending.remove(id);
        if (pendingRequest == null) {
            diagnostics.accept("Ignoring unmatched DebugBridge response " + BridgePayloadValidator.safeDisplay(id));
            return;
        }
        pendingRequest.response().complete(response);
    }

    private void fail(String id, Throwable failure) {
        PendingRequest pendingRequest = pending.remove(id);
        if (pendingRequest == null) {
            diagnostics.accept("Ignoring unmatched DebugBridge response " + BridgePayloadValidator.safeDisplay(id));
            return;
        }
        pendingRequest.response().completeExceptionally(failure);
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
            if (pending.remove(pendingRequest.request().id(), pendingRequest)) {
                pendingRequest.response().completeExceptionally(failure);
            }
        }
        try {
            transport.close();
        } catch (RuntimeException _) {
            // A peer can already be gone.
        }
        callback.accept(this);
    }

    @FunctionalInterface
    interface Peer {
        BridgeResponse answer(BridgeRequest request) throws Exception;
    }

    interface Transport {
        void send(BridgeRequest request, String text, Duration timeout) throws IOException, InterruptedException, TimeoutException;

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
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            terminate(new IllegalStateException("DebugBridge closed: " + statusCode));
            return null;
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

    /**
     * WebSocket permits one outstanding text send, so sends are serialized, and a send
     * abandoned by a timed-out or interrupted caller is awaited by the next sender.
     */
    private static final class WebSocketTransport implements Transport {
        private final WebSocket socket;
        private final ReentrantLock sendLock = new ReentrantLock();
        private CompletableFuture<WebSocket> lastSend = CompletableFuture.completedFuture(null);

        private WebSocketTransport(WebSocket socket) {
            this.socket = socket;
        }

        @Override
        public void send(BridgeRequest request, String text, Duration timeout) throws IOException, InterruptedException, TimeoutException {
            long deadline = System.nanoTime() + timeout.toNanos();
            if (!sendLock.tryLock(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new TimeoutException();
            }
            try {
                try {
                    lastSend.get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
                } catch (ExecutionException _) {
                    // The abandoned send failed; its own caller has already given up.
                }
                lastSend = socket.sendText(text, true);
                lastSend.get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
            } catch (ExecutionException exception) {
                throw failure(exception.getCause());
            } finally {
                sendLock.unlock();
            }
        }

        @Override
        public void close() {
            socket.abort();
        }
    }

    private value record TextFragment(String text, boolean last) {
    }

    private record PendingRequest(BridgeRequest request, CompletableFuture<BridgeResponse> response) {
    }
}

package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.bridge.BridgePayload;
import dev.mcdevmcp.bridge.BridgeResponse;
import dev.mcdevmcp.bridge.BridgeSession;
import dev.mcdevmcp.bridge.SessionInfo;
import dev.mcdevmcp.mcp.tool.api.ToolCancellation;
import dev.mcdevmcp.mcp.tool.api.ContentToolResult;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.support.AppEnvironment;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;

final class SessionControlSupport {
    static final int BRIDGE_PORT_START = 9876;
    static final int BRIDGE_PORT_END = 9886;
    static final int DEFAULT_JOIN_TIMEOUT_SECONDS = 60;
    static final int DEFAULT_QUIT_TIMEOUT_SECONDS = 30;
    static final int DEFAULT_BRIDGE_WAIT_TIMEOUT_SECONDS = 120;

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    private static final Duration PROCESS_POLL_INTERVAL = Duration.ofMillis(250);
    private static final Duration PID_PROBE_TIMEOUT = Duration.ofSeconds(4);
    private static final BridgeEndpoint SNAPSHOT = new BridgeEndpoint("snapshot");
    private static final BridgeEndpoint SCREEN_INSPECT = new BridgeEndpoint("screenInspect");

    private final BridgeSession session;
    private final AppEnvironment environment;
    private final ScheduledExecutorService scheduler;
    private final MonotonicTicker ticker;
    private final PortListeningProbe portListeningProbe;
    private final ListeningPidResolver listeningPidResolver;

    SessionControlSupport(BridgeSession session, AppEnvironment environment, ScheduledExecutorService scheduler) {
        this(session, environment, scheduler, MonotonicTicker.system(), defaultPortListeningProbe(scheduler), defaultListeningPidResolver(scheduler));
    }

    SessionControlSupport(BridgeSession session, AppEnvironment environment, ScheduledExecutorService scheduler, MonotonicTicker ticker, PortListeningProbe portListeningProbe, ListeningPidResolver listeningPidResolver) {
        this.session = Objects.requireNonNull(session, "session");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.portListeningProbe = Objects.requireNonNull(portListeningProbe, "portListeningProbe");
        this.listeningPidResolver = Objects.requireNonNull(listeningPidResolver, "listeningPidResolver");
    }

    static <T, R> CompletionStage<R> mapCancellable(CompletionStage<T> stage, Function<T, R> mapper) {
        var result = new CancellableOperation<R>();
        result.pending(stage);
        stage.whenComplete((value, failure) -> {
            if (result.isDone()) {
                return;
            }
            if (failure != null) {
                result.completeExceptionally(failure);
                return;
            }
            try {
                result.complete(mapper.apply(value));
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    static <T, R> CompletionStage<R> composeCancellable(CompletionStage<T> stage, Function<T, CompletionStage<R>> mapper) {
        var result = new CancellableOperation<R>();
        result.pending(stage);
        stage.whenComplete((value, failure) -> {
            if (result.isDone()) {
                return;
            }
            if (failure != null) {
                result.completeExceptionally(failure);
                return;
            }
            CompletionStage<R> next;
            try {
                next = Objects.requireNonNull(mapper.apply(value), "Composed runtime operation returned no stage");
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
                return;
            }
            result.pending(next);
            next.whenComplete((mapped, mappedFailure) -> {
                if (result.isDone()) {
                    return;
                }
                if (mappedFailure != null) {
                    result.completeExceptionally(mappedFailure);
                }
                else {
                    result.complete(mapped);
                }
            });
        });
        return result;
    }

    static <T, R> CompletionStage<R> handleCancellable(CompletionStage<T> stage, BiFunction<T, Throwable, R> handler) {
        var result = new CancellableOperation<R>();
        result.pending(stage);
        stage.whenComplete((value, failure) -> {
            if (result.isDone()) {
                return;
            }
            try {
                result.complete(handler.apply(value, failure));
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }

    static CompletionStage<ContentToolResult<Void>> recoverTool(CompletionStage<ContentToolResult<Void>> stage) {
        var result = new CancellableOperation<ContentToolResult<Void>>();
        result.pending(stage);
        stage.whenComplete((value, failure) -> {
            if (result.isDone()) {
                return;
            }
            if (failure == null) {
                result.complete(value);
            }
            else {
                result.complete(ToolResult.error(message(failure)));
            }
        });
        return result;
    }

    static InWorldPollResult classifyInWorldPoll(Object snapshotResult, Object screenResult) {
        if (snapshotResult instanceof Map<?, ?> snapshot && truthy(snapshot.get("player"))) {
            return new InWorldPollResult.Joined();
        }
        if (screenResult instanceof Map<?, ?> screen && screen.get("type") instanceof String type && type.contains("DisconnectedScreen")) {
            Object title = screen.get("title");
            return new InWorldPollResult.Failed(title instanceof String text && !text.isEmpty() ? text : type);
        }
        return new InWorldPollResult.Pending();
    }

    static InWorldPollResult stepInWorldWait(InWorldWaitProgress progress, boolean requireAbsenceFirst, Object snapshotResult, Object screenResult) {
        InWorldPollResult classified = classifyInWorldPoll(snapshotResult, screenResult);
        if (snapshotResult instanceof Map<?, ?> && !(classified instanceof InWorldPollResult.Joined)) {
            progress.sawAbsence = true;
        }
        if (classified instanceof InWorldPollResult.Joined && requireAbsenceFirst && !progress.sawAbsence) {
            return new InWorldPollResult.Pending();
        }
        return classified;
    }

    static String sessionControlDisabledMessage(Path gameDirectory) {
        String config = gameDirectory == null ? "<minecraft>/config/debugbridge.json" : joinClientPath(gameDirectory);
        return "Session control is disabled in DebugBridge (session_control_enabled=false, the default).\n" + "To enable it: edit " + config + ", set \"session_control_enabled\": true, then restart the Minecraft client — the flag is only read at startup.";
    }

    // The game directory is reported by the Minecraft client and may use Windows
    // separators while this MCP server runs on POSIX. Join the config sub-path with
    // backslashes so the instruction matches the client's OS rather than the host's.
    private static String joinClientPath(Path base) {
        String root = base.toString();
        String separator = root.matches("[A-Za-z]:[\\\\/].*") ? "\\" : base.getFileSystem().getSeparator();
        StringBuilder joined = new StringBuilder(root);
        for (String segment : List.of("config", "debugbridge.json")) {
            if (!joined.isEmpty() && joined.charAt(joined.length() - 1) != separator.charAt(0)) {
                joined.append(separator);
            }
            joined.append(segment);
        }
        return joined.toString();
    }

    static Long parseListeningPid(String output) {
        Set<Long> pids = new LinkedHashSet<>();
        for (String line : output.split("\\R", -1)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!trimmed.chars().allMatch(Character::isDigit)) {
                return null;
            }
            try {
                pids.add(Long.parseLong(trimmed));
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        if (pids.size() != 1) {
            return null;
        }
        long pid = pids.iterator().next();
        return pid > 0 ? pid : null;
    }

    static boolean instanceMatches(SessionInfo info, ExpectedInstance expected) {
        if (expected.gameDirectory().isPresent()) {
            if (info.gameDir().isPresent()) {
                return expected.gameDirectory().equals(info.gameDir());
            }
            return expected.version().map(version -> version.equals(info.version())).orElse(false);
        }
        return expected.version().map(version -> version.equals(info.version())).orElse(true);
    }

    private static boolean truthy(Object value) {
        return switch (value) {
            case null -> false;
            case Boolean flag -> flag;
            case Number number -> number.doubleValue() != 0;
            case String text -> !text.isEmpty();
            default -> true;
        };
    }

    static long elapsedNanos(long started, long now) {
        return now - started;
    }

    private static double elapsedSeconds(long started, long now) {
        return Math.round(elapsedNanos(started, now) / 100_000_000.0) / 10.0;
    }

    static long saturatedNanos(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isZero() || duration.isNegative()) {
            return 0;
        }
        try {
            return duration.toNanos();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static String message(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    @SuppressWarnings("resource")
    private static PortListeningProbe defaultPortListeningProbe(ScheduledExecutorService scheduler) {
        return port -> {
            var result = new CompletableFuture<Boolean>();
            AsynchronousSocketChannel channel;
            try {
                channel = AsynchronousSocketChannel.open();
            } catch (IOException exception) {
                return CompletableFuture.completedFuture(false);
            }
            ScheduledFuture<?> timeout = scheduler.schedule(() -> {
                closeQuietly(channel);
                result.complete(false);
            }, saturatedNanos(Duration.ofMillis(800)), TimeUnit.NANOSECONDS);
            result.whenComplete((_, _) -> {
                timeout.cancel(false);
                closeQuietly(channel);
            });
            channel.connect(new InetSocketAddress("127.0.0.1", port), null, new CompletionHandler<>() {
                @Override
                public void completed(Void ignored, Object attachment) {
                    result.complete(true);
                }

                @Override
                public void failed(Throwable ignored, Object attachment) {
                    result.complete(false);
                }
            });
            return result;
        };
    }

    private static ListeningPidResolver defaultListeningPidResolver(ScheduledExecutorService scheduler) {
        return listeningPidResolver(scheduler, command -> new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start());
    }

    @SuppressWarnings("resource")
    static ListeningPidResolver listeningPidResolver(ScheduledExecutorService scheduler, ProcessStarter processStarter) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(processStarter, "processStarter");
        return port -> {
            List<String> command = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? List.of("powershell.exe", "-NoProfile", "-Command", "(Get-NetTCPConnection -LocalPort " + port + " -State Listen -ErrorAction SilentlyContinue).OwningProcess") : List.of("lsof", "-t", "-iTCP:" + port, "-sTCP:LISTEN");
            var result = new CompletableFuture<Long>();
            Process process;
            try {
                process = processStarter.start(command);
            } catch (IOException ioException) {
                return CompletableFuture.completedFuture(null);
            }
            AtomicReference<CleanupState> cleanup = new AtomicReference<>(CleanupState.OPEN);
            Object lifecycleLock = new Object();
            AtomicReference<ScheduledFuture<?>> timeoutRef = new AtomicReference<>();
            Runnable close = () -> {
                try {
                    process.close();
                } catch (IOException | RuntimeException ignored) {
                }
            };
            Runnable closeAfterAbort = () -> {
                if (cleanup.compareAndSet(CleanupState.ABORTED, CleanupState.CLOSING)) {
                    Thread.ofVirtual().start(close);
                }
            };
            Runnable abort = () -> {
                synchronized (lifecycleLock) {
                    if (!cleanup.compareAndSet(CleanupState.OPEN, CleanupState.ABORTED)) {
                        return;
                    }
                    try {
                        process.destroyForcibly();
                    } catch (RuntimeException ignored) {
                    } finally {
                        result.complete(null);
                    }
                }
                closeAfterAbort.run();
            };
            try {
                result.whenComplete((_, _) -> {
                    ScheduledFuture<?> timeout = timeoutRef.get();
                    if (timeout != null) {
                        timeout.cancel(false);
                    }
                    if (result.isCancelled()) {
                        abort.run();
                    }
                });
                process.onExit().whenComplete((exited, failure) -> {
                    synchronized (lifecycleLock) {
                        try {
                            if (cleanup.get() != CleanupState.OPEN || result.isDone()) {
                                return;
                            }
                            if (failure != null || exited.exitValue() != 0) {
                                result.complete(null);
                            }
                            else {
                                try {
                                    result.complete(parseListeningPid(new String(exited.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));
                                } catch (IOException ioException) {
                                    result.complete(null);
                                }
                            }
                        } finally {
                            if (cleanup.compareAndSet(CleanupState.OPEN, CleanupState.CLOSING)) {
                                close.run();
                            }
                        }
                    }
                });
                ScheduledFuture<?> timeout = scheduler.schedule(abort, saturatedNanos(PID_PROBE_TIMEOUT), TimeUnit.NANOSECONDS);
                timeoutRef.set(timeout);
                if (result.isDone()) {
                    timeout.cancel(false);
                }
            } catch (RuntimeException exception) {
                abort.run();
                return result;
            }
            return result;
        };
    }

    private static void closeQuietly(AsynchronousSocketChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }

    static boolean processAlive(long pid) {
        try {
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (RuntimeException exception) {
            return true;
        }
    }

    List<Integer> bridgePortRange() {
        var ports = new ArrayList<Integer>();
        environment.debugBridgePort().ifPresent(port -> {
            if (port < BRIDGE_PORT_START || port > BRIDGE_PORT_END) {
                ports.add(port);
            }
        });
        for (int port = BRIDGE_PORT_START; port <= BRIDGE_PORT_END; port++) {
            ports.add(port);
        }
        return List.copyOf(ports);
    }

    CompletionStage<String> checkSessionControlEnabled() {
        CompletionStage<SessionInfo> info;
        if (session.connectedPort().isPresent()) {
            info = CompletableFuture.completedFuture(session.sessionInfo().orElseThrow(() -> new IllegalStateException("DebugBridge connected without session information")));
        }
        else {
            info = session.connect(null);
        }
        return info.thenApply(sessionInfo -> sessionInfo.sessionControlEnabled().filter(enabled -> !enabled).map(_ -> sessionControlDisabledMessage(sessionInfo.gameDir().orElse(null))).orElse(null));
    }

    CompletionStage<InWorldWaitResult> waitUntilInWorld(Duration timeout, boolean requireAbsenceFirst, ToolCancellation cancellation) {
        long started = ticker.readNanos();
        var operation = new CancellableOperation<InWorldWaitResult>();
        var poller = new InWorldPoller(operation, cancellation, started, timeout, requireAbsenceFirst);
        operation.deadline(() -> operation.complete(new InWorldWaitResult(InWorldWaitResult.State.TIMEOUT, null, elapsedSeconds(started, ticker.readNanos()))), timeout, scheduler);
        poller.tick();
        return operation;
    }

    CompletionStage<FoundBridge> waitForBridge(ExpectedInstance expected, Duration timeout, List<String> notes, ToolCancellation cancellation) {
        long started = ticker.readNanos();
        var operation = new CancellableOperation<FoundBridge>();
        var waiter = new BridgeWaiter(operation, cancellation, expected, notes, started, timeout);
        operation.deadline(waiter::timeout, timeout, scheduler);
        waiter.sweep();
        return operation;
    }

    CompletionStage<Long> resolveListeningPid(int port) {
        return listeningPidResolver.resolve(port);
    }

    CompletionStage<BridgeResponse> send(BridgeEndpoint endpoint, BridgePayload payload, Duration timeout) {
        return session.send(endpoint, payload, timeout);
    }

    Optional<SessionInfo> sessionInfo() {
        return session.sessionInfo();
    }

    OptionalLong connectedPort() {
        return session.connectedPort().isPresent() ? OptionalLong.of(session.connectedPort().orElseThrow()) : OptionalLong.empty();
    }

    CompletionStage<SessionInfo> adoptPort(int port) {
        return session.adoptPort(port);
    }

    void disconnect() {
        session.disconnect();
    }

    CompletionStage<ClientExitResult> waitForClientExit(int port, Long pid, Duration timeout, ToolCancellation cancellation) {
        long started = ticker.readNanos();
        var operation = new CancellableOperation<ClientExitResult>();
        var phase = new AtomicReference<>(ClientExitResult.Phase.PORT);
        operation.deadline(() -> operation.complete(new ClientExitResult.Timeout(phase.get())), timeout, scheduler);
        pollPortClosed(operation, cancellation, port, pid, started, timeout, phase);
        return operation;
    }

    private void pollPortClosed(CancellableOperation<ClientExitResult> operation, ToolCancellation cancellation, int port, Long pid, long started, Duration timeout, AtomicReference<ClientExitResult.Phase> phase) {
        if (operation.stopIfCancelled(cancellation)) {
            return;
        }
        CompletionStage<Boolean> listening = portListeningProbe.isListening(port);
        operation.pending(listening);
        listening.whenComplete((isListening, failure) -> {
            if (operation.isDone()) {
                return;
            }
            boolean stillListening = failure != null || Boolean.TRUE.equals(isListening);
            if (!stillListening) {
                if (pid == null) {
                    operation.complete(new ClientExitResult.Exited(false));
                }
                else {
                    phase.set(ClientExitResult.Phase.PROCESS);
                    pollProcessExit(operation, cancellation, pid, started, timeout);
                }
                return;
            }
            if (elapsedNanos(started, ticker.readNanos()) >= saturatedNanos(timeout)) {
                operation.complete(new ClientExitResult.Timeout(ClientExitResult.Phase.PORT));
                return;
            }
            operation.schedule(() -> pollPortClosed(operation, cancellation, port, pid, started, timeout, phase), POLL_INTERVAL, scheduler);
        });
    }

    private void pollProcessExit(CancellableOperation<ClientExitResult> operation, ToolCancellation cancellation, long pid, long started, Duration timeout) {
        if (operation.stopIfCancelled(cancellation)) {
            return;
        }
        if (!processAlive(pid)) {
            operation.complete(new ClientExitResult.Exited(true));
            return;
        }
        if (elapsedNanos(started, ticker.readNanos()) >= saturatedNanos(timeout)) {
            operation.complete(new ClientExitResult.Timeout(ClientExitResult.Phase.PROCESS));
            return;
        }
        operation.schedule(() -> pollProcessExit(operation, cancellation, pid, started, timeout), PROCESS_POLL_INTERVAL, scheduler);
    }

    @FunctionalInterface
    interface PortListeningProbe {
        CompletionStage<Boolean> isListening(int port);
    }

    @FunctionalInterface
    interface ListeningPidResolver {
        CompletionStage<Long> resolve(int port);
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(List<String> command) throws IOException;
    }

    private enum CleanupState {
        OPEN, ABORTED, CLOSING
    }

    record ExpectedInstance(Optional<MinecraftVersion> version, Optional<Path> gameDirectory) {
        ExpectedInstance {
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(gameDirectory, "gameDirectory");
        }

        static ExpectedInstance none() {
            return new ExpectedInstance(Optional.empty(), Optional.empty());
        }
    }

    record FoundBridge(int port, SessionInfo info) {
    }

    static final class InWorldWaitProgress {
        private boolean sawAbsence;
    }

    private static final class CancellableOperation<T> extends CompletableFuture<T> {
        private final AtomicReference<Future<?>> pending = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> scheduled = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> deadline = new AtomicReference<>();

        private CancellableOperation() {
            whenComplete((_, _) -> cancelWork());
        }

        private void pending(CompletionStage<?> stage) {
            CompletableFuture<?> future = stage.toCompletableFuture();
            pending.set(future);
            if (isDone()) {
                future.cancel(true);
            }
            future.whenComplete((_, _) -> pending.compareAndSet(future, null));
        }

        private void schedule(Runnable action, Duration delay, ScheduledExecutorService scheduler) {
            if (isDone()) {
                return;
            }
            ScheduledFuture<?> future = scheduler.schedule(action, saturatedNanos(delay), TimeUnit.NANOSECONDS);
            ScheduledFuture<?> previous = scheduled.getAndSet(future);
            if (previous != null) {
                previous.cancel(false);
            }
            if (isDone()) {
                future.cancel(false);
            }
        }

        private void deadline(Runnable action, Duration delay, ScheduledExecutorService scheduler) {
            if (isDone()) {
                return;
            }
            ScheduledFuture<?> future = scheduler.schedule(action, saturatedNanos(delay), TimeUnit.NANOSECONDS);
            ScheduledFuture<?> previous = deadline.getAndSet(future);
            if (previous != null) {
                previous.cancel(false);
            }
            if (isDone()) {
                future.cancel(false);
            }
        }

        private boolean stopIfCancelled(ToolCancellation cancellation) {
            if (isDone()) {
                return true;
            }
            if (cancellation.isCancelled()) {
                cancel(true);
                return true;
            }
            return false;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            cancelWork();
            return cancelled;
        }

        private void cancelWork() {
            Future<?> active = pending.getAndSet(null);
            if (active != null) {
                active.cancel(true);
            }
            ScheduledFuture<?> timer = scheduled.getAndSet(null);
            if (timer != null) {
                timer.cancel(false);
            }
            ScheduledFuture<?> deadlineTimer = deadline.getAndSet(null);
            if (deadlineTimer != null) {
                deadlineTimer.cancel(false);
            }
        }
    }

    private final class InWorldPoller {
        private final CancellableOperation<InWorldWaitResult> operation;
        private final ToolCancellation cancellation;
        private final long started;
        private final Duration timeout;
        private final boolean requireAbsenceFirst;
        private final InWorldWaitProgress progress = new InWorldWaitProgress();

        private InWorldPoller(CancellableOperation<InWorldWaitResult> operation, ToolCancellation cancellation, long started, Duration timeout, boolean requireAbsenceFirst) {
            this.operation = operation;
            this.cancellation = cancellation;
            this.started = started;
            this.timeout = timeout;
            this.requireAbsenceFirst = requireAbsenceFirst;
        }

        private void tick() {
            if (operation.stopIfCancelled(cancellation)) {
                return;
            }
            CompletionStage<BridgeResponse> snapshot = session.send(SNAPSHOT, RuntimeToolSupport.EMPTY_PAYLOAD, null);
            operation.pending(snapshot);
            snapshot.handle((response, failure) -> failure == null && response.success() ? response.result() : null).whenComplete((snapshotResult, _) -> onSnapshot(snapshotResult));
        }

        private void onSnapshot(Object snapshotResult) {
            if (operation.isDone()) {
                return;
            }
            InWorldPollResult snapshotState = stepInWorldWait(progress, requireAbsenceFirst, snapshotResult, null);
            if (snapshotState instanceof InWorldPollResult.Joined) {
                operation.complete(new InWorldWaitResult(InWorldWaitResult.State.JOINED, null, elapsedSeconds(started, ticker.readNanos())));
                return;
            }
            CompletionStage<BridgeResponse> screen = session.send(SCREEN_INSPECT, RuntimeToolSupport.EMPTY_PAYLOAD, null);
            operation.pending(screen);
            screen.handle((response, failure) -> failure == null && response.success() ? response.result() : null).whenComplete((screenResult, _) -> onScreen(snapshotResult, screenResult));
        }

        private void onScreen(Object snapshotResult, Object screenResult) {
            if (operation.isDone()) {
                return;
            }
            InWorldPollResult state = stepInWorldWait(progress, requireAbsenceFirst, snapshotResult, screenResult);
            long now = ticker.readNanos();
            if (state instanceof InWorldPollResult.Failed(String reason)) {
                operation.complete(new InWorldWaitResult(InWorldWaitResult.State.FAILED, reason, elapsedSeconds(started, now)));
            }
            else if (elapsedNanos(started, now) >= saturatedNanos(timeout)) {
                operation.complete(new InWorldWaitResult(InWorldWaitResult.State.TIMEOUT, null, elapsedSeconds(started, now)));
            }
            else {
                operation.schedule(this::tick, POLL_INTERVAL, scheduler);
            }
        }
    }

    private final class BridgeWaiter {
        private final CancellableOperation<FoundBridge> operation;
        private final ToolCancellation cancellation;
        private final ExpectedInstance expected;
        private final List<String> notes;
        private final long started;
        private final Duration timeout;
        private final Map<Integer, String> mismatches = new LinkedHashMap<>();
        private List<Integer> ports;
        private int index;

        private BridgeWaiter(CancellableOperation<FoundBridge> operation, ToolCancellation cancellation, ExpectedInstance expected, List<String> notes, long started, Duration timeout) {
            this.operation = operation;
            this.cancellation = cancellation;
            this.expected = expected;
            this.notes = notes;
            this.started = started;
            this.timeout = timeout;
        }

        private synchronized void sweep() {
            if (operation.stopIfCancelled(cancellation)) {
                return;
            }
            if (elapsedNanos(started, ticker.readNanos()) >= saturatedNanos(timeout)) {
                operation.completeExceptionally(new IllegalStateException(timeoutMessage()));
                return;
            }
            ports = bridgePortRange();
            index = 0;
            probeNext();
        }

        private synchronized void timeout() {
            operation.completeExceptionally(new IllegalStateException(timeoutMessage()));
        }

        private synchronized void probeNext() {
            if (operation.stopIfCancelled(cancellation)) {
                return;
            }
            if (index >= ports.size()) {
                operation.schedule(this::sweep, POLL_INTERVAL, scheduler);
                return;
            }
            int port = ports.get(index++);
            CompletionStage<SessionInfo> probe = session.probe(port);
            operation.pending(probe);
            probe.whenComplete((info, failure) -> onProbe(port, info, failure));
        }

        private synchronized void onProbe(int port, SessionInfo info, Throwable failure) {
            if (operation.isDone()) {
                return;
            }
            if (failure == null && instanceMatches(info, expected)) {
                operation.complete(new FoundBridge(port, info));
                return;
            }
            if (failure == null) {
                String description = info.version().value() + " (" + info.gameDir().map(Path::toString).orElse("unknown gameDir") + ")";
                if (!description.equals(mismatches.put(port, description))) {
                    notes.add("port " + port + " answered with a different instance: " + description + " — skipping");
                }
            }
            probeNext();
        }

        private String timeoutMessage() {
            String expectedDescription = expected.gameDirectory().map(Path::toString).orElseGet(() -> expected.version().map(MinecraftVersion::value).orElse("any instance"));
            String seen = mismatches.isEmpty() ? "" : " Other instances answered: " + String.join(", ", mismatches.entrySet().stream().map(entry -> "port " + entry.getKey() + " → " + entry.getValue()).toList()) + ".";
            double timeoutSeconds = timeout.getSeconds() + timeout.getNano() / 1_000_000_000.0;
            return "Timed out after " + Math.round(timeoutSeconds) + "s waiting for the bridge of " + expectedDescription + " on ports " + BRIDGE_PORT_START + "-" + BRIDGE_PORT_END + "." + seen + " If you just launched the client, check the launcher window: it may be sitting on a login prompt (the user must log in once in the launcher GUI), or the game may have crashed — read <gameDir>/logs/latest.log.";
        }
    }
}

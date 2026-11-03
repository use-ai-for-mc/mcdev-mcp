package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.*;
import dev.mcdevmcp.mcp.tool.api.ToolCancellation;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.ScopeOutcomes;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Blocking, interruptible session control; every wait runs under a structured
 * task scope that owns its deadline.
 */
value class SessionControlSupport {
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
    private final MonotonicTicker ticker;
    private final PortListeningProbe portListeningProbe;
    private final ListeningPidResolver listeningPidResolver;

    SessionControlSupport(BridgeSession session, AppEnvironment environment) {
        this(session, environment, MonotonicTicker.system(), defaultPortListeningProbe(), defaultListeningPidResolver());
    }

    SessionControlSupport(BridgeSession session, AppEnvironment environment, MonotonicTicker ticker, PortListeningProbe portListeningProbe, ListeningPidResolver listeningPidResolver) {
        this.session = Objects.requireNonNull(session, "session");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.portListeningProbe = Objects.requireNonNull(portListeningProbe, "portListeningProbe");
        this.listeningPidResolver = Objects.requireNonNull(listeningPidResolver, "listeningPidResolver");
    }

    private static String bridgeTimeoutMessage(ExpectedInstance expected, Duration timeout, Map<Integer, String> mismatches) {
        String expectedDescription = expected.gameDirectory().map(Path::toString).orElseGet(() -> expected.version().map(MinecraftVersion::value).orElse("any instance"));
        String seen = mismatches.isEmpty() ? "" : " Other instances answered: " + String.join(", ", mismatches.entrySet().stream().map(entry -> "port " + entry.getKey() + " → " + entry.getValue()).toList()) + ".";
        double timeoutSeconds = timeout.getSeconds() + timeout.getNano() / 1_000_000_000.0;
        return "Timed out after " + Math.round(timeoutSeconds) + "s waiting for the bridge of " + expectedDescription + " on ports " + BRIDGE_PORT_START + "-" + BRIDGE_PORT_END + "." + seen + " If you just launched the client, check the launcher window: it may be sitting" + " on a login prompt (the user must log in once in the launcher GUI), or the game may" + " have crashed — read <gameDir>/logs/latest.log.";
    }

    /**
     * Run the operation under a scope-owned deadline. Cancelling the tool call interrupts
     * the caller, and closing the scope interrupts and joins the operation.
     */
    @SuppressWarnings("preview")
    private static <T> T withinDeadline(Duration timeout, ToolCancellation cancellation, Callable<T> work, Supplier<T> timedOut) throws InterruptedException {
        checkCancelled(cancellation);
        long nanos = saturatedNanos(timeout);
        if (nanos == 0) {
            return timedOut.get();
        }
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<T>anySuccessfulOrThrow(), config -> config.withName("session-control").withTimeout(Duration.ofNanos(nanos)))) {
            scope.fork(work);
            return scope.join();
        } catch (ExecutionException exception) {
            if (ScopeOutcomes.timedOut(exception)) {
                // close() has already joined the operation; timeout reporting may
                // safely inspect state such as the phase or accumulated mismatches.
                return timedOut.get();
            }
            Throwable failure = ScopeOutcomes.cause(exception);
            switch (failure) {
                case IOException _ -> {
                    return null;
                }
                case InterruptedException interrupted -> throw interrupted;
                case RuntimeException runtime -> throw runtime;
                default -> throw new IllegalStateException(failure);
            }
        }
    }

    private static void checkCancelled(ToolCancellation cancellation) throws InterruptedException {
        checkInterrupted();
        if (cancellation != null && cancellation.isCancelled()) {
            throw new CancellationException("Tool operation cancelled");
        }
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Session control interrupted");
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    static String message(Throwable failure) {
        Throwable cause = unwrap(failure);
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    private static PortListeningProbe defaultPortListeningProbe() {
        return port -> Boolean.TRUE.equals(withinDeadline(Duration.ofMillis(800), null, () -> {
            checkInterrupted();
            // SocketChannel.connect is interruptible on virtual threads and the
            // scope retains the old 800ms per-port bound.
            try (var socket = SocketChannel.open()) {
                socket.configureBlocking(true);
                socket.connect(new InetSocketAddress("127.0.0.1", port));
                checkInterrupted();
                return true;
            } catch (IOException _) {
                checkInterrupted();
                return false;
            }
        }, () -> false));
    }

    private static ListeningPidResolver defaultListeningPidResolver() {
        return listeningPidResolver(command -> new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start());
    }

    static ListeningPidResolver listeningPidResolver(ProcessStarter processStarter) {
        Objects.requireNonNull(processStarter, "processStarter");
        return port -> {
            List<String> command = listeningPidCommand(port);
            return withinDeadline(PID_PROBE_TIMEOUT, null, () -> {
                checkInterrupted();
                Process process;
                try {
                    process = processStarter.start(command);
                } catch (IOException exception) {
                    return null;
                }
                boolean interrupted = false;
                try {
                    checkInterrupted();
                    return readListeningPid(process);
                } catch (InterruptedException failure) {
                    interrupted = true;
                    destroyQuietly(process);
                    throw failure;
                } catch (RuntimeException failure) {
                    destroyQuietly(process);
                    throw failure;
                } finally {
                    closeProcess(process);
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, () -> null);
        };
    }

    private static void destroyQuietly(Process process) {
        try {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        } catch (RuntimeException _) {
            // Cleanup is best effort; the owner still closes the process.
        }
    }

    private static void closeProcess(Process process) {
        try {
            process.close();
        } catch (IOException | RuntimeException _) {
            // The owning scope has already destroyed the process on failure.
        }
    }

    @SuppressWarnings("preview")
    private static Long readListeningPid(Process process) throws InterruptedException {
        try (var scope = StructuredTaskScope.open()) {
            try {
                // Drain output while waiting: waiting for exit before reading
                // stdout can deadlock when the subprocess fills its pipe.
                var output = scope.fork(() -> new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                scope.fork(() -> process.getErrorStream().transferTo(OutputStream.nullOutputStream()));
                var exit = scope.fork(() -> process.waitFor());
                scope.join();
                return exit.get() == 0 ? parseListeningPid(output.get()) : null;
            } finally {
                // Destroy before scope close joins stream readers.
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        } catch (ExecutionException exception) {
            switch (exception.getCause()) {
                case IOException _ -> {
                    return null;
                }
                case InterruptedException interrupted -> throw interrupted;
                case RuntimeException runtime -> throw runtime;
                case Throwable failure -> throw new IllegalStateException(failure);
            }
        }
    }

    private static List<String> listeningPidCommand(int port) {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? List.of("powershell.exe", "-NoProfile", "-Command", "(Get-NetTCPConnection -LocalPort " + port + " -State Listen -ErrorAction SilentlyContinue).OwningProcess") : List.of("lsof", "-t", "-iTCP:" + port, "-sTCP:LISTEN");
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
        SequencedSet<Long> pids = new LinkedHashSet<>();
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
        long pid = pids.getFirst();
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

    static boolean processAlive(long pid) {
        try {
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (RuntimeException exception) {
            return true;
        }
    }

    String checkSessionControlEnabled() throws IOException, InterruptedException {
        SessionInfo info = session.connectedPort().isPresent() ? session.sessionInfo().orElseThrow(() -> new IllegalStateException("DebugBridge connected without session information")) : session.connect(null);
        return info.sessionControlEnabled().filter(enabled -> !enabled).map(_ -> sessionControlDisabledMessage(info.gameDir().orElse(null))).orElse(null);
    }

    InWorldWaitResult waitUntilInWorld(Duration timeout, boolean requireAbsenceFirst, ToolCancellation cancellation) throws InterruptedException {
        Objects.requireNonNull(cancellation, "cancellation");
        long started = ticker.readNanos();
        long budget = saturatedNanos(timeout);
        return withinDeadline(remaining(started, budget), cancellation, () -> pollInWorld(started, budget, requireAbsenceFirst, cancellation), () -> inWorldResult(InWorldWaitResult.State.TIMEOUT, null, started));
    }

    FoundBridge waitForBridge(ExpectedInstance expected, Duration timeout, List<String> notes, ToolCancellation cancellation) throws InterruptedException {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(notes, "notes");
        Objects.requireNonNull(cancellation, "cancellation");
        long started = ticker.readNanos();
        long budget = saturatedNanos(timeout);
        var mismatches = new LinkedHashMap<Integer, String>();
        Supplier<FoundBridge> timedOut = () -> {
            throw new IllegalStateException(bridgeTimeoutMessage(expected, timeout, mismatches));
        };
        return withinDeadline(remaining(started, budget), cancellation, () -> pollBridge(expected, notes, mismatches, started, budget, cancellation, timedOut), timedOut);
    }

    ClientExitResult waitForClientExit(int port, Long pid, Duration timeout, ToolCancellation cancellation) throws InterruptedException {
        Objects.requireNonNull(cancellation, "cancellation");
        long started = ticker.readNanos();
        long budget = saturatedNanos(timeout);
        var phase = new AtomicReference<>(ClientExitResult.Phase.PORT);
        return withinDeadline(remaining(started, budget), cancellation, () -> pollClientExit(port, pid, started, budget, cancellation, phase), () -> new ClientExitResult.Timeout(phase.get()));
    }

    Long resolveListeningPid(int port) throws InterruptedException {
        return listeningPidResolver.resolve(port);
    }

    BridgeResponse send(BridgeEndpoint endpoint, BridgePayload payload, Duration timeout) throws IOException, InterruptedException {
        return session.send(endpoint, payload, timeout);
    }

    Optional<SessionInfo> sessionInfo() {
        return session.sessionInfo();
    }

    OptionalLong connectedPort() {
        var port = session.connectedPort();
        return port.isPresent() ? OptionalLong.of(port.orElseThrow()) : OptionalLong.empty();
    }

    SessionInfo adoptPort(int port) throws IOException, InterruptedException {
        return session.adoptPort(port);
    }

    void disconnect() {
        session.disconnect();
    }

    private InWorldWaitResult pollInWorld(long started, long budget, boolean requireAbsenceFirst, ToolCancellation cancellation) throws InterruptedException {
        var progress = new InWorldWaitProgress();
        while (true) {
            checkCancelled(cancellation);
            if (expired(started, budget)) {
                return inWorldResult(InWorldWaitResult.State.TIMEOUT, null, started);
            }
            Object snapshot = queryResult(SNAPSHOT);
            checkCancelled(cancellation);
            var state = stepInWorldWait(progress, requireAbsenceFirst, snapshot, null);
            if (state instanceof InWorldPollResult.Joined) {
                return inWorldResult(InWorldWaitResult.State.JOINED, null, started);
            }
            if (expired(started, budget)) {
                return inWorldResult(InWorldWaitResult.State.TIMEOUT, null, started);
            }
            Object screen = queryResult(SCREEN_INSPECT);
            checkCancelled(cancellation);
            state = stepInWorldWait(progress, requireAbsenceFirst, snapshot, screen);
            if (state instanceof InWorldPollResult.Failed(String reason)) {
                return inWorldResult(InWorldWaitResult.State.FAILED, reason, started);
            }
            pause(POLL_INTERVAL, started, budget);
        }
    }

    private Object queryResult(BridgeEndpoint endpoint) throws InterruptedException {
        try {
            BridgeResponse response = session.send(endpoint, RuntimeToolSupport.EMPTY_PAYLOAD, null);
            return response.success() ? response.result() : null;
        } catch (IOException | RuntimeException _) {
            checkInterrupted();
            return null;
        }
    }

    private FoundBridge pollBridge(ExpectedInstance expected, List<String> notes, Map<Integer, String> mismatches, long started, long budget, ToolCancellation cancellation, Supplier<FoundBridge> timedOut) throws InterruptedException {
        while (true) {
            checkCancelled(cancellation);
            if (expired(started, budget)) {
                return timedOut.get();
            }
            // Deliberately sequential: do not change preferred-port ordering or
            // assume that BridgeSession supports concurrent probes.
            for (int port : bridgePortRange()) {
                checkCancelled(cancellation);
                if (expired(started, budget)) {
                    return timedOut.get();
                }
                SessionInfo info;
                try {
                    info = session.probe(port);
                } catch (IOException | RuntimeException _) {
                    checkInterrupted();
                    continue;
                }
                checkCancelled(cancellation);
                if (instanceMatches(info, expected)) {
                    return new FoundBridge(port, info);
                }
                String description = info.version().value() + " (" + info.gameDir().map(Path::toString).orElse("unknown gameDir") + ")";
                if (!description.equals(mismatches.put(port, description))) {
                    notes.add("port " + port + " answered with a different instance: " + description + " — skipping");
                }
            }
            pause(POLL_INTERVAL, started, budget);
        }
    }

    private ClientExitResult pollClientExit(int port, Long pid, long started, long budget, ToolCancellation cancellation, AtomicReference<ClientExitResult.Phase> phase) throws InterruptedException {
        while (true) {
            checkCancelled(cancellation);
            if (expired(started, budget)) {
                return new ClientExitResult.Timeout(ClientExitResult.Phase.PORT);
            }
            boolean listening;
            try {
                listening = portListeningProbe.isListening(port);
            } catch (RuntimeException _) {
                checkInterrupted();
                listening = true; // A failed probe is not evidence that the port closed.
            }
            checkCancelled(cancellation);
            if (!listening) {
                break;
            }
            pause(POLL_INTERVAL, started, budget);
        }
        if (pid == null) {
            return new ClientExitResult.Exited(false);
        }
        phase.set(ClientExitResult.Phase.PROCESS);
        while (true) {
            checkCancelled(cancellation);
            if (expired(started, budget)) {
                return new ClientExitResult.Timeout(ClientExitResult.Phase.PROCESS);
            }
            if (!processAlive(pid)) {
                return new ClientExitResult.Exited(true);
            }
            pause(PROCESS_POLL_INTERVAL, started, budget);
        }
    }

    private InWorldWaitResult inWorldResult(InWorldWaitResult.State state, String reason, long started) {
        return new InWorldWaitResult(state, reason, elapsedSeconds(started, ticker.readNanos()));
    }

    private boolean expired(long started, long budget) {
        return elapsedNanos(started, ticker.readNanos()) >= budget;
    }

    private Duration remaining(long started, long budget) {
        long elapsed = Math.max(0, elapsedNanos(started, ticker.readNanos()));
        return Duration.ofNanos(elapsed >= budget ? 0 : budget - elapsed);
    }

    private void pause(Duration interval, long started, long budget) throws InterruptedException {
        Thread.sleep(Duration.ofNanos(Math.min(saturatedNanos(interval), remaining(started, budget).toNanos())));
    }

    /**
     * The ports a bridge may answer on: the configured port first, so that it is the one found when
     * several clients are running, then the rest of the documented range.
     */
    List<Integer> bridgePortRange() {
        var ports = new LinkedHashSet<Integer>();
        environment.debugBridgePort().ifPresent(ports::add);
        for (int port = BRIDGE_PORT_START; port <= BRIDGE_PORT_END; port++) {
            ports.add(port);
        }
        return List.copyOf(ports);
    }

    @FunctionalInterface
    interface PortListeningProbe {
        boolean isListening(int port) throws InterruptedException;
    }

    @FunctionalInterface
    interface ListeningPidResolver {
        Long resolve(int port) throws InterruptedException;
    }

    @FunctionalInterface
    interface ProcessStarter {
        Process start(List<String> command) throws IOException;
    }

    static final class InWorldWaitProgress {
        private boolean sawAbsence;
    }

}
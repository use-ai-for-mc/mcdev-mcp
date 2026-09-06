package dev.mcdevmcp.parity;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A shell-free, NDJSON-only JSON-RPC client for parity tests.
 */
final class McpProcessClient implements AutoCloseable {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration QUIET_PERIOD = Duration.ofMillis(100);
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();
    private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() {
    };

    private final Process process;
    private final String processDescription;
    private final boolean allowForcedShutdown;
    private final ProcessTreeTracker processTree;
    private final BufferedWriter stdin;
    private final BlockingQueue<OutputEvent> stdoutEvents = new LinkedBlockingQueue<>();
    private final Set<String> completedResponseIds = new HashSet<>();
    private final List<Map<String, Object>> notifications = new ArrayList<>();
    private final AtomicLong nextRequestId = new AtomicLong(1);
    private final Duration timeout;
    private final Object stderrLock = new Object();
    private final StringBuilder stderr = new StringBuilder();
    private final Thread stdoutPump;
    private final Thread stderrPump;
    private volatile IOException stderrFailure;
    private boolean closed;
    private boolean forcedShutdown;

    private McpProcessClient(Process process, Duration timeout, String processDescription, boolean allowForcedShutdown) {
        this.process = Objects.requireNonNull(process, "process");
        this.processDescription = Objects.requireNonNull(processDescription, "processDescription");
        this.allowForcedShutdown = allowForcedShutdown;
        this.timeout = requirePositiveTimeout(timeout);
        processTree = new ProcessTreeTracker(process.toHandle());
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        stdoutPump = Thread.ofVirtual().name("mcp-parity-stdout").start(this::pumpStdout);
        stderrPump = Thread.ofVirtual().name("mcp-parity-stderr").start(this::pumpStderr);
    }

    static McpProcessClient start(ProcessBuilder processBuilder) throws IOException {
        return start(processBuilder, DEFAULT_TIMEOUT);
    }

    static McpProcessClient start(ProcessBuilder processBuilder, Duration timeout) throws IOException {
        return start(processBuilder, timeout, false);
    }

    static McpProcessClient startAllowingForcedShutdown(ProcessBuilder processBuilder) throws IOException {
        return start(processBuilder, DEFAULT_TIMEOUT, true);
    }

    private static McpProcessClient start(ProcessBuilder processBuilder, Duration timeout, boolean allowForcedShutdown) throws IOException {
        Objects.requireNonNull(processBuilder, "processBuilder");
        Duration validatedTimeout = requirePositiveTimeout(timeout);
        if (processBuilder.redirectErrorStream()) {
            throw new IllegalArgumentException("MCP parity processes must keep STDERR separate from STDOUT");
        }
        if (processBuilder.redirectInput() != ProcessBuilder.Redirect.PIPE || processBuilder.redirectOutput() != ProcessBuilder.Redirect.PIPE || processBuilder.redirectError() != ProcessBuilder.Redirect.PIPE) {
            throw new IllegalArgumentException("MCP parity processes must use piped standard streams");
        }
        return new McpProcessClient(processBuilder.start(), validatedTimeout, processBuilder.command().getFirst(), allowForcedShutdown);
    }

    synchronized Map<String, Object> initialize(Map<String, Object> parameters) throws IOException {
        Map<String, Object> response = request("initialize", parameters);
        sendInitializedNotification();
        return response;
    }

    synchronized Map<String, Object> request(String method, Map<String, Object> parameters) throws IOException {
        Objects.requireNonNull(method, "method");
        return request(jsonRpcRequest(nextRequestId.getAndIncrement(), method, parameters));
    }

    synchronized Map<String, Object> request(Map<String, Object> request) throws IOException {
        ensureOpen();
        Objects.requireNonNull(request, "request");
        Object requestId = request.get("id");
        if (requestId == null) {
            throw new IllegalArgumentException("JSON-RPC requests require a non-null id");
        }
        String requestJson;
        try {
            requestJson = MAPPER.writeValueAsString(request);
        } catch (IOException exception) {
            throw failAndClose("Could not encode JSON-RPC request", exception);
        }

        try {
            stdin.write(requestJson);
            stdin.newLine();
            stdin.flush();
        } catch (IOException exception) {
            throw failAndClose("Could not write JSON-RPC request " + requestId, exception);
        }
        return awaitResponse(requestId);
    }

    private void sendInitializedNotification() throws IOException {
        ensureOpen();
        var notification = new LinkedHashMap<String, Object>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        notification.put("params", Map.of());
        try {
            stdin.write(MAPPER.writeValueAsString(notification));
            stdin.newLine();
            stdin.flush();
        } catch (IOException exception) {
            throw failAndClose("Could not write JSON-RPC initialized notification", exception);
        }
    }

    synchronized String stderr() {
        synchronized (stderrLock) {
            return stderr.toString();
        }
    }

    /**
     * Waits for the protocol stream to go quiet, rejects unmatched responses, and returns every server notification observed so far.
     */
    synchronized List<Map<String, Object>> awaitQuiescence() throws IOException {
        ensureOpen();
        long now = System.nanoTime();
        long quietDeadline = now + QUIET_PERIOD.toNanos();
        long overallDeadline = now + timeout.toNanos();
        while (true) {
            long remainingNanos = Math.min(quietDeadline, overallDeadline) - System.nanoTime();
            if (remainingNanos <= 0) {
                if (System.nanoTime() >= overallDeadline) {
                    throw failAndClose("Timed out after " + timeout + " waiting for MCP protocol quiescence", null);
                }
                break;
            }
            OutputEvent event;
            try {
                event = stdoutEvents.poll(remainingNanos, TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw failAndClose("Interrupted while waiting for MCP protocol quiescence", exception);
            }
            if (event == null) {
                break;
            }
            if (event instanceof EndOfStream(IOException failure)) {
                String detail = failure == null ? "MCP process closed STDOUT before protocol quiescence" : "Could not read MCP process STDOUT while waiting for protocol quiescence";
                throw failAndClose(detail, failure);
            }
            acceptUnmatchedMessage(parseOutputLine((OutputLine) event));
            quietDeadline = System.nanoTime() + QUIET_PERIOD.toNanos();
        }
        return List.copyOf(notifications);
    }

    synchronized List<Map<String, Object>> notifications() {
        return List.copyOf(notifications);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        processTree.capture();
        try {
            stdin.close();
        } catch (IOException exception) {
            failure = exception;
        }
        failure = stopProcess(failure);
        failure = awaitPump(stdoutPump, "STDOUT", failure);
        failure = awaitPump(stderrPump, "STDERR", failure);
        failure = drainRemainingOutput(failure);
        if (stderrFailure != null) {
            failure = appendFailure(failure, stderrFailure);
        }
        if (process.isAlive()) {
            failure = appendFailure(failure, new IOException("MCP process remained alive after cleanup"));
        }
        else if (process.exitValue() != 0 && !(allowForcedShutdown && forcedShutdown)) {
            failure = appendFailure(failure, processFailure("MCP process (" + processDescription + ") exited with code " + process.exitValue(), null));
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static Duration requirePositiveTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return timeout;
    }

    private static Map<String, Object> jsonRpcRequest(long id, String method, Map<String, Object> parameters) {
        var request = new LinkedHashMap<String, Object>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", Map.copyOf(Objects.requireNonNull(parameters, "parameters")));
        return request;
    }

    private Map<String, Object> awaitResponse(Object requestId) throws IOException {
        String key = responseKey(requestId);
        long remainingNanos = timeout.toNanos();
        long deadline = System.nanoTime() + remainingNanos;
        long processExitObservedAt = 0;
        while (remainingNanos > 0) {
            OutputEvent event;
            try {
                event = stdoutEvents.poll(Math.min(remainingNanos, QUIET_PERIOD.toNanos()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw failAndClose("Interrupted while waiting for JSON-RPC response " + requestId, exception);
            }
            if (event == null) {
                long now = System.nanoTime();
                if (!process.isAlive()) {
                    if (processExitObservedAt != 0 && now - processExitObservedAt >= QUIET_PERIOD.toNanos()) {
                        throw failAndClose("MCP process exited before responding to request " + requestId, null);
                    }
                    processExitObservedAt = now;
                }
                remainingNanos = deadline - now;
                continue;
            }
            if (event instanceof EndOfStream(IOException failure)) {
                String detail = failure == null ? "MCP process closed STDOUT before responding to request " + requestId : "Could not read MCP process STDOUT while waiting for request " + requestId;
                throw failAndClose(detail, failure);
            }

            Map<String, Object> response = parseOutputLine((OutputLine) event);
            Object responseId = response.get("id");
            if (responseId == null) {
                if (isNotification(response)) {
                    notifications.add(immutableMessage(response));
                    remainingNanos = deadline - System.nanoTime();
                    continue;
                }
                throw failAndClose("MCP process emitted an invalid message without a JSON-RPC id", null);
            }
            String responseKey = responseKey(responseId);
            if (key.equals(responseKey)) {
                recordCompletedResponse(key);
                return response;
            }
            if (completedResponseIds.contains(responseKey)) {
                throw failAndClose("MCP process emitted duplicate JSON-RPC response id " + responseId, null);
            }
            throw failAndClose("MCP process emitted unmatched JSON-RPC response id " + responseId + " while awaiting " + requestId, null);
        }
        throw failAndClose("Timed out after " + timeout + " waiting for JSON-RPC response " + requestId, null);
    }

    private Map<String, Object> parseOutputLine(OutputLine outputLine) throws IOException {
        try {
            Map<String, Object> message = MAPPER.readValue(outputLine.line(), MAP_TYPE);
            if (message == null) {
                throw new IllegalArgumentException("MCP process emitted top-level JSON null");
            }
            validateIncomingMessage(message);
            return message;
        } catch (IOException exception) {
            throw failAndClose("MCP process emitted non-JSON NDJSON output", exception);
        } catch (IllegalArgumentException exception) {
            throw failAndClose(exception.getMessage(), exception);
        }
    }

    private void acceptUnmatchedMessage(Map<String, Object> message) throws IOException {
        Object responseId = message.get("id");
        if (responseId == null) {
            if (isNotification(message)) {
                notifications.add(immutableMessage(message));
                return;
            }
            throw failAndClose("MCP process emitted an invalid message without a JSON-RPC id", null);
        }
        String key = responseKey(responseId);
        if (completedResponseIds.contains(key)) {
            throw failAndClose("MCP process emitted duplicate JSON-RPC response id " + responseId, null);
        }
        throw failAndClose("MCP process emitted unmatched JSON-RPC response id " + responseId, null);
    }

    private static boolean isNotification(Map<String, Object> message) {
        return "2.0".equals(message.get("jsonrpc")) && message.get("method") instanceof String && !message.containsKey("id") && !message.containsKey("result") && !message.containsKey("error");
    }

    private static void validateIncomingMessage(Map<String, Object> message) {
        if (!"2.0".equals(message.get("jsonrpc"))) {
            throw new IllegalArgumentException("MCP process emitted a JSON-RPC message without jsonrpc exactly 2.0");
        }

        boolean hasId = message.containsKey("id");
        boolean hasMethod = message.containsKey("method");
        boolean hasResult = message.containsKey("result");
        boolean hasError = message.containsKey("error");
        if (!hasId) {
            if (!(message.get("method") instanceof String) || hasResult || hasError) {
                throw new IllegalArgumentException("MCP process emitted a malformed JSON-RPC notification envelope");
            }
            return;
        }

        Object id = message.get("id");
        responseKey(id);
        if (hasMethod) {
            throw new IllegalArgumentException("MCP process emitted an unsupported JSON-RPC server request");
        }
        if (hasResult == hasError) {
            throw new IllegalArgumentException("MCP process emitted a malformed JSON-RPC response without exactly one of result or error");
        }
        if (hasError) {
            Object error = message.get("error");
            if (!(error instanceof Map<?, ?> errorFields) || !(errorFields.get("code") instanceof Number) || !(errorFields.get("message") instanceof String)) {
                throw new IllegalArgumentException("MCP process emitted a malformed JSON-RPC error object");
            }
        }
    }

    private static Map<String, Object> immutableMessage(Map<String, Object> message) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(message));
    }

    private void pumpStdout() {
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stdoutEvents.put(new OutputLine(line));
            }
            stdoutEvents.add(new EndOfStream(null));
        } catch (IOException exception) {
            stdoutEvents.add(new EndOfStream(exception));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            stdoutEvents.add(new EndOfStream(new IOException("STDOUT pump interrupted", exception)));
        }
    }

    private void pumpStderr() {
        try (var reader = new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[1024];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                synchronized (stderrLock) {
                    stderr.append(buffer, 0, count);
                }
            }
        } catch (IOException exception) {
            stderrFailure = exception;
        }
    }

    private IOException stopProcess(IOException failure) {
        boolean interrupted = Thread.interrupted();
        try {
            processTree.capture();
            try {
                process.waitFor(SHUTDOWN_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                interrupted = true;
                failure = appendFailure(failure, new IOException("Interrupted while stopping MCP process", exception));
            }
            ProcessTreeTracker.TerminationResult termination = processTree.terminateAndAwait(failure);
            failure = termination.failure();
            forcedShutdown = termination.rootTerminationRequested() && !process.isAlive();
        } finally {
            failure = processTree.stop(failure);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return failure;
    }

    private IOException awaitPump(Thread pump, String stream, IOException failure) {
        try {
            pump.join(SHUTDOWN_TIMEOUT);
            if (pump.isAlive()) {
                pump.interrupt();
                return appendFailure(failure, new IOException(stream + " pump did not stop"));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return appendFailure(failure, new IOException("Interrupted while joining " + stream + " pump", exception));
        }
        return failure;
    }

    private IOException drainRemainingOutput(IOException failure) {
        OutputEvent event;
        while ((event = stdoutEvents.poll()) != null) {
            if (event instanceof EndOfStream(IOException streamFailure)) {
                if (streamFailure != null) {
                    failure = appendFailure(failure, streamFailure);
                }
                continue;
            }
            try {
                Map<String, Object> message = MAPPER.readValue(((OutputLine) event).line(), MAP_TYPE);
                if (message == null) {
                    failure = appendFailure(failure, processFailure("MCP process emitted top-level JSON null", null));
                    continue;
                }
                validateIncomingMessage(message);
                Object responseId = message.get("id");
                if (responseId == null) {
                    if (isNotification(message)) {
                        notifications.add(immutableMessage(message));
                    }
                    else {
                        failure = appendFailure(failure, processFailure("MCP process emitted an invalid message without a JSON-RPC id", null));
                    }
                }
                else {
                    String key = responseKey(responseId);
                    if (completedResponseIds.contains(key)) {
                        failure = appendFailure(failure, processFailure("MCP process emitted duplicate JSON-RPC response id " + responseId, null));
                    }
                    else {
                        failure = appendFailure(failure, processFailure("MCP process emitted unmatched JSON-RPC response id " + responseId, null));
                    }
                }
            } catch (IOException exception) {
                failure = appendFailure(failure, processFailure("MCP process emitted non-JSON NDJSON output", exception));
            } catch (IllegalArgumentException exception) {
                failure = appendFailure(failure, processFailure(exception.getMessage(), exception));
            }
        }
        return failure;
    }

    private IOException failAndClose(String message, Exception cause) {
        IOException failure = processFailure(message, cause);
        try {
            close();
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        return failure;
    }

    private IOException processFailure(String message, Exception cause) {
        String diagnostic = stderr();
        String suffix = diagnostic.isBlank() ? "" : System.lineSeparator() + "MCP STDERR:" + System.lineSeparator() + diagnostic;
        return cause == null ? new IOException(message + suffix) : new IOException(message + suffix, cause);
    }

    private static IOException appendFailure(IOException existing, IOException next) {
        if (existing == null) {
            return next;
        }
        existing.addSuppressed(next);
        return existing;
    }

    private static String responseKey(Object id) {
        if (id instanceof Number number) {
            return "number:" + number;
        }
        if (id instanceof String text) {
            return "string:" + text;
        }
        throw new IllegalArgumentException("JSON-RPC id must be a string or number: " + id);
    }

    private void recordCompletedResponse(String responseId) throws IOException {
        if (!completedResponseIds.add(responseId)) {
            throw failAndClose("MCP process emitted duplicate JSON-RPC response id " + responseId, null);
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("MCP process client is closed");
        }
    }

    private sealed interface OutputEvent permits OutputLine, EndOfStream {
    }

    private record OutputLine(String line) implements OutputEvent {
    }

    private record EndOfStream(IOException failure) implements OutputEvent {
    }

    private static final class ProcessTreeTracker {
        private static final Duration POLL_INTERVAL = Duration.ofMillis(1);

        private final ProcessHandle root;
        private final Map<Long, ProcessHandle> observed = new ConcurrentHashMap<>();
        private final AtomicBoolean tracking = new AtomicBoolean(true);
        private final Thread monitor;

        private ProcessTreeTracker(ProcessHandle root) {
            this.root = root;
            observed.put(root.pid(), root);
            capture();
            monitor = Thread.ofVirtual().name("mcp-parity-process-tree").start(this::monitor);
        }

        private void monitor() {
            while (tracking.get()) {
                capture();
                try {
                    Thread.sleep(POLL_INTERVAL);
                } catch (InterruptedException exception) {
                    return;
                }
            }
        }

        private void capture() {
            int previousSize;
            do {
                previousSize = observed.size();
                List<ProcessHandle> known = List.copyOf(observed.values());
                for (ProcessHandle process : known) {
                    process.descendants().forEach(descendant -> observed.putIfAbsent(descendant.pid(), descendant));
                }
            } while (observed.size() != previousSize);
        }

        private TerminationResult terminateAndAwait(IOException failure) {
            boolean interrupted = Thread.interrupted();
            boolean rootTerminationRequested = false;
            long deadline = System.nanoTime() + SHUTDOWN_TIMEOUT.toNanos();
            try {
                while (true) {
                    capture();
                    List<ProcessHandle> alive = observed.values().stream().filter(ProcessHandle::isAlive).toList();
                    if (alive.isEmpty()) {
                        return new TerminationResult(failure, rootTerminationRequested);
                    }
                    alive.stream().filter(handle -> handle.pid() != root.pid()).forEach(ProcessHandle::destroyForcibly);
                    if (root.isAlive()) {
                        rootTerminationRequested |= root.destroyForcibly();
                    }
                    long remainingNanos = deadline - System.nanoTime();
                    if (remainingNanos <= 0) {
                        String pids = observed.values().stream().filter(ProcessHandle::isAlive).map(handle -> Long.toString(handle.pid())).sorted().reduce((left, right) -> left + ", " + right).orElse("unknown");
                        return new TerminationResult(appendFailure(failure, new IOException("MCP process tree remained alive after cleanup: " + pids)), rootTerminationRequested);
                    }
                    try {
                        TimeUnit.NANOSECONDS.sleep(Math.min(remainingNanos, POLL_INTERVAL.toNanos()));
                    } catch (InterruptedException exception) {
                        interrupted = true;
                        failure = appendFailure(failure, new IOException("Interrupted while awaiting MCP process-tree termination", exception));
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private IOException stop(IOException failure) {
            boolean interrupted = Thread.interrupted();
            tracking.set(false);
            monitor.interrupt();
            long deadline = System.nanoTime() + SHUTDOWN_TIMEOUT.toNanos();
            try {
                while (monitor.isAlive()) {
                    long remainingNanos = deadline - System.nanoTime();
                    if (remainingNanos <= 0) {
                        return appendFailure(failure, new IOException("MCP process-tree monitor did not stop"));
                    }
                    try {
                        monitor.join(Duration.ofNanos(remainingNanos));
                    } catch (InterruptedException exception) {
                        interrupted = true;
                        failure = appendFailure(failure, new IOException("Interrupted while joining MCP process-tree monitor", exception));
                    }
                }
                return failure;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private record TerminationResult(IOException failure, boolean rootTerminationRequested) {
        }
    }
}

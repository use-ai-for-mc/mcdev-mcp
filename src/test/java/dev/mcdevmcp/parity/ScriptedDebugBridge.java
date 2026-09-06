package dev.mcdevmcp.parity;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A deliberately small, deterministic WebSocket peer for process-level parity tests.
 */
final class ScriptedDebugBridge implements AutoCloseable {
    private static final int MAXIMUM_HTTP_HEADER_BYTES = 16 * 1024;
    private static final int MAXIMUM_FRAME_BYTES = 8 * 1024 * 1024;
    private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();
    private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() {
    };

    private final Path fixtureRoot;
    private final ServerSocket serverSocket;
    private final Runnable afterAccept;
    private final ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor();
    private final Object socketsLock = new Object();
    private final Set<Socket> sockets = new HashSet<>();
    private final List<Invocation> invocations = Collections.synchronizedList(new ArrayList<>());
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread acceptThread;

    private ScriptedDebugBridge(Path fixtureRoot, ServerSocket serverSocket, Runnable afterAccept) {
        this.fixtureRoot = fixtureRoot.toAbsolutePath().normalize();
        this.serverSocket = serverSocket;
        this.afterAccept = afterAccept;
        acceptThread = Thread.ofPlatform().daemon(true).name("scripted-debugbridge-accept").start(this::acceptLoop);
    }

    static ScriptedDebugBridge start(Path fixtureRoot) throws IOException {
        return start(fixtureRoot, () -> {
        });
    }

    static ScriptedDebugBridge start(Path fixtureRoot, Runnable afterAccept) throws IOException {
        Objects.requireNonNull(fixtureRoot, "fixtureRoot");
        Objects.requireNonNull(afterAccept, "afterAccept");
        return new ScriptedDebugBridge(fixtureRoot, new ServerSocket(0, 16, java.net.InetAddress.getLoopbackAddress()), afterAccept);
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    boolean isClosed() {
        return closed.get();
    }

    List<Invocation> invocations() {
        synchronized (invocations) {
            return List.copyOf(invocations);
        }
    }

    void assertHealthy() {
        Throwable problem = failure.get();
        if (problem != null) {
            throw new AssertionError("Scripted DebugBridge failed", problem);
        }
    }

    void shutdown() {
        close();
    }

    private void acceptLoop() {
        while (!closed.get()) {
            try {
                Socket socket = serverSocket.accept();
                try {
                    afterAccept.run();
                } catch (RuntimeException | Error exception) {
                    try {
                        socket.close();
                    } catch (IOException closeFailure) {
                        exception.addSuppressed(closeFailure);
                    }
                    throw exception;
                }
                if (!registerSocket(socket)) {
                    socket.close();
                    continue;
                }
                try {
                    clients.submit(() -> serve(socket));
                } catch (RejectedExecutionException exception) {
                    unregisterSocket(socket);
                    socket.close();
                    if (!closed.get()) {
                        recordFailure(exception);
                    }
                }
            } catch (SocketException exception) {
                if (!closed.get()) {
                    recordFailure(exception);
                }
                return;
            } catch (IOException | RuntimeException exception) {
                if (!closed.get()) {
                    recordFailure(exception);
                }
            }
        }
    }

    private boolean registerSocket(Socket socket) {
        synchronized (socketsLock) {
            if (closed.get()) {
                return false;
            }
            return sockets.add(socket);
        }
    }

    private void unregisterSocket(Socket socket) {
        synchronized (socketsLock) {
            sockets.remove(socket);
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            socket.setTcpNoDelay(true);
            acceptHandshake(socket.getInputStream(), socket.getOutputStream());
            readMessages(socket.getInputStream(), socket.getOutputStream());
        } catch (IOException | RuntimeException exception) {
            if (!closed.get()) {
                recordFailure(exception);
            }
        } finally {
            unregisterSocket(socket);
        }
    }

    private static void acceptHandshake(InputStream input, OutputStream output) throws IOException {
        String request = readHttpHeaders(input);
        String key = request.lines().map(String::strip).filter(line -> line.toLowerCase(Locale.ROOT).startsWith("sec-websocket-key:")).map(line -> line.substring(line.indexOf(':') + 1).strip()).findFirst().orElseThrow(() -> new IOException("WebSocket handshake omitted Sec-WebSocket-Key"));
        String accept = Base64.getEncoder().encodeToString(sha1(key + WEBSOCKET_GUID));
        String response = "HTTP/1.1 101 Switching Protocols\r\n" + "Upgrade: websocket\r\n" + "Connection: Upgrade\r\n" + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        output.write(response.getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private static String readHttpHeaders(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (bytes.size() < MAXIMUM_HTTP_HEADER_BYTES) {
            int value = input.read();
            if (value < 0) {
                throw new EOFException("WebSocket peer closed during handshake");
            }
            bytes.write(value);
            matched = switch (matched) {
                case 0 -> value == '\r' ? 1 : 0;
                case 1 -> value == '\n' ? 2 : value == '\r' ? 1 : 0;
                case 2 -> value == '\r' ? 3 : 0;
                case 3 -> value == '\n' ? 4 : value == '\r' ? 1 : 0;
                default -> matched;
            };
            if (matched == 4) {
                return bytes.toString(StandardCharsets.US_ASCII);
            }
        }
        throw new IOException("WebSocket handshake exceeded " + MAXIMUM_HTTP_HEADER_BYTES + " bytes");
    }

    private void readMessages(InputStream input, OutputStream output) throws IOException {
        var fragments = new ByteArrayOutputStream();
        boolean fragmentedText = false;
        while (!closed.get()) {
            Frame frame = readFrame(input);
            if (frame == null) {
                return;
            }
            switch (frame.opcode()) {
                case 0 -> {
                    if (!fragmentedText) {
                        throw new IOException("Unexpected WebSocket continuation frame");
                    }
                    appendFragment(fragments, frame.payload());
                    if (frame.finalFragment()) {
                        handleMessage(fragments.toString(StandardCharsets.UTF_8), output);
                        fragments.reset();
                        fragmentedText = false;
                    }
                }
                case 1 -> {
                    if (fragmentedText) {
                        throw new IOException("WebSocket text message overlapped an unfinished message");
                    }
                    appendFragment(fragments, frame.payload());
                    if (frame.finalFragment()) {
                        handleMessage(fragments.toString(StandardCharsets.UTF_8), output);
                        fragments.reset();
                    }
                    else {
                        fragmentedText = true;
                    }
                }
                case 8 -> {
                    writeFrame(output, 8, frame.payload());
                    return;
                }
                case 9 -> writeFrame(output, 10, frame.payload());
                case 10 -> {
                    // Pong is informational for this deterministic peer.
                }
                default -> throw new IOException("Unsupported WebSocket opcode " + frame.opcode());
            }
        }
    }

    private static void appendFragment(ByteArrayOutputStream target, byte[] payload) throws IOException {
        if (payload.length > MAXIMUM_FRAME_BYTES - target.size()) {
            throw new IOException("WebSocket message exceeded " + MAXIMUM_FRAME_BYTES + " bytes");
        }
        target.write(payload);
    }

    private void handleMessage(String message, OutputStream output) throws IOException {
        Map<String, Object> request = MAPPER.readValue(message, MAP_TYPE);
        Object rawId = request.get("id");
        Object rawType = request.get("type");
        if (!(rawId instanceof String id) || id.isBlank() || !(rawType instanceof String type) || type.isBlank()) {
            throw new IOException("Malformed DebugBridge request envelope");
        }
        Map<String, Object> payload = request.get("payload") == null ? Map.of() : MAPPER.convertValue(request.get("payload"), MAP_TYPE);
        invocations.add(new Invocation(type, payload));

        var response = new LinkedHashMap<String, Object>();
        response.put("id", id);
        switch (type) {
            case "status" -> {
                response.put("success", true);
                response.put("result", statusResult());
            }
            case "snapshot" -> {
                response.put("success", true);
                response.put("result", snapshotResult());
            }
            case "execute" -> {
                response.put("success", true);
                response.put("result", Map.of("value", "parity"));
                response.put("output", "bridge-output");
            }
            case "screenshot" -> success(response, screenshotResult());
            case "record_video" -> success(response, recordingResult());
            case "getItemTexture", "getEntityItemTexture", "getItemTextureById" -> success(response, textureResult());
            case "lookedAtEntity" -> {
                var result = new LinkedHashMap<String, Object>();
                result.put("entityId", payload.containsKey("range") ? 7 : null);
                success(response, result);
            }
            case "nearbyEntities", "entityDetails", "nearbyBlocks", "blockDetails", "chatHistory", "screenInspect" ->
                    success(response, containerResult(type, payload));
            case "setEntityGlow", "setBlockGlow", "clearBlockGlow", "runCommand", "joinServer", "disconnect", "quit" ->
                    success(response, acknowledgementResult(type));
            default -> {
                response.put("success", false);
                response.put("error", "Unsupported parity endpoint: " + type);
            }
        }
        writeFrame(output, 1, MAPPER.writeValueAsString(response).getBytes(StandardCharsets.UTF_8));
    }

    private static void success(Map<String, Object> response, Object result) {
        response.put("success", true);
        response.put("result", result);
    }

    private Map<String, Object> screenshotResult() {
        var result = new LinkedHashMap<String, Object>();
        result.put("path", fixtureRoot.resolve("captures/parity.jpg").toString());
        result.put("mimeType", "image/jpeg");
        result.put("width", 320);
        result.put("height", 180);
        result.put("sizeBytes", 4096);
        return result;
    }

    private Map<String, Object> recordingResult() {
        var result = new LinkedHashMap<String, Object>();
        result.put("mode", "grid");
        result.put("path", fixtureRoot.resolve("recordings/parity-grid.jpg").toString());
        result.put("mimeType", "image/jpeg");
        result.put("width", 640);
        result.put("height", 360);
        result.put("sizeBytes", 8192);
        result.put("frameCount", 4);
        result.put("frameWidth", 320);
        result.put("frameHeight", 180);
        result.put("gridCols", 2);
        result.put("gridRows", 2);
        result.put("captureMs", 150);
        result.put("intervalMs", 50);
        result.put("dropped", 0);
        return result;
    }

    private static Map<String, Object> textureResult() {
        var result = new LinkedHashMap<String, Object>();
        result.put("base64Png", "iVBORw0KGgo=");
        result.put("width", 16);
        result.put("height", 16);
        result.put("spriteName", "minecraft:item/parity");
        return result;
    }

    private static Map<String, Object> containerResult(String endpoint, Map<String, Object> payload) {
        var result = new LinkedHashMap<String, Object>();
        result.put("endpoint", endpoint);
        result.put("payload", payload);
        return result;
    }

    private static Map<String, Object> acknowledgementResult(String endpoint) {
        var result = new LinkedHashMap<String, Object>();
        result.put("accepted", true);
        result.put("endpoint", endpoint);
        return result;
    }

    private Map<String, Object> statusResult() {
        Path game = fixtureRoot.resolve("game");
        Path logs = fixtureRoot.resolve("logs");
        var result = new LinkedHashMap<String, Object>();
        result.put("version", "1.21.11");
        result.put("mappingStatus", "mojang");
        result.put("obfuscated", false);
        result.put("refs", 0);
        result.put("gameDir", game.toString());
        result.put("logsDir", logs.toString());
        result.put("latestLog", logs.resolve("latest.log").toString());
        result.put("latestLogExists", true);
        result.put("debugLog", logs.resolve("debug.log").toString());
        result.put("debugLogExists", false);
        result.put("sessionControlEnabled", true);
        return result;
    }

    private static Map<String, Object> snapshotResult() {
        var player = new LinkedHashMap<String, Object>();
        player.put("x", 1.25);
        player.put("y", 64);
        player.put("z", -2.5);
        player.put("health", 20);
        var result = new LinkedHashMap<String, Object>();
        result.put("inWorld", true);
        result.put("dimension", "minecraft:overworld");
        result.put("player", player);
        result.put("tick", 12345);
        result.put("tags", List.of("parity", "deterministic"));
        return result;
    }

    private static Frame readFrame(InputStream input) throws IOException {
        int first = input.read();
        if (first < 0) {
            return null;
        }
        int second = requireByte(input);
        boolean finalFragment = (first & 0x80) != 0;
        int opcode = first & 0x0f;
        boolean masked = (second & 0x80) != 0;
        if (!masked) {
            throw new IOException("Client WebSocket frame was not masked");
        }
        long length = second & 0x7f;
        if (length == 126) {
            length = ((long) requireByte(input) << 8) | requireByte(input);
        }
        else if (length == 127) {
            length = 0;
            for (int index = 0; index < Long.BYTES; index++) {
                length = (length << 8) | requireByte(input);
            }
        }
        if (length < 0 || length > MAXIMUM_FRAME_BYTES) {
            throw new IOException("WebSocket frame length is invalid: " + length);
        }
        byte[] mask = readExactly(input, 4);
        byte[] payload = readExactly(input, (int) length);
        for (int index = 0; index < payload.length; index++) {
            payload[index] ^= mask[index % mask.length];
        }
        return new Frame(finalFragment, opcode, payload);
    }

    private static int requireByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("WebSocket frame ended unexpectedly");
        }
        return value;
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("WebSocket frame ended unexpectedly");
        }
        return bytes;
    }

    private static void writeFrame(OutputStream output, int opcode, byte[] payload) throws IOException {
        output.write(0x80 | opcode);
        if (payload.length < 126) {
            output.write(payload.length);
        }
        else if (payload.length <= 0xffff) {
            output.write(126);
            output.write(payload.length >>> 8);
            output.write(payload.length);
        }
        else {
            output.write(127);
            long length = payload.length;
            for (int shift = 56; shift >= 0; shift -= 8) {
                output.write((int) (length >>> shift));
            }
        }
        output.write(payload);
        output.flush();
    }

    private static byte[] sha1(String value) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK does not provide SHA-1 for WebSocket handshakes", exception);
        }
    }

    private void recordFailure(Throwable problem) {
        failure.compareAndSet(null, problem);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Throwable closeFailure = null;
        try {
            serverSocket.close();
        } catch (IOException exception) {
            closeFailure = exception;
        }
        synchronized (socketsLock) {
            for (Socket socket : Set.copyOf(sockets)) {
                try {
                    socket.close();
                } catch (IOException exception) {
                    if (closeFailure == null) {
                        closeFailure = exception;
                    }
                    else {
                        closeFailure.addSuppressed(exception);
                    }
                }
            }
            sockets.clear();
        }
        clients.shutdownNow();
        try {
            acceptThread.join(Duration.ofSeconds(2));
            if (acceptThread.isAlive()) {
                closeFailure = addFailure(closeFailure, new IOException("Scripted DebugBridge accept loop did not stop"));
            }
            if (!clients.awaitTermination(2, TimeUnit.SECONDS)) {
                closeFailure = addFailure(closeFailure, new IOException("Scripted DebugBridge clients did not stop"));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            closeFailure = addFailure(closeFailure, exception);
        }
        if (closeFailure != null) {
            throw new IllegalStateException("Unable to close scripted DebugBridge", closeFailure);
        }
        assertHealthy();
    }

    private static Throwable addFailure(Throwable failure, Throwable additional) {
        if (failure == null) {
            return additional;
        }
        failure.addSuppressed(additional);
        return failure;
    }

    record Invocation(String type, Map<String, Object> payload) {
        Invocation {
            Objects.requireNonNull(type, "type");
            payload = Map.copyOf(Objects.requireNonNull(payload, "payload"));
        }
    }

    private record Frame(boolean finalFragment, int opcode, byte[] payload) {
    }
}

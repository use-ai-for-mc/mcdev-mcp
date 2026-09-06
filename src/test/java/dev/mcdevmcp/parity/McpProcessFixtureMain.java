package dev.mcdevmcp.parity;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Shell-free subprocess fixture for {@link McpProcessClientTest}.
 */
final class McpProcessFixtureMain {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();
    private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() {
    };
    private static final BufferedReader INPUT = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    private static final BufferedWriter OUTPUT = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));

    private McpProcessFixtureMain() {
    }

    static void main(String[] arguments) throws Exception {
        switch (arguments[0]) {
            case "echo" -> echo();
            case "mismatched" -> mismatched();
            case "notification" -> notification();
            case "extra-response" -> extraResponse();
            case "malformed-json" -> malformedJson();
            case "top-level-null" -> topLevelNull();
            case "invalid-message" -> invalidMessage();
            case "invalid-jsonrpc" -> invalidJsonRpc();
            case "server-request" -> serverRequest();
            case "response-both" -> responseWithResultAndError();
            case "response-neither" -> responseWithoutResultOrError();
            case "response-method" -> responseWithMethod();
            case "malformed-error" -> malformedError();
            case "null-id" -> nullId();
            case "duplicate-response" -> duplicateResponse();
            case "eof" -> readMessage();
            case "timeout" -> {
                readMessage();
                awaitParentEof();
            }
            case "close" -> awaitParentEof();
            case "forced-shutdown" -> forcedShutdown();
            case "spontaneous-nonzero" -> spontaneousNonzero();
            case "materializer-tree" -> materializerTree(Path.of(arguments[1]));
            case "materializer-tree-root-exits" -> materializerTreeRootExits(Path.of(arguments[1]));
            case "client-tree-root-exits" -> clientTreeRootExits(Path.of(arguments[1]));
            case "materializer-sleep" -> Thread.sleep(Duration.ofDays(1));
            default -> throw new IllegalArgumentException("Unsupported fixture mode: " + arguments[0]);
        }
    }

    private static void echo() throws IOException {
        Map<String, Object> request = readMessage();
        respond(request.get("id"), Map.of("method", request.get("method")));
        awaitParentEof();
    }

    private static void forcedShutdown() throws IOException, InterruptedException {
        Map<String, Object> request = readMessage();
        respond(request.get("id"), Map.of("ready", true));
        Thread.sleep(Duration.ofDays(1));
    }

    private static void spontaneousNonzero() throws IOException {
        Map<String, Object> request = readMessage();
        respond(request.get("id"), Map.of("ready", true));
        awaitParentEof();
        System.exit(23);
    }

    private static void mismatched() throws IOException {
        readMessage();
        respond("later", Map.of("order", "deferred"));
        awaitParentEof();
    }

    private static void notification() throws IOException {
        Map<String, Object> request = readMessage();
        writeMessage(Map.of("jsonrpc", "2.0", "method", "notifications/progress", "params", Map.of("progress", 1)));
        respond(request.get("id"), Map.of("notificationIgnored", true));
        awaitParentEof();
    }

    private static void extraResponse() throws IOException {
        Map<String, Object> request = readMessage();
        respond(request.get("id"), Map.of("expected", true));
        respond("unexpected", Map.of("expected", false));
        awaitParentEof();
    }

    @SuppressWarnings("resource")
    private static void materializerTree(Path childPidFile) throws IOException, InterruptedException {
        Process child = spawnSleeper();
        Files.writeString(childPidFile, Long.toString(child.pid()), StandardCharsets.UTF_8);
        Thread.sleep(Duration.ofDays(1));
    }

    @SuppressWarnings("resource")
    private static void materializerTreeRootExits(Path childPidFile) throws IOException, InterruptedException {
        Process child = spawnInheritedSleeper();
        Files.writeString(childPidFile, Long.toString(child.pid()), StandardCharsets.UTF_8);
        Thread.sleep(Duration.ofMillis(250));
    }

    private static void clientTreeRootExits(Path childPidFile) throws IOException, InterruptedException {
        readMessage();
        materializerTreeRootExits(childPidFile);
    }

    private static Process spawnSleeper() throws IOException {
        String java = ProcessHandle.current().info().command().orElseThrow();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), McpProcessFixtureMain.class.getName(), "materializer-sleep").start();
    }

    private static Process spawnInheritedSleeper() throws IOException {
        String java = ProcessHandle.current().info().command().orElseThrow();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), McpProcessFixtureMain.class.getName(), "materializer-sleep").redirectOutput(ProcessBuilder.Redirect.INHERIT).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    }

    private static void malformedJson() throws IOException {
        readMessage();
        System.err.print("fixture diagnostic");
        System.err.flush();
        OUTPUT.write("not-json");
        OUTPUT.newLine();
        OUTPUT.flush();
        awaitParentEof();
    }

    private static void topLevelNull() throws IOException {
        readMessage();
        OUTPUT.write("null");
        OUTPUT.newLine();
        OUTPUT.flush();
        awaitParentEof();
    }

    private static void invalidMessage() throws IOException {
        readMessage();
        writeMessage(Map.of("jsonrpc", "2.0", "result", Map.of()));
        awaitParentEof();
    }

    private static void invalidJsonRpc() throws IOException {
        Map<String, Object> request = readMessage();
        writeMessage(Map.of("jsonrpc", "1.0", "id", request.get("id"), "result", Map.of()));
        awaitParentEof();
    }

    private static void serverRequest() throws IOException {
        readMessage();
        writeMessage(Map.of("jsonrpc", "2.0", "id", "server", "method", "fixture/server", "params", Map.of()));
        awaitParentEof();
    }

    private static void responseWithResultAndError() throws IOException {
        Map<String, Object> request = readMessage();
        writeMessage(Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", Map.of(), "error", Map.of("code", -1, "message", "failure")));
        awaitParentEof();
    }

    private static void responseWithoutResultOrError() throws IOException {
        Map<String, Object> request = readMessage();
        writeMessage(Map.of("jsonrpc", "2.0", "id", request.get("id")));
        awaitParentEof();
    }

    private static void responseWithMethod() throws IOException {
        Map<String, Object> request = readMessage();
        writeMessage(Map.of("jsonrpc", "2.0", "id", request.get("id"), "method", "fixture/server", "result", Map.of()));
        awaitParentEof();
    }

    private static void malformedError() throws IOException {
        Map<String, Object> request = readMessage();
        writeMessage(Map.of("jsonrpc", "2.0", "id", request.get("id"), "error", Map.of("message", "missing code")));
        awaitParentEof();
    }

    private static void nullId() throws IOException {
        readMessage();
        var response = new LinkedHashMap<String, Object>();
        response.put("jsonrpc", "2.0");
        response.put("id", null);
        response.put("result", Map.of());
        writeMessage(response);
        awaitParentEof();
    }

    private static void duplicateResponse() throws IOException {
        Map<String, Object> request = readMessage();
        respond(request.get("id"), Map.of("first", true));
        respond(request.get("id"), Map.of("first", false));
        awaitParentEof();
    }

    private static Map<String, Object> readMessage() throws IOException {
        String line = INPUT.readLine();
        if (line == null) {
            throw new IOException("Parent closed STDIN before sending the expected message");
        }
        return MAPPER.readValue(line, MAP_TYPE);
    }

    private static void respond(Object id, Map<String, Object> result) throws IOException {
        var response = new LinkedHashMap<String, Object>();
        response.put("jsonrpc", "2.0");
        response.put("id", Objects.requireNonNull(id, "id"));
        response.put("result", result);
        writeMessage(response);
    }

    private static void writeMessage(Map<String, Object> message) throws IOException {
        OUTPUT.write(MAPPER.writeValueAsString(message));
        OUTPUT.newLine();
        OUTPUT.flush();
    }

    private static void awaitParentEof() throws IOException {
        INPUT.transferTo(Writer.nullWriter());
    }
}
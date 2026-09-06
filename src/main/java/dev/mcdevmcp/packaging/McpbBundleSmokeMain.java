package dev.mcdevmcp.packaging;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class McpbBundleSmokeMain {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();
    private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() {
    };

    private McpbBundleSmokeMain() {
    }

    @SuppressWarnings("all")
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Usage: McpbBundleSmokeMain <extracted-bundle-directory>");
        }
        Path bundle = Path.of(arguments[0]).toAbsolutePath();
        Map<String, Object> manifest = MAPPER.readValue(java.nio.file.Files.readString(bundle.resolve("manifest.json")), MAP_TYPE);
        Path logs = java.nio.file.Files.createDirectories(bundle.resolve("smoke-logs"));
        var processBuilder = new ProcessBuilder("node", "bootstrap.cjs").directory(bundle.toFile());
        processBuilder.environment().put("MCDEV_SESSION_LOG_DIR", logs.toString());
        processBuilder.environment().put("MCDEV_RUN_COMMAND", "true");
        Process process = processBuilder.start();
        try (var output = new BufferedReader(new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            try (var input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                write(input, request(1, "initialize", Map.of("protocolVersion", "2024-11-05", "capabilities", Map.of(), "clientInfo", Map.of("name", "mcpb-smoke", "version", "1.0"))));
                Map<String, Object> initialized = readWithinTimeout(output);
                Map<String, Object> result = map(initialized.get("result"));
                Map<String, Object> serverInfo = map(result.get("serverInfo"));
                requireEquals(manifest.get("name"), serverInfo.get("name"), "initialize server name");
                requireEquals(manifest.get("version"), serverInfo.get("version"), "initialize server version");
                write(input, Map.of("jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of()));
                write(input, request(2, "tools/list", Map.of()));
                List<Map<String, Object>> actualTools = maps(map(readWithinTimeout(output).get("result")).get("tools"));
                List<Map<String, Object>> expectedTools = maps(manifest.get("tools"));
                if (actualTools.isEmpty() || !actualTools.stream().map(tool -> tool.get("name")).sorted().toList().equals(expectedTools.stream().map(tool -> tool.get("name")).sorted().toList())) {
                    throw new IllegalStateException("MCPB tools/list catalog does not match the generated manifest");
                }
            }
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("MCPB launcher did not stop cleanly");
            }
        } finally {
            process.destroyForcibly();
            process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private static Map<String, Object> request(int id, String method, Map<String, Object> params) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }

    private static void write(OutputStreamWriter output, Map<String, Object> request) throws IOException {
        output.write(MAPPER.writeValueAsString(request) + System.lineSeparator());
        output.flush();
    }

    private static Map<String, Object> read(BufferedReader input) throws IOException {
        String line = input.readLine();
        if (line == null) {
            throw new IllegalStateException("MCPB launcher closed STDOUT before responding");
        }
        return MAPPER.readValue(line, MAP_TYPE);
    }

    private static Map<String, Object> readWithinTimeout(BufferedReader input) throws IOException {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            return executor.submit(() -> read(input)).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException exception) {
            throw new IOException("MCPB launcher timed out waiting for a response", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading MCPB launcher output", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Unable to read MCPB launcher output", cause);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("Expected JSON object");
        }
        return (Map<String, Object>) map;
    }

    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalStateException("Expected JSON array");
        }
        return list.stream().map(McpbBundleSmokeMain::map).toList();
    }

    private static void requireEquals(Object expected, Object actual, String label) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new IllegalStateException("Unexpected " + label + ": " + actual);
        }
    }
}
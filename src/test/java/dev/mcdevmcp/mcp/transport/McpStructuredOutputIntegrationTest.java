package dev.mcdevmcp.mcp.transport;

import dev.mcdevmcp.mcp.ServerDefinition;
import dev.mcdevmcp.mcp.resource.ResourceCatalog;
import dev.mcdevmcp.mcp.tool.CompleteToolBindings;
import dev.mcdevmcp.mcp.tool.ToolCatalog;
import dev.mcdevmcp.mcp.tool.ToolDeclarations;
import dev.mcdevmcp.mcp.tool.api.JsonValueSchema;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolHandlers;
import dev.mcdevmcp.mcp.tool.api.ToolInput;
import dev.mcdevmcp.mcp.tool.api.ToolOutput;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.support.AppEnvironment;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class McpStructuredOutputIntegrationTest {
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(2);
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();
    private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() {
    };
    private static final TypeRef<List<Map<String, Object>>> LIST_OF_MAPS_TYPE = new TypeRef<>() {
    };

    @Test
    void responseDeadlineClosesThePipeAndFailsWithAReadableDiagnostic() throws IOException {
        try (var writer = new PipedOutputStream(); var input = new PipedInputStream(writer)) {
            var cleanupCalled = new AtomicBoolean();
            AutoCloseable cleanup = () -> cleanupCalled.set(true);
            long started = System.nanoTime();

            try (var responses = new ResponseReader(input, input, writer)) {
                AssertionError failure = org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class, () -> responses.read("missing response", input, writer, cleanup));

                assertTrue(cleanupCalled.get());
                assertTrue(failure.getMessage().contains("missing response"));
                assertTrue(System.nanoTime() - started < RESPONSE_TIMEOUT.plusSeconds(2).toNanos());
            }
        }
    }

    @Test
    void sdkOwnsMissingStructuredContentValidationAtTheStdioBoundary() throws Exception {
        var output = ToolOutput.of(String.class, JsonValueSchema.of(Map.of("type", "string")));
        var definition = definition(output, ToolResult.text("plain result"));

        Map<String, Object> result = call(definition);

        assertTrue((Boolean) result.get("isError"));
        assertEquals("Response missing structured content which is expected when calling tool with non-empty outputSchema", firstText(result));
    }

    @Test
    void sdkOwnsStructuredOutputSchemaValidationAtTheStdioBoundary() throws Exception {
        var schema = JsonValueSchema.of(Map.of("type", "object", "properties", Map.of("expected", Map.of("type", "integer")), "required", List.of("expected")));
        var output = ToolOutput.of(String.class, schema);
        var definition = definition(output, ToolResult.structured("not an object", "invalid"));

        Map<String, Object> result = call(definition);

        assertTrue((Boolean) result.get("isError"));
        assertTrue(firstText(result).startsWith("Tool (mc_snapshot) output validation failed:"));
    }

    @Test
    void sdkBypassesOutputValidationForAnErrorResult() throws Exception {
        var output = ToolOutput.of(String.class, JsonValueSchema.of(Map.of("type", "object")));
        var definition = definition(output, ToolResult.error("expected tool failure"));

        Map<String, Object> result = call(definition);

        assertTrue((Boolean) result.get("isError"));
        assertEquals("expected tool failure", firstText(result));
    }

    private static <O> ServerDefinition definition(ToolOutput<O> output, ToolResult<O> result) {
        ToolInput<Object> input = declaredInput();
        ToolBinding<Object> binding = ToolBinding.output(input, output, (_, _) -> ToolHandlers.completed(result));
        ToolCatalog catalog = ToolCatalog.load(new AppEnvironment(Map.of()), CompleteToolBindings.including(MAPPER, Map.of("mc_snapshot", binding)), MAPPER);
        return new ServerDefinition("test", "1", "test", catalog, new ResourceCatalog());
    }

    @SuppressWarnings("unchecked")
    private static <A> ToolInput<A> declaredInput() {
        return (ToolInput<A>) ToolDeclarations.all().stream().filter(declaration -> declaration.name().equals("mc_snapshot")).findFirst().orElseThrow().input();
    }

    private static Map<String, Object> call(ServerDefinition definition) throws Exception {
        try (var clientToServer = new PipedOutputStream(); var serverInput = new PipedInputStream(clientToServer);
             var serverToClient = new PipedOutputStream(); var clientOutput = new PipedInputStream(serverToClient);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try (var server = McpSdkAdapter.startStdio(MAPPER, serverInput, serverToClient, definition, executor, () -> {
            }); var responses = new ResponseReader(clientOutput, clientOutput, serverToClient);
                 var writer = new OutputStreamWriter(clientToServer, StandardCharsets.UTF_8)) {
                write(writer, request(1, "initialize", Map.of("protocolVersion", "2024-11-05", "capabilities", Map.of(), "clientInfo", Map.of("name", "output-test", "version", "1"))));
                responses.read("initialize", clientOutput, serverToClient, clientToServer, serverInput, server);
                write(writer, Map.of("jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of()));
                write(writer, request(2, "tools/list", Map.of()));
                Map<String, Object> tools = responses.read("tools/list", clientOutput, serverToClient, clientToServer, serverInput, server);
                Map<String, Object> tool = MAPPER.convertValue(MAPPER.convertValue(tools.get("result"), MAP_TYPE).get("tools"), LIST_OF_MAPS_TYPE).stream().filter(candidate -> candidate.get("name").equals("mc_snapshot")).findFirst().orElseThrow();
                assertNotNull(tool.get("outputSchema"));
                write(writer, request(3, "tools/call", Map.of("name", "mc_snapshot", "arguments", Map.of())));
                return MAPPER.convertValue(responses.read("tools/call", clientOutput, serverToClient, clientToServer, serverInput, server).get("result"), MAP_TYPE);
            }
        }
    }

    private static String firstText(Map<String, Object> result) {
        List<Map<String, Object>> content = MAPPER.convertValue(result.get("content"), new TypeRef<>() {
        });
        return (String) content.getFirst().get("text");
    }

    private static AssertionError timeoutFailure(String phase, AutoCloseable... timeoutCleanup) {
        var failure = new AssertionError("Timed out waiting for " + phase + " response after " + RESPONSE_TIMEOUT);
        for (AutoCloseable resource : timeoutCleanup) {
            try {
                resource.close();
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
        return failure;
    }

    private static final class ResponseReader implements AutoCloseable {
        private static final Object END_OF_STREAM = new Object();

        private final BlockingQueue<Object> responses = new LinkedBlockingQueue<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AutoCloseable[] closeResources;
        private final Thread readerThread;

        private ResponseReader(PipedInputStream input, AutoCloseable... closeResources) {
            this.closeResources = closeResources.clone();
            readerThread = Thread.ofVirtual().name("mcp-output-reader").start(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        responses.put(line);
                    }
                } catch (Throwable exception) {
                    failure.set(exception);
                } finally {
                    if (!responses.offer(END_OF_STREAM)) {
                        failure.compareAndSet(null, new IllegalStateException("MCP output reader could not publish end-of-stream"));
                    }
                }
            });
        }

        private Map<String, Object> read(String phase, AutoCloseable... timeoutCleanup) throws Exception {
            Object response = responses.poll(RESPONSE_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            if (response == null) {
                AssertionError timeout = timeoutFailure(phase, timeoutCleanup);
                awaitStop(timeout);
                throw timeout;
            }
            if (response == END_OF_STREAM) {
                Throwable cause = failure.get();
                if (cause != null) {
                    throw new AssertionError("Response reader failed during " + phase, cause);
                }
                return fail("server closed STDOUT before responding during " + phase);
            }
            return MAPPER.readValue((String) response, MAP_TYPE);
        }

        private void awaitStop(AssertionError failure) {
            try {
                readerThread.join(RESPONSE_TIMEOUT.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure.addSuppressed(interrupted);
            }
            if (readerThread.isAlive()) {
                readerThread.interrupt();
                failure.addSuppressed(new IllegalStateException("MCP output reader did not stop after its pipe was closed"));
            }
        }

        @Override
        public void close() throws IOException {
            var closeFailure = new AssertionError("MCP output reader did not stop after close");
            for (AutoCloseable resource : closeResources) {
                try {
                    resource.close();
                } catch (Throwable resourceFailure) {
                    closeFailure.addSuppressed(resourceFailure);
                }
            }
            awaitStop(closeFailure);
            if (closeFailure.getSuppressed().length > 0) {
                throw new IOException(closeFailure.getMessage(), closeFailure);
            }
        }
    }

    private static void write(OutputStreamWriter writer, Map<String, Object> message) throws Exception {
        writer.write(MAPPER.writeValueAsString(message));
        writer.write(System.lineSeparator());
        writer.flush();
    }

    private static Map<String, Object> request(int id, String method, Map<String, Object> params) {
        var request = new LinkedHashMap<String, Object>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", params);
        return request;
    }
}

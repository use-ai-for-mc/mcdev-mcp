package dev.mcdevmcp.mcp.transport;

import dev.mcdevmcp.mcp.resource.ResourceCatalog;
import dev.mcdevmcp.mcp.tool.*;
import dev.mcdevmcp.mcp.tool.api.*;
import dev.mcdevmcp.support.AppEnvironment;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class McpSdkAdapterTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();

    private static ToolInput<?> declaredInput() {
        return ToolDeclarations.all().stream().filter(declaration -> declaration.name().equals("mc_snapshot")).findFirst().orElseThrow().input();
    }

    @Test
    void resourceReadRunsOnTheSuppliedVirtualExecutorInsteadOfTheSubscribingThread() throws Exception {
        var executingThread = new AtomicReference<Thread>();
        ThreadFactory recordingFactory = runnable -> Thread.ofVirtual().name("mcp-resource-", 0).unstarted(() -> {
            executingThread.set(Thread.currentThread());
            runnable.run();
        });

        try (var executor = Executors.newThreadPerTaskExecutor(recordingFactory)) {
            var adapter = new McpSdkAdapter(MAPPER, executor);
            McpServerFeatures.AsyncResourceSpecification resource = adapter.resources(new ResourceCatalog()).getFirst();
            var subscribingThread = Thread.currentThread();
            var result = resource.readHandler().apply(null, McpSchema.ReadResourceRequest.builder("mcdev://guides/python-scripting").build()).toFuture().get(5, TimeUnit.SECONDS);

            assertEquals("mcdev://guides/python-scripting", result.contents().getFirst().uri());
            assertTrue(executingThread.get().isVirtual());
            assertNotEquals(subscribingThread, executingThread.get());
        }
    }

    @Test
    void sdkArgumentsReachTheTypedBindingAsAnEmptyRecord() throws Exception {
        var received = new CompletableFuture<TestEmptyArguments>();
        var input = ToolInput.of(TestEmptyArguments.class, RecordInputSchemaFactory.standard());
        var definition = new ToolDefinition("mc_snapshot", "typed empty arguments", ToolBinding.content(input, (arguments, _) -> {
            received.complete(arguments);
            return ToolHandlers.completed(ToolResult.text("legacy packages"));
        }), ToolAvailability.ALWAYS);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = new McpSdkAdapter(MAPPER, executor).callHandler(definition).apply(null, McpSchema.CallToolRequest.builder("mc_snapshot").build()).toFuture().get(5, TimeUnit.SECONDS);

            assertEquals(new TestEmptyArguments(), received.get(5, TimeUnit.SECONDS));
            assertNull(result.isError());
            assertEquals("legacy packages", assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text());
        }
    }

    @Test
    void imageContentMapsToTheSdkProtocolTypeWithoutDecodingBase64() throws Exception {
        var input = ToolInput.of(TestEmptyArguments.class, RecordInputSchemaFactory.standard());
        var imageContent = McpSchema.ImageContent.builder("iVBORw0KGgo=", "image/png").build();
        var definition = new ToolDefinition("image", "image", ToolBinding.content(input, (_, _) -> ToolHandlers.completed(ToolResult.content(List.of(imageContent), false))), ToolAvailability.ALWAYS);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = new McpSdkAdapter(MAPPER, executor).callHandler(definition).apply(null, McpSchema.CallToolRequest.builder("image").arguments(Map.of()).build()).toFuture().get(5, TimeUnit.SECONDS);

            var image = assertInstanceOf(McpSchema.ImageContent.class, result.content().getFirst());
            assertSame(imageContent, image);
            assertEquals("iVBORw0KGgo=", image.data());
            assertEquals("image/png", image.mimeType());
            assertNull(result.isError());
        }
    }

    @Test
    void everySdkContentSubtypePassesThroughByIdentityAndUsesTheProtocolDiscriminator() throws Exception {
        var text = McpSchema.TextContent.builder("hello").build();
        var image = McpSchema.ImageContent.builder("aW1hZ2U=", "image/png").build();
        var audio = McpSchema.AudioContent.builder("YXVkaW8=", "audio/wav").build();
        var embeddedText = McpSchema.EmbeddedResource.builder(McpSchema.TextResourceContents.builder("mcdev://text", "embedded text").mimeType("text/plain").build()).build();
        var embeddedBlob = McpSchema.EmbeddedResource.builder(McpSchema.BlobResourceContents.builder("mcdev://blob", "YmxvYg==").mimeType("application/octet-stream").build()).build();
        var link = McpSchema.ResourceLink.builder().name("manual").title("Manual").uri("mcdev://manual").description("A manual").mimeType("text/markdown").size(12L).build();
        List<McpSchema.Content> supplied = List.of(text, image, audio, embeddedText, embeddedBlob, link);
        var input = ToolInput.of(TestEmptyArguments.class, RecordInputSchemaFactory.standard());
        var definition = new ToolDefinition("all-content", "all-content", ToolBinding.content(input, (_, _) -> ToolHandlers.completed(ToolResult.content(supplied, false))), ToolAvailability.ALWAYS);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = new McpSdkAdapter(MAPPER, executor).callHandler(definition).apply(null, McpSchema.CallToolRequest.builder("all-content").arguments(Map.of()).build()).toFuture().get(5, TimeUnit.SECONDS);

            assertEquals(supplied.size(), result.content().size());
            for (int index = 0; index < supplied.size(); index++) {
                assertSame(supplied.get(index), result.content().get(index), "content identity " + index);
            }

            List<Map<String, Object>> wireContent = MAPPER.convertValue(MAPPER.readValue(MAPPER.writeValueAsString(result), new TypeRef<Map<String, Object>>() {
            }).get("content"), new TypeRef<>() {
            });
            assertEquals(List.of("text", "image", "audio", "resource", "resource", "resource_link"), wireContent.stream().map(content -> content.get("type")).toList());
            assertEquals(Map.of("type", "text", "text", "hello"), wireContent.get(0));
            assertEquals(Map.of("type", "image", "data", "aW1hZ2U=", "mimeType", "image/png"), wireContent.get(1));
            assertEquals(Map.of("type", "audio", "data", "YXVkaW8=", "mimeType", "audio/wav"), wireContent.get(2));
            assertEquals(Map.of("type", "resource", "resource", Map.of("uri", "mcdev://text", "mimeType", "text/plain", "text", "embedded text")), wireContent.get(3));
            assertEquals(Map.of("type", "resource", "resource", Map.of("uri", "mcdev://blob", "mimeType", "application/octet-stream", "blob", "YmxvYg==")), wireContent.get(4));
            assertEquals(Map.of("type", "resource_link", "name", "manual", "title", "Manual", "uri", "mcdev://manual", "description", "A manual", "mimeType", "text/markdown", "size", 12), wireContent.get(5));
        }
    }

    @Test
    void typedJavaResultBecomesStructuredContentOnlyAtTheSdkBoundary() throws Exception {
        var value = new TestStructuredResult("minecraft:diamond", 3);
        var input = ToolInput.of(TestEmptyArguments.class, RecordInputSchemaFactory.standard());
        var output = ToolOutput.of(TestStructuredResult.class, JsonValueSchema.of(Map.of("type", "object", "properties", Map.of("itemId", Map.of("type", "string"), "count", Map.of("type", "integer")))));
        var definition = new ToolDefinition("inventory", "inventory", ToolBinding.output(input, output, (_, _) -> {
            StructuredToolResult<TestStructuredResult> result = ToolResult.structured(value, "minecraft:diamond x3");
            return ToolHandlers.completed(result);
        }), ToolAvailability.ALWAYS);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = new McpSdkAdapter(MAPPER, executor).callHandler(definition).apply(null, McpSchema.CallToolRequest.builder("inventory").arguments(Map.of()).build()).toFuture().get(5, TimeUnit.SECONDS);

            assertSame(value, result.structuredContent());
            assertEquals("minecraft:diamond x3", assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text());
            assertNull(result.isError());
        }
    }

    @Test
    void advertisesOutputSchemaBeforeInvocation() {
        Map<String, Object> schema = Map.of("type", "string");
        var output = ToolOutput.of(String.class, JsonValueSchema.of(schema));
        var called = new AtomicReference<Boolean>();
        var binding = ToolBinding.output(declaredInput(), output, (_, _) -> {
            called.set(true);
            return ToolHandlers.completed(ToolResult.text("ok"));
        });
        var catalog = ToolCatalog.load(new AppEnvironment(Map.of()), CompleteToolBindings.including(MAPPER, Map.of("mc_snapshot", binding)), MAPPER);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tool = new McpSdkAdapter(MAPPER, executor).tools(catalog).stream().filter(specification -> specification.tool().name().equals("mc_snapshot")).findFirst().orElseThrow().tool();
            assertEquals(schema, tool.outputSchema());
        }
        assertNull(called.get());
    }

    @Test
    void rejectsStructuredResultWithoutDeclaredOutputAsAToolError() {
        var value = new TestStructuredResult("item", 1);
        var input = ToolInput.of(TestEmptyArguments.class, RecordInputSchemaFactory.standard());
        var binding = ToolBinding.content(input, (_, _) -> ToolHandlers.completed(ToolResult.text("unused")));
        var definition = new ToolDefinition("undeclared", "undeclared", binding, ToolAvailability.ALWAYS);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var adapter = new McpSdkAdapter(MAPPER, executor);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> adapter.toSdkResult(definition, binding, ToolResult.structured(value, "item")));
            assertTrue(failure.getMessage().contains("no declared output"));
        }
    }

    @Test
    void preservesStructuredValueIdentityWithoutResultTypeMetadata() throws Exception {
        var output = ToolOutput.of(new TypeRef<List<Integer>>() {
        }, JsonValueSchema.of(Map.of("type", "array", "items", Map.of("type", "string"))));
        var input = ToolInput.of(TestEmptyArguments.class, RecordInputSchemaFactory.standard());
        var value = List.of(1);
        var definition = new ToolDefinition("mismatch", "mismatch", ToolBinding.output(input, output, (_, _) -> ToolHandlers.completed(ToolResult.structured(value, "mismatch"))), ToolAvailability.ALWAYS);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = new McpSdkAdapter(MAPPER, executor).callHandler(definition).apply(null, McpSchema.CallToolRequest.builder("mismatch").arguments(Map.of()).build()).toFuture().get(5, TimeUnit.SECONDS);

            assertSame(value, result.structuredContent());
            assertNull(result.isError());
        }
    }

    @Test
    void errorMarkedStructuredResultsRetainStructuredValueIdentity() throws Exception {
        var output = ToolOutput.of(TestOtherStructuredResult.class, JsonValueSchema.of(Map.of("type", "object")));
        var input = ToolInput.of(TestEmptyArguments.class, RecordInputSchemaFactory.standard());
        var value = new TestOtherStructuredResult("other");
        var definition = new ToolDefinition("error-mismatch", "error-mismatch", ToolBinding.output(input, output, (_, _) -> ToolHandlers.completed(new StructuredToolResult<>(List.of(McpSchema.TextContent.builder("error").build()), value, true))), ToolAvailability.ALWAYS);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = new McpSdkAdapter(MAPPER, executor).callHandler(definition).apply(null, McpSchema.CallToolRequest.builder("error-mismatch").arguments(Map.of()).build()).toFuture().get(5, TimeUnit.SECONDS);

            assertTrue(result.isError());
            assertSame(value, result.structuredContent());
        }
    }

    @Test
    void acceptsStructurallyEqualParameterizedTypesWithoutConvertingTheValue() throws Exception {
        TypeRef<List<String>> declared = new TypeRef<>() {
        };
        var output = ToolOutput.of(declared, JsonValueSchema.of(Map.of("type", "array", "items", Map.of("type", "string"))));
        var input = ToolInput.of(TestEmptyArguments.class, RecordInputSchemaFactory.standard());
        var value = List.of("one", "two");
        var mapper = new CountingMcpJsonMapper(MAPPER);
        var definition = new ToolDefinition("equal", "equal", ToolBinding.output(input, output, (_, _) -> ToolHandlers.completed(ToolResult.structured(value, "equal"))), ToolAvailability.ALWAYS);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = new McpSdkAdapter(mapper, executor).callHandler(definition).apply(null, McpSchema.CallToolRequest.builder("equal").arguments(Map.of()).build()).toFuture().get(5, TimeUnit.SECONDS);

            assertSame(value, result.structuredContent());
            assertEquals(1, mapper.convertValueCalls(), "only the input decode should use mapper conversion");
            assertNull(result.isError());
        }
    }

    private record TestStructuredResult(String itemId, int count) {
    }

    private record TestOtherStructuredResult(String itemId) {
    }
}

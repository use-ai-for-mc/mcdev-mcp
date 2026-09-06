package dev.mcdevmcp.mcp.transport;

import dev.mcdevmcp.mcp.ServerDefinition;
import dev.mcdevmcp.mcp.resource.ResourceCatalog;
import dev.mcdevmcp.mcp.resource.ResourceRead;
import dev.mcdevmcp.mcp.tool.ToolCatalog;
import dev.mcdevmcp.mcp.tool.ToolDefinition;
import dev.mcdevmcp.mcp.tool.api.StructuredToolResult;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The only production boundary that exposes Reactor types.
 */
public final class McpSdkAdapter {
    private static final Duration STREAMABLE_CLOSE_TIMEOUT = Duration.ofSeconds(10);

    private final McpJsonMapper mapper;
    private final ExecutorService blockingExecutor;

    McpSdkAdapter(McpJsonMapper mapper, ExecutorService blockingExecutor) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.blockingExecutor = Objects.requireNonNull(blockingExecutor, "blockingExecutor");
    }

    static McpJsonMapper nodeParityMapper(McpJsonMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return new NodeParityJsonMapper(mapper);
    }

    static StdioServerTransportProvider stdioTransport(McpJsonMapper mapper, InputStream input, OutputStream output, CountDownLatch inputClosed) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(inputClosed, "inputClosed");
        return new StdioServerTransportProvider(mapper, new EofTrackingInputStream(input, inputClosed), new NonClosingOutputStream(output));
    }

    public static StdioServer startStdio(McpJsonMapper mapper, InputStream input, OutputStream output, ServerDefinition definition, ExecutorService blockingExecutor, AutoCloseable ownedRuntime) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(blockingExecutor, "blockingExecutor");
        Objects.requireNonNull(ownedRuntime, "ownedRuntime");

        var inputClosed = new CountDownLatch(1);
        McpJsonMapper transportMapper = nodeParityMapper(mapper);
        var transport = stdioTransport(transportMapper, input, output, inputClosed);
        try {
            var adapter = new McpSdkAdapter(mapper, blockingExecutor);
            McpAsyncServer server = McpServer.async(transport).jsonMapper(transportMapper).serverInfo(definition.name(), definition.version()).instructions(definition.instructions()).capabilities(McpSchema.ServerCapabilities.builder().resources(null, null).tools(null).build()).validateToolInputs(true).tools(adapter.tools(definition.tools())).resources(adapter.resources(definition.resources())).build();
            return new StdioServer(server, blockingExecutor, inputClosed, ownedRuntime);
        } catch (RuntimeException | Error exception) {
            try {
                closeWithinTimeout("STDIO MCP transport", () -> transport.closeGracefully().block());
            } catch (Throwable closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    @SuppressWarnings("unused")
    public static StreamableServer startStreamable(McpJsonMapper transportMapper, McpStreamableServerTransportProvider transport, ServerDefinition definition, ExecutorService blockingExecutor) {
        return startStreamable(transportMapper, transport, definition, blockingExecutor, AsyncServerExtensions.production());
    }

    public static StreamableServer startStreamable(McpJsonMapper transportMapper, McpStreamableServerTransportProvider transport, ServerDefinition definition, ExecutorService blockingExecutor, AsyncServerExtensions extensions) {
        Objects.requireNonNull(transportMapper, "transportMapper");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(blockingExecutor, "blockingExecutor");
        Objects.requireNonNull(extensions, "extensions");

        try {
            var adapter = new McpSdkAdapter(transportMapper, blockingExecutor);
            var tools = merge(adapter.tools(definition.tools()), extensions.tools());
            var resources = merge(adapter.resources(definition.resources()), extensions.resources());
            requireUnique(tools, specification -> specification.tool().name(), "tool name");
            requireUnique(resources, specification -> specification.resource().uri(), "resource URI");
            requireUnique(extensions.resourceTemplates(), specification -> specification.resourceTemplate().uriTemplate(), "resource template URI");
            requireUnique(extensions.prompts(), specification -> specification.prompt().name(), "prompt name");
            requireUnique(extensions.completions(), McpServerFeatures.AsyncCompletionSpecification::referenceKey, "completion reference");
            McpAsyncServer server = McpServer.async(transport).jsonMapper(transportMapper).serverInfo(definition.name(), definition.version()).instructions(definition.instructions()).capabilities(withProductionCatalogs(extensions.capabilities())).validateToolInputs(true).tools(tools).resources(resources).resourceTemplates(extensions.resourceTemplates()).prompts(extensions.prompts()).completions(extensions.completions()).requestTimeout(extensions.requestTimeout()).build();
            return new StreamableServer(server, transport);
        } catch (RuntimeException | Error exception) {
            closeAfterFailure(transport, exception);
            throw exception;
        }
    }

    private static McpSchema.ServerCapabilities withProductionCatalogs(McpSchema.ServerCapabilities requested) {
        var resources = requested.resources();
        var tools = requested.tools();
        return requested.mutate().resources(resources == null ? null : resources.subscribe(), resources == null ? null : resources.listChanged()).tools(tools == null ? null : tools.listChanged()).build();
    }

    private static <T> List<T> merge(List<T> first, List<T> second) {
        return Stream.concat(first.stream(), second.stream()).toList();
    }

    private static <T, K> void requireUnique(List<T> specifications, Function<T, K> key, String description) {
        var values = new HashSet<K>();
        for (T specification : specifications) {
            K value = key.apply(specification);
            if (!values.add(value)) {
                throw new IllegalArgumentException("Duplicate MCP " + description + ": " + value);
            }
        }
    }

    private static void closeAfterFailure(McpStreamableServerTransportProvider transport, Throwable failure) {
        try {
            closeWithinTimeout("streamable MCP transport", () -> transport.closeGracefully().block());
        } catch (Throwable closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static void closeWithinTimeout(String description, Runnable closeAction) {
        var closeTask = new FutureTask<Void>(() -> {
            closeAction.run();
            return null;
        });
        Thread closeThread = Thread.ofVirtual().name("mcp-transport-close").start(closeTask);
        try {
            closeTask.get(STREAMABLE_CLOSE_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            closeTask.cancel(true);
            closeThread.interrupt();
            throw new IllegalStateException(description + " did not close within " + STREAMABLE_CLOSE_TIMEOUT, exception);
        } catch (InterruptedException exception) {
            closeTask.cancel(true);
            closeThread.interrupt();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while closing " + description, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Error error) {
                throw error;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Unable to close " + description, cause);
        }
    }

    List<McpServerFeatures.AsyncToolSpecification> tools(ToolCatalog catalog) {
        return catalog.enabledDefinitions().stream().map(definition -> {
            var binding = Objects.requireNonNull(catalog.binding(definition.name()), "Tool binding: " + definition.name());
            return McpServerFeatures.AsyncToolSpecification.builder().tool(toSdkTool(definition, binding)).callHandler(callHandler(definition, binding)).build();
        }).toList();
    }

    List<McpServerFeatures.AsyncResourceSpecification> resources(ResourceCatalog catalog) {
        return catalog.definitions().stream().map(definition -> new McpServerFeatures.AsyncResourceSpecification(McpSchema.Resource.builder(definition.uri().toString(), definition.name()).title(definition.title()).description(definition.description()).mimeType(definition.mimeType()).build(), (_, request) -> readResource(catalog, URI.create(request.uri())))).toList();
    }

    BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler(ToolDefinition definition) {
        ToolBinding<?> binding = Objects.requireNonNull(definition.binding(), "Tool definition has no direct handler: " + definition.name());
        return callHandler(definition, binding);
    }

    private BiFunction<McpAsyncServerExchange, McpSchema.CallToolRequest, Mono<McpSchema.CallToolResult>> callHandler(ToolDefinition definition, ToolBinding<?> binding) {
        return (_, request) -> invoke(definition, binding, request);
    }

    private Mono<McpSchema.ReadResourceResult> readResource(ResourceCatalog catalog, URI uri) {
        return Mono.defer(() -> {
            var result = new CompletableFuture<McpSchema.ReadResourceResult>();
            Future<?> task;
            try {
                task = blockingExecutor.submit(() -> {
                    try {
                        ResourceRead read = catalog.read(uri);
                        var contents = McpSchema.TextResourceContents.builder(read.uri().toString(), read.text()).mimeType(read.mimeType()).build();
                        result.complete(McpSchema.ReadResourceResult.builder(List.of(contents)).build());
                    } catch (Throwable exception) {
                        result.completeExceptionally(exception);
                        if (exception instanceof Error error) {
                            throw error;
                        }
                    }
                });
            } catch (RuntimeException exception) {
                return Mono.error(exception);
            }
            result.whenComplete((_, _) -> {
                if (result.isCancelled()) {
                    task.cancel(true);
                }
            });
            return Mono.fromFuture(result);
        });
    }

    private Mono<McpSchema.CallToolResult> invoke(ToolDefinition definition, ToolBinding<?> binding, McpSchema.CallToolRequest request) {
        return Mono.defer(() -> {
            var cancelled = new AtomicBoolean();
            CompletionStage<? extends ToolResult<?>> stage;
            try {
                Map<String, Object> arguments = request.arguments();
                stage = Objects.requireNonNull(binding.invoke(mapper, arguments == null ? Map.of() : arguments, cancelled::get), "Tool handler returned null: " + definition.name());
            } catch (RuntimeException exception) {
                return Mono.just(error(definition.name(), exception));
            }

            CompletableFuture<? extends ToolResult<?>> future;
            try {
                future = stage.toCompletableFuture();
            } catch (RuntimeException exception) {
                return Mono.just(error(definition.name(), exception));
            }

            return Mono.fromFuture(future).map(result -> toSdkResult(definition, binding, result)).onErrorResume(exception -> Mono.just(error(definition.name(), exception))).doOnCancel(() -> {
                cancelled.set(true);
                future.cancel(true);
            });
        });
    }

    private McpSchema.Tool toSdkTool(ToolDefinition definition, ToolBinding<?> binding) {
        var builder = McpSchema.Tool.builder(definition.name(), definition.inputSchema()).description(definition.description());
        binding.output().ifPresent(output -> builder.outputSchema(output.schema().value()));
        return builder.build();
    }

    private McpSchema.CallToolResult toSdkResult(ToolResult<?> result) {
        return sdkResult(result, null);
    }

    McpSchema.CallToolResult toSdkResult(ToolDefinition definition, ToolBinding<?> binding, ToolResult<?> result) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(result, "result");
        if (result instanceof StructuredToolResult<?>) {
            binding.output().orElseThrow(() -> new IllegalArgumentException("Structured result has no declared output for tool: " + definition.name()));
        }
        return sdkResult(result, result instanceof StructuredToolResult<?> structured ? structured.structuredContent() : null);
    }

    private McpSchema.CallToolResult sdkResult(ToolResult<?> result, Object structuredContent) {
        Boolean isError = result.isError() ? Boolean.TRUE : null;
        return new McpSchema.CallToolResult(result.content(), isError, structuredContent, null);
    }

    private McpSchema.CallToolResult error(String name, Throwable exception) {
        return toSdkResult(ToolResult.error(ToolCatalog.errorText(name, exception)));
    }

    public record AsyncServerExtensions(List<McpServerFeatures.AsyncToolSpecification> tools, List<McpServerFeatures.AsyncResourceSpecification> resources, List<McpServerFeatures.AsyncResourceTemplateSpecification> resourceTemplates, List<McpServerFeatures.AsyncPromptSpecification> prompts, List<McpServerFeatures.AsyncCompletionSpecification> completions, McpSchema.ServerCapabilities capabilities, Duration requestTimeout) {
        public AsyncServerExtensions {
            tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
            resources = List.copyOf(Objects.requireNonNull(resources, "resources"));
            resourceTemplates = List.copyOf(Objects.requireNonNull(resourceTemplates, "resourceTemplates"));
            prompts = List.copyOf(Objects.requireNonNull(prompts, "prompts"));
            completions = List.copyOf(Objects.requireNonNull(completions, "completions"));
            Objects.requireNonNull(capabilities, "capabilities");
            Objects.requireNonNull(requestTimeout, "requestTimeout");
            if (requestTimeout.isNegative() || requestTimeout.isZero()) {
                throw new IllegalArgumentException("requestTimeout must be positive");
            }
        }

        public static AsyncServerExtensions production() {
            return new AsyncServerExtensions(List.of(), List.of(), List.of(), List.of(), List.of(), McpSchema.ServerCapabilities.builder().resources(null, null).tools(null).build(), Duration.ofHours(10));
        }
    }

    public static final class StreamableServer implements AutoCloseable {
        private final McpAsyncServer server;
        private final McpStreamableServerTransportProvider transport;
        private final AtomicBoolean closed = new AtomicBoolean();

        private StreamableServer(McpAsyncServer server, McpStreamableServerTransportProvider transport) {
            this.server = Objects.requireNonNull(server, "server");
            this.transport = Objects.requireNonNull(transport, "transport");
        }

        McpAsyncServer server() {
            return server;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }

            Throwable failure = null;
            try {
                closeWithinTimeout("streamable MCP server", () -> server.closeGracefully().block());
            } catch (Throwable exception) {
                failure = exception;
            }
            if (failure != null) {
                try {
                    closeWithinTimeout("streamable MCP transport", () -> transport.closeGracefully().block());
                } catch (Throwable exception) {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw new IllegalStateException("Unable to close streamable MCP server", failure);
            }
        }
    }
}

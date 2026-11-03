package dev.mcdevmcp.mcp.transport;

import dev.mcdevmcp.mcp.ServerDefinition;
import dev.mcdevmcp.mcp.resource.ResourceCatalog;
import dev.mcdevmcp.mcp.resource.ResourceRead;
import dev.mcdevmcp.mcp.tool.ToolCatalog;
import dev.mcdevmcp.mcp.tool.ToolDefinition;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.support.ScopeOutcomes;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

/**
 * The only production boundary that exposes Reactor types.
 */
public final class McpSdkAdapter {
    private static final Duration TRANSPORT_CLOSE_TIMEOUT = Duration.ofSeconds(10);

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
                closeWithinTimeout(() -> transport.closeGracefully().block());
            } catch (Throwable closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    @SuppressWarnings("preview")
    private static void closeWithinTimeout(Runnable closeAction) {
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.awaitAllSuccessfulOrThrow(), config -> config.withName("mcp-transport-close").withTimeout(TRANSPORT_CLOSE_TIMEOUT))) {
            scope.fork(() -> {
                closeAction.run();
                return null;
            });
            scope.join();
        } catch (ExecutionException exception) {
            if (ScopeOutcomes.timedOut(exception)) {
                throw new IllegalStateException("STDIO MCP transport did not close within " + TRANSPORT_CLOSE_TIMEOUT, exception);
            }
            Throwable cause = ScopeOutcomes.cause(exception);
            switch (cause) {
                case Error error -> throw error;
                case RuntimeException runtimeException -> throw runtimeException;
                default -> throw new IllegalStateException("Unable to close STDIO MCP transport", cause);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while closing STDIO MCP transport", exception);
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

    @SuppressWarnings("preview")
    private Mono<McpSchema.ReadResourceResult> readResource(ResourceCatalog catalog, URI uri) {
        return Mono.defer(() -> {
            var result = new CompletableFuture<McpSchema.ReadResourceResult>();
            Future<?> task;
            try {
                task = blockingExecutor.submit(() -> {
                    try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<McpSchema.ReadResourceResult>anySuccessfulOrThrow(), config -> config.withName("mcp-resource-read"))) {
                        scope.fork(() -> {
                            ResourceRead read = catalog.read(uri);
                            var contents = McpSchema.TextResourceContents.builder(read.uri().toString(), read.text()).mimeType(read.mimeType()).build();
                            return McpSchema.ReadResourceResult.builder(List.of(contents)).build();
                        });
                        result.complete(scope.join());
                    } catch (ExecutionException exception) {
                        Throwable cause = ScopeOutcomes.cause(exception);
                        result.completeExceptionally(cause);
                        if (cause instanceof Error error) {
                            throw error;
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        result.cancel(false);
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
        Optional<?> structured = result.structuredValue();
        if (structured.isPresent()) {
            binding.output().orElseThrow(() -> new IllegalArgumentException("Structured result has no declared output for tool: " + definition.name()));
        }
        return sdkResult(result, structured.orElse(null));
    }

    private McpSchema.CallToolResult sdkResult(ToolResult<?> result, Object structuredContent) {
        Boolean isError = result.isError() ? Boolean.TRUE : null;
        return new McpSchema.CallToolResult(result.content(), isError, structuredContent, null);
    }

    private McpSchema.CallToolResult error(String name, Throwable exception) {
        return toSdkResult(ToolResult.error(ToolCatalog.errorText(name, exception)));
    }
}

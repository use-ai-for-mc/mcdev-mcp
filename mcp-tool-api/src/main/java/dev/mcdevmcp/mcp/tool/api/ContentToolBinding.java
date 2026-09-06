package dev.mcdevmcp.mcp.tool.api;

import io.modelcontextprotocol.json.McpJsonMapper;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;

public final class ContentToolBinding<A> implements ToolBinding<A> {
    private final ToolInput<A> input;
    private final ToolHandler<A> handler;
    private final BlockingToolHandler<A> blockingHandler;

    public ContentToolBinding(ToolInput<A> input, ToolHandler<A> handler) {
        this(Objects.requireNonNull(input, "input"), Objects.requireNonNull(handler, "handler"), null);
    }

    private ContentToolBinding(ToolInput<A> input, ToolHandler<A> handler, BlockingToolHandler<A> blockingHandler) {
        this.input = Objects.requireNonNull(input, "input");
        this.handler = handler;
        this.blockingHandler = blockingHandler;
        if ((handler == null) == (blockingHandler == null)) {
            throw new IllegalArgumentException("A content tool binding must have exactly one handler");
        }
    }

    static <A> ContentToolBinding<A> blocking(ToolInput<A> input, BlockingToolHandler<A> handler) {
        return new ContentToolBinding<>(Objects.requireNonNull(input, "input"), null, Objects.requireNonNull(handler, "handler"));
    }

    @Override
    public ToolInput<A> input() {
        return input;
    }

    @Override
    public Optional<ToolOutput<?>> output() {
        return Optional.empty();
    }

    @Override
    public CompletionStage<? extends ToolResult<?>> invoke(McpJsonMapper mapper, Map<String, Object> arguments, ToolCancellation cancellation) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(cancellation, "cancellation");
        return invokeDecoded(input.decode(mapper, arguments), cancellation);
    }

    /**
     * Invokes the handler with an already decoded value for a lazy runtime
     * binding; normal callers should use {@link #invoke}.
     */
    public CompletionStage<? extends ContentToolResult<Void>> invokeDecoded(A decoded, ToolCancellation cancellation) {
        Objects.requireNonNull(decoded, "decoded");
        Objects.requireNonNull(cancellation, "cancellation");
        if (handler == null) {
            throw new IllegalStateException("Blocking content binding has not been assigned an executor");
        }
        return Objects.requireNonNull(handler.handle(decoded, cancellation), "Tool handler returned null");
    }

    @Override
    public ContentToolBinding<A> withBlockingExecutor(ExecutorService executor) {
        Objects.requireNonNull(executor, "executor");
        if (blockingHandler == null) {
            return this;
        }
        return new ContentToolBinding<>(input, ToolHandlers.blocking(executor, blockingHandler), null);
    }
}

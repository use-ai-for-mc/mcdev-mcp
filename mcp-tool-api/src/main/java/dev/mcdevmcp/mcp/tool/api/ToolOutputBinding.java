package dev.mcdevmcp.mcp.tool.api;

import io.modelcontextprotocol.json.McpJsonMapper;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;

public final class ToolOutputBinding<A, O> implements ToolBinding<A> {
    private final ToolInput<A> input;
    private final ToolOutput<O> output;
    private final ToolOutputHandler<A, O> handler;
    private final BlockingToolOutputHandler<A, O> blockingHandler;

    public ToolOutputBinding(ToolInput<A> input, ToolOutput<O> output, ToolOutputHandler<A, O> handler) {
        this(input, output, Objects.requireNonNull(handler, "handler"), null);
    }

    public static <A, O> ToolOutputBinding<A, O> blocking(ToolInput<A> input, ToolOutput<O> output, BlockingToolOutputHandler<A, O> handler) {
        return new ToolOutputBinding<>(input, output, null, Objects.requireNonNull(handler, "handler"));
    }

    private ToolOutputBinding(ToolInput<A> input, ToolOutput<O> output, ToolOutputHandler<A, O> handler, BlockingToolOutputHandler<A, O> blockingHandler) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.handler = handler;
        this.blockingHandler = blockingHandler;
        if ((handler == null) == (blockingHandler == null)) {
            throw new IllegalArgumentException("A structured tool binding must have exactly one handler");
        }
    }

    public ToolOutput<O> declaredOutput() {
        return output;
    }

    @Override
    public ToolInput<A> input() {
        return input;
    }

    @Override
    public Optional<ToolOutput<?>> output() {
        return Optional.of(output);
    }

    @Override
    public CompletionStage<? extends ToolResult<O>> invoke(McpJsonMapper mapper, Map<String, Object> arguments, ToolCancellation cancellation) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(cancellation, "cancellation");
        A decoded = Objects.requireNonNull(input.decode(mapper, arguments), "decoded tool input");
        if (handler == null) {
            throw new IllegalStateException("Blocking output binding has not been assigned an executor");
        }
        return Objects.requireNonNull(handler.handle(decoded, cancellation), "Tool output handler returned null");
    }

    @Override
    public ToolOutputBinding<A, O> withBlockingExecutor(ExecutorService executor) {
        Objects.requireNonNull(executor, "executor");
        if (blockingHandler == null) {
            return this;
        }
        return new ToolOutputBinding<>(input, output, ToolHandlers.blocking(executor, blockingHandler), null);
    }
}

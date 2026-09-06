package dev.mcdevmcp.mcp.tool.api;

import io.modelcontextprotocol.json.McpJsonMapper;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;

public sealed interface ToolBinding<A> permits ContentToolBinding, ToolOutputBinding {
    ToolInput<A> input();

    Optional<ToolOutput<?>> output();

    CompletionStage<? extends ToolResult<?>> invoke(McpJsonMapper mapper, Map<String, Object> arguments, ToolCancellation cancellation);

    ToolBinding<A> withBlockingExecutor(ExecutorService executor);

    static <A> ContentToolBinding<A> content(ToolInput<A> input, ToolHandler<A> handler) {
        return new ContentToolBinding<>(input, handler);
    }

    static <A> ContentToolBinding<A> blocking(ToolInput<A> input, BlockingToolHandler<A> handler) {
        return ContentToolBinding.blocking(input, handler);
    }

    static <A, O> ToolOutputBinding<A, O> output(ToolInput<A> input, ToolOutput<O> output, ToolOutputHandler<A, O> handler) {
        return new ToolOutputBinding<>(input, output, handler);
    }

    static <A, O> ToolOutputBinding<A, O> blocking(ToolInput<A> input, ToolOutput<O> output, BlockingToolOutputHandler<A, O> handler) {
        return ToolOutputBinding.blocking(input, output, handler);
    }
}

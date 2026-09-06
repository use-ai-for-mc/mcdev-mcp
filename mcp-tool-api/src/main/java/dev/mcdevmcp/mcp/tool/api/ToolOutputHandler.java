package dev.mcdevmcp.mcp.tool.api;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ToolOutputHandler<A, O> {
    CompletionStage<? extends ToolResult<O>> handle(A arguments, ToolCancellation cancellation);
}

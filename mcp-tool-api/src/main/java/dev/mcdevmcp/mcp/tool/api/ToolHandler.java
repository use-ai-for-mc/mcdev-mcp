package dev.mcdevmcp.mcp.tool.api;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface ToolHandler<A> {
    CompletionStage<? extends ContentToolResult<Void>> handle(A arguments, ToolCancellation cancellation);
}

package dev.mcdevmcp.mcp.tool.api;

@FunctionalInterface
public interface BlockingToolHandler<A> {
    ContentToolResult<Void> handle(A arguments, ToolCancellation cancellation) throws Exception;
}
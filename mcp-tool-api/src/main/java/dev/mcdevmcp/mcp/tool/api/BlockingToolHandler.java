package dev.mcdevmcp.mcp.tool.api;

@FunctionalInterface
public interface BlockingToolHandler<A> {
    @SuppressWarnings("unused")
    ContentToolResult<Void> handle(A arguments, ToolCancellation cancellation) throws Exception;
}

package dev.mcdevmcp.mcp.tool.api;

@FunctionalInterface
public interface BlockingToolOutputHandler<A, O> {
    ToolResult<O> handle(A arguments, ToolCancellation cancellation) throws Exception;
}

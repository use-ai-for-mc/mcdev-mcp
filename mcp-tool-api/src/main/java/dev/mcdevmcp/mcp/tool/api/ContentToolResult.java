package dev.mcdevmcp.mcp.tool.api;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Objects;

public value record ContentToolResult<O>(List<McpSchema.Content> content, boolean isError) implements ToolResult<O> {
    public ContentToolResult {
        content = List.copyOf(Objects.requireNonNull(content, "content"));
    }
}

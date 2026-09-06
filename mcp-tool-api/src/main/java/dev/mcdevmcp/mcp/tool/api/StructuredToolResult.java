package dev.mcdevmcp.mcp.tool.api;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Objects;

public record StructuredToolResult<T>(List<McpSchema.Content> content, T structuredContent, boolean isError) implements ToolResult<T> {
    public StructuredToolResult {
        content = List.copyOf(Objects.requireNonNull(content, "content"));
        Objects.requireNonNull(structuredContent, "structuredContent");
    }
}

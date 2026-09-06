package dev.mcdevmcp.mcp.tool.api;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Objects;

// The result slot is phantom for content-only results, preserving the output
// type at output-binding handler boundaries without exposing it on the wire.
@SuppressWarnings("unused")
public sealed interface ToolResult<O> permits ContentToolResult, StructuredToolResult {
    static <O> ContentToolResult<O> content(List<? extends McpSchema.Content> content, boolean isError) {
        Objects.requireNonNull(content, "content");
        return new ContentToolResult<>(content.stream().map(value -> Objects.requireNonNull(value, "content element")).map(McpSchema.Content.class::cast).toList(), isError);
    }

    static <O> ContentToolResult<O> text(String text) {
        return content(List.of(McpSchema.TextContent.builder(text).build()), false);
    }

    static <O> ContentToolResult<O> error(String text) {
        return content(List.of(McpSchema.TextContent.builder(text).build()), true);
    }

    static <O> StructuredToolResult<O> structured(O value, String fallbackText) {
        return new StructuredToolResult<>(List.of(McpSchema.TextContent.builder(fallbackText).build()), value, false);
    }

    List<McpSchema.Content> content();

    boolean isError();
}

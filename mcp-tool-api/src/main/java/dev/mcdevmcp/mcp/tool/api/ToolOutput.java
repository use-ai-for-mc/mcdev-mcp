package dev.mcdevmcp.mcp.tool.api;

import io.modelcontextprotocol.json.TypeRef;

import java.util.Objects;

/**
 * The trusted Java type and JSON Schema advertised for a tool's structured output.
 */
public value record ToolOutput<T>(JsonType<T> type, JsonValueSchema schema) {
    public ToolOutput {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(schema, "schema");
    }

    public static <T> ToolOutput<T> of(Class<T> type, JsonValueSchema schema) {
        return new ToolOutput<>(JsonType.of(Objects.requireNonNull(type, "type")), schema);
    }

    public static <T> ToolOutput<T> of(TypeRef<T> type, JsonValueSchema schema) {
        return new ToolOutput<>(JsonType.of(Objects.requireNonNull(type, "type")), schema);
    }
}

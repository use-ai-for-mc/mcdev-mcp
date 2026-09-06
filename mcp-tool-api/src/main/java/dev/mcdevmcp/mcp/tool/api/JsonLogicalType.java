package dev.mcdevmcp.mcp.tool.api;

import io.modelcontextprotocol.json.TypeRef;

import java.util.Objects;
import java.util.Optional;

/**
 * Metadata associating one exact Java JSON target with directional schemas.
 */
public final class JsonLogicalType<T> {
    private final String id;
    private final JsonType<T> targetType;
    private final JsonValueSchema inputSchema;
    private final JsonValueSchema outputSchema;

    private JsonLogicalType(String id, JsonType<T> targetType, JsonValueSchema inputSchema, JsonValueSchema outputSchema) {
        this.id = requireId(id);
        this.targetType = Objects.requireNonNull(targetType, "targetType");
        if (inputSchema == null && outputSchema == null) {
            throw new IllegalArgumentException("Logical JSON type must declare an input or output schema");
        }
        this.inputSchema = inputSchema;
        this.outputSchema = outputSchema;
    }

    public static <T> JsonLogicalType<T> of(String id, Class<T> type, JsonValueSchema inputSchema, JsonValueSchema outputSchema) {
        return create(id, JsonType.of(Objects.requireNonNull(type, "type")), Objects.requireNonNull(inputSchema, "inputSchema"), Objects.requireNonNull(outputSchema, "outputSchema"));
    }

    public static <T> JsonLogicalType<T> of(String id, TypeRef<T> type, JsonValueSchema inputSchema, JsonValueSchema outputSchema) {
        return create(id, JsonType.of(Objects.requireNonNull(type, "type")), Objects.requireNonNull(inputSchema, "inputSchema"), Objects.requireNonNull(outputSchema, "outputSchema"));
    }

    public static <T> JsonLogicalType<T> inputOnly(String id, Class<T> type, JsonValueSchema inputSchema) {
        return create(id, JsonType.of(Objects.requireNonNull(type, "type")), Objects.requireNonNull(inputSchema, "inputSchema"), null);
    }

    public static <T> JsonLogicalType<T> inputOnly(String id, TypeRef<T> type, JsonValueSchema inputSchema) {
        return create(id, JsonType.of(Objects.requireNonNull(type, "type")), Objects.requireNonNull(inputSchema, "inputSchema"), null);
    }

    public static <T> JsonLogicalType<T> outputOnly(String id, Class<T> type, JsonValueSchema outputSchema) {
        return create(id, JsonType.of(Objects.requireNonNull(type, "type")), null, Objects.requireNonNull(outputSchema, "outputSchema"));
    }

    public static <T> JsonLogicalType<T> outputOnly(String id, TypeRef<T> type, JsonValueSchema outputSchema) {
        return create(id, JsonType.of(Objects.requireNonNull(type, "type")), null, Objects.requireNonNull(outputSchema, "outputSchema"));
    }

    public static <T> JsonLogicalType<T> bidirectional(String id, Class<T> type, JsonValueSchema schema) {
        return of(id, type, schema, schema);
    }

    public static <T> JsonLogicalType<T> bidirectional(String id, TypeRef<T> type, JsonValueSchema schema) {
        return of(id, type, schema, schema);
    }

    public String id() {
        return id;
    }

    public JsonType<T> targetType() {
        return targetType;
    }

    public Optional<JsonValueSchema> inputSchema() {
        return Optional.ofNullable(inputSchema);
    }

    public Optional<JsonValueSchema> outputSchema() {
        return Optional.ofNullable(outputSchema);
    }

    private static <T> JsonLogicalType<T> create(String id, JsonType<T> targetType, JsonValueSchema inputSchema, JsonValueSchema outputSchema) {
        return new JsonLogicalType<>(id, targetType, inputSchema, outputSchema);
    }

    private static String requireId(String id) {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Logical JSON type ID must not be blank");
        }
        return id;
    }
}
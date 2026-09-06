package dev.mcdevmcp.mcp.tool.api;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;

import java.lang.reflect.Type;
import java.util.Objects;

public final class JsonType<T> {
    private final Class<T> rawType;
    private final TypeRef<T> typeRef;
    private final Type javaType;

    private JsonType(Class<T> rawType, TypeRef<T> typeRef, Type javaType) {
        this.rawType = rawType;
        this.typeRef = typeRef;
        this.javaType = Objects.requireNonNull(javaType, "javaType");
    }

    public static <T> JsonType<T> of(Class<T> type) {
        Class<T> required = Objects.requireNonNull(type, "type");
        return new JsonType<>(required, null, required);
    }

    public static <T> JsonType<T> of(TypeRef<T> type) {
        TypeRef<T> required = Objects.requireNonNull(type, "type");
        return new JsonType<>(null, required, required.getType());
    }

    public Type javaType() {
        return javaType;
    }

    public Class<?> rawClass() {
        return javaType instanceof Class<?> type ? type : null;
    }

    public T decode(McpJsonMapper mapper, Object value) {
        McpJsonMapper required = Objects.requireNonNull(mapper, "mapper");
        return rawType == null ? required.convertValue(value, typeRef) : required.convertValue(value, rawType);
    }

    @Override
    public String toString() {
        return javaType.getTypeName();
    }
}
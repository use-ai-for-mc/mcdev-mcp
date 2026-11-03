package dev.mcdevmcp.mcp.tool.api;

import java.util.Map;
import java.util.Objects;

/**
 * An immutable supported JSON Schema fragment for one JSON value.
 */
public value record JsonValueSchema(Map<String, Object> value) {
    public JsonValueSchema {
        value = JsonSchemaSupport.immutableObject(value);
        JsonSchemaSupport.validateSupportedSchema(value, "value");
    }

    public static JsonValueSchema of(Map<String, Object> value) {
        return new JsonValueSchema(value);
    }

    public boolean semanticallyEquals(Map<String, Object> other) {
        Objects.requireNonNull(other, "other");
        return JsonSchemaSupport.jsonEquals(value, JsonSchemaSupport.immutableObject(other));
    }
}
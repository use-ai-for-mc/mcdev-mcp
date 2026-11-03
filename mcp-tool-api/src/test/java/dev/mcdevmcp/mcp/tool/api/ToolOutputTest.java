package dev.mcdevmcp.mcp.tool.api;

import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolOutputTest {
    @Test
    void canonicalFieldsAndFactoriesRetainTrustedMetadata() {
        var schema = JsonValueSchema.of(Map.of("type", "string"));
        var type = JsonType.of(String.class);
        var canonical = new ToolOutput<>(type, schema);

        assertSame(type, canonical.type());
        assertSame(schema, canonical.schema());
        assertEquals(String.class, ToolOutput.of(String.class, schema).type().javaType());
        assertEquals(new TypeRef<List<String>>() {
        }.getType(), ToolOutput.of(new TypeRef<List<String>>() {
        }, JsonValueSchema.of(Map.of("type", "array", "items", Map.of("type", "string")))).type().javaType());
    }

    @Test
    void rejectsNullFields() {
        var schema = JsonValueSchema.of(Map.of("type", "string"));
        assertThrows(NullPointerException.class, () -> new ToolOutput<String>(null, schema));
        assertThrows(NullPointerException.class, () -> new ToolOutput<>(JsonType.of(String.class), null));
        assertThrows(NullPointerException.class, () -> ToolOutput.of((Class<String>) null, schema));
        assertThrows(NullPointerException.class, () -> ToolOutput.of((TypeRef<String>) null, schema));
    }

    @Test
    void deeplyFreezesTheAdvertisedSchema() {
        var nestedProperties = new LinkedHashMap<String, Object>();
        nestedProperties.put("value", new LinkedHashMap<>(Map.of("type", "string")));
        var values = new ArrayList<>(List.of("nested"));
        var source = new LinkedHashMap<String, Object>();
        source.put("type", "object");
        source.put("properties", Map.of("nested", Map.of("type", "object", "properties", nestedProperties), "items", Map.of("type", "array", "items", Map.of("type", "string"))));
        source.put("required", values);

        var output = ToolOutput.of(String[].class, JsonValueSchema.of(source));
        nestedProperties.put("changed", Map.of("type", "boolean"));
        values.add("changed");

        Map<?, ?> properties = (Map<?, ?>) output.schema().value().get("properties");
        Map<?, ?> nested = (Map<?, ?>) properties.get("nested");
        Map<?, ?> nestedValues = (Map<?, ?>) nested.get("properties");
        assertFalse(properties.containsKey("changed"));
        assertFalse(nestedValues.containsKey("changed"));
        assertEquals(1, ((List<?>) output.schema().value().get("required")).size());
        assertThrows(UnsupportedOperationException.class, () -> output.schema().value().put("new", "value"));
        assertThrows(UnsupportedOperationException.class, properties::clear);
    }
}
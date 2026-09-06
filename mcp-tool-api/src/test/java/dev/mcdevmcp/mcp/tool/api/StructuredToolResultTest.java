package dev.mcdevmcp.mcp.tool.api;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StructuredToolResultTest {
    @Test
    void preservesTheTypedJavaValueUntilTheJsonBoundary() throws IOException {
        var value = new InventorySummary(2, List.of(new InventoryItem("minecraft:diamond", 3)));

        StructuredToolResult<InventorySummary> result = ToolResult.structured(value, "2 inventory slots");

        assertSame(value, result.structuredContent());
        assertEquals("2 inventory slots", ((McpSchema.TextContent) result.content().getFirst()).text());
        String json = McpJsonDefaults.getMapper().writeValueAsString(result.structuredContent());
        assertEquals(Map.of("slots", 2, "items", List.of(Map.of("id", "minecraft:diamond", "count", 3))), McpJsonDefaults.getMapper().readValue(json, new TypeRef<Map<String, Object>>() {
        }));
    }

    @Test
    void defensivelyCopiesContentAndRejectsNullContentOrStructuredValues() {
        McpSchema.ImageContent image = McpSchema.ImageContent.builder("aW1hZ2U=", "image/png").build();
        List<McpSchema.Content> source = new ArrayList<>(List.of(image));
        InventorySummary value = new InventorySummary(0, List.of());

        StructuredToolResult<InventorySummary> result = new StructuredToolResult<>(source, value, false);
        source.clear();

        assertEquals(List.of(image), result.content());
        assertSame(image, result.content().getFirst());
        assertThrows(UnsupportedOperationException.class, () -> result.content().clear());
        assertThrows(NullPointerException.class, () -> new StructuredToolResult<>(null, value, false));
        assertThrows(NullPointerException.class, () -> new StructuredToolResult<>(java.util.Arrays.asList(image, null), value, false));
        assertThrows(NullPointerException.class, () -> new StructuredToolResult<>(List.of(image), null, false));
    }
}

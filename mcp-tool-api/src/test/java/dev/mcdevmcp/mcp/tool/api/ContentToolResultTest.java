package dev.mcdevmcp.mcp.tool.api;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentToolResultTest {
    @Test
    void textAndErrorFactoriesProduceSdkTextContentAndFlags() {
        ToolResult<Void> text = ToolResult.text("hello");
        ToolResult<Void> error = ToolResult.error("failed");

        assertEquals("hello", assertInstanceOf(McpSchema.TextContent.class, text.content().getFirst()).text());
        assertEquals("failed", assertInstanceOf(McpSchema.TextContent.class, error.content().getFirst()).text());
        assertFalse(text.isError());
        assertTrue(error.isError());
    }

    @Test
    void acceptsAConcreteSdkContentSubtypeListAndPreservesElementIdentity() {
        McpSchema.ImageContent image = McpSchema.ImageContent.builder("aW1hZ2U=", "image/png").build();
        List<McpSchema.ImageContent> images = List.of(image);

        ToolResult<Void> result = ToolResult.content(images, false);

        assertSame(image, result.content().getFirst());
        assertEquals(List.of(image), result.content());
    }

    @Test
    void defensivelyCopiesContentAndRejectsNullListsOrElements() {
        McpSchema.TextContent text = McpSchema.TextContent.builder("hello").build();
        List<McpSchema.Content> source = new ArrayList<>(List.of(text));
        ContentToolResult<Void> result = new ContentToolResult<>(source, false);
        source.clear();

        assertEquals(List.of(text), result.content());
        assertSame(text, result.content().getFirst());
        assertThrows(UnsupportedOperationException.class, () -> result.content().clear());
        assertThrows(NullPointerException.class, () -> ToolResult.content(null, false));
        assertThrows(NullPointerException.class, () -> new ContentToolResult<Void>(null, false));
        assertThrows(NullPointerException.class, () -> ToolResult.content(java.util.Arrays.asList(text, null), false));
        assertThrows(NullPointerException.class, () -> new ContentToolResult<Void>(java.util.Arrays.asList(text, null), false));
    }
}

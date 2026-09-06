package dev.mcdevmcp.packaging;

import dev.mcdevmcp.bridge.payload.EmptyBridgePayload;
import dev.mcdevmcp.mcp.McpServerFactory;
import dev.mcdevmcp.mcp.tool.ToolAvailability;
import dev.mcdevmcp.mcp.tool.ToolDefinition;
import dev.mcdevmcp.mcp.tool.api.JsonValueSchema;
import dev.mcdevmcp.mcp.tool.api.RecordInputSchemaFactory;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolInput;
import dev.mcdevmcp.mcp.tool.api.ToolOutput;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.support.AppEnvironment;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeArtifactSmokeCatalogTest {
    private static final TypeRef<List<Map<String, Object>>> TOOLS_TYPE = new TypeRef<>() {
    };

    @Test
    void acceptsTheCompleteSdkWireCatalogWithNormalizedNumbers() throws Exception {
        List<ToolDefinition> definitions = definitions();
        List<Map<String, Object>> tools = sdkWireCatalog(definitions);
        assertEquals(33, tools.size());
        assertTrue(definitions.stream().anyMatch(definition -> !definition.inputSchema().equals(tools.stream().filter(tool -> definition.name().equals(tool.get("name"))).findFirst().orElseThrow().get("inputSchema"))), "The catalog must exercise Java versus parsed JSON numeric representations");

        assertDoesNotThrow(() -> RuntimeArtifactSmokeMain.verifyToolCatalog(tools, definitions));
    }

    @Test
    void acceptsDeclaredOutputSchemasAlongsideContentOnlyTools() throws Exception {
        List<ToolDefinition> definitions = definitionsWithOutput();
        List<Map<String, Object>> tools = sdkWireCatalog(definitions);
        assertTrue(tools.stream().anyMatch(tool -> tool.containsKey("outputSchema")));
        assertTrue(tools.stream().anyMatch(tool -> !tool.containsKey("outputSchema")));
        assertDoesNotThrow(() -> RuntimeArtifactSmokeMain.verifyToolCatalog(tools, definitions));
    }

    @Test
    void rejectsEveryOmittedDeclaredOutputSchema() throws Exception {
        List<ToolDefinition> definitions = definitionsWithOutput();
        for (int index = 0; index < definitions.size(); index++) {
            if (definitions.get(index).output().isPresent()) {
                List<Map<String, Object>> tools = sdkWireCatalog(definitions);
                tools.get(index).remove("outputSchema");
                assertRejected(tools, definitions);
            }
        }
    }

    @Test
    void rejectsAnAlteredOutputSchema() throws Exception {
        List<ToolDefinition> definitions = definitionsWithOutput();
        List<Map<String, Object>> tools = sdkWireCatalog(definitions);
        tools.stream().filter(tool -> tool.containsKey("outputSchema")).findFirst().orElseThrow().put("outputSchema", Map.of("type", "object", "properties", Map.of()));
        assertRejected(tools, definitions);
    }

    @Test
    void rejectsAnUndeclaredOutputSchema() throws Exception {
        List<ToolDefinition> definitions = definitions();
        List<Map<String, Object>> tools = sdkWireCatalog(definitions);
        tools.stream().filter(tool -> !tool.containsKey("outputSchema")).findFirst().orElseThrow().put("outputSchema", Map.of("type", "object"));
        assertRejected(tools, definitions);
    }

    @Test
    void rejectsReorderingMissingAndExtraTools() throws Exception {
        List<ToolDefinition> definitions = definitions();
        List<Map<String, Object>> reordered = sdkWireCatalog(definitions);
        Collections.swap(reordered, 0, 1);
        assertRejected(reordered, definitions);
        List<Map<String, Object>> missing = sdkWireCatalog(definitions);
        missing.removeFirst();
        assertRejected(missing, definitions);
        List<Map<String, Object>> extra = sdkWireCatalog(definitions);
        extra.add(extra.getFirst());
        assertRejected(extra, definitions);
    }

    @Test
    void rejectsAlteredMetadataAndInputSchema() throws Exception {
        List<ToolDefinition> definitions = definitions();
        for (String field : List.of("name", "description", "inputSchema")) {
            List<Map<String, Object>> tools = sdkWireCatalog(definitions);
            tools.getFirst().put(field, "changed");
            assertRejected(tools, definitions);
        }
    }

    private static void assertRejected(List<Map<String, Object>> tools, List<ToolDefinition> definitions) {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> RuntimeArtifactSmokeMain.verifyToolCatalog(tools, definitions));
        assertEquals("Exact JAR tools/list differs from Java-owned tools metadata", exception.getMessage());
    }

    private static List<ToolDefinition> definitions() {
        try (var composition = McpServerFactory.declarativeComposition(new AppEnvironment(Map.of()), McpJsonDefaults.getMapper())) {
            return composition.definitions();
        }
    }

    private static List<ToolDefinition> definitionsWithOutput() {
        var input = ToolInput.of(EmptyBridgePayload.class, RecordInputSchemaFactory.standard());
        var output = ToolOutput.of(EmptyBridgePayload.class, JsonValueSchema.of(Map.of("type", "object", "additionalProperties", false)));
        var binding = ToolBinding.blocking(input, output, (_, _) -> ToolResult.text("unused"));
        List<ToolDefinition> definitions = new ArrayList<>(definitions());
        definitions.add(new ToolDefinition("output_fixture", "Declared output fixture", binding, ToolAvailability.ALWAYS));
        return definitions;
    }

    private static List<Map<String, Object>> sdkWireCatalog(List<ToolDefinition> definitions) throws IOException {
        List<McpSchema.Tool> tools = definitions.stream().map(definition -> {
            var builder = McpSchema.Tool.builder(definition.name(), definition.inputSchema()).description(definition.description());
            definition.output().ifPresent(output -> builder.outputSchema(output.schema().value()));
            return builder.build();
        }).toList();
        var mapper = McpJsonDefaults.getMapper();
        return mapper.readValue(mapper.writeValueAsString(tools), TOOLS_TYPE);
    }
}
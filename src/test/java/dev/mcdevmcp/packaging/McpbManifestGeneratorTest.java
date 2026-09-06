package dev.mcdevmcp.packaging;

import dev.mcdevmcp.mcp.McpServerFactory;
import dev.mcdevmcp.mcp.McpContractTestSupport;
import dev.mcdevmcp.mcp.tool.ToolDefinition;
import dev.mcdevmcp.support.AppVersion;
import dev.mcdevmcp.support.JsonResourceReader;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpbManifestGeneratorTest {
    @Test
    void generatedCatalogUsesTheJavaToolMetadataAndConfiguredVersion() throws Exception {
        var mapper = McpJsonDefaults.getMapper();
        Map<String, Object> template = mapper.readValue(new JsonResourceReader(mapper).readText("/contracts/mcpb/manifest.json"), new TypeRef<>() {
        });
        Map<String, Object> manifest;
        List<ToolDefinition> definitions;
        try (var composition = McpServerFactory.declarativeComposition(new dev.mcdevmcp.support.AppEnvironment(Map.of()), mapper)) {
            definitions = composition.definitions();
            manifest = McpbManifestGenerator.generatedRootManifest(template, "3.0.0", definitions);
        }

        assertEquals("0.3", manifest.get("manifest_version"));
        assertEquals("3.0.0", manifest.get("version"));
        assertNull(manifest.get("server"));
        List<Map<String, Object>> tools = maps(manifest.get("tools"));
        assertEquals(definitions.size(), tools.size());
        assertTrue(tools.stream().anyMatch(tool -> tool.get("name").equals("mc_record_video")));
        for (ToolDefinition tool : definitions) {
            Map<String, Object> generated = tools.stream().filter(candidate -> candidate.get("name").equals(tool.name())).findFirst().orElseThrow();
            assertEquals(tool.description(), generated.get("description"));
            assertEquals(tool.inputSchema(), generated.get("inputSchema"));
        }
    }

    @Test
    void stagingManifestAddsOnlyThePackerLauncherConfiguration() {
        Map<String, Object> staging = McpbManifestGenerator.stagingManifest(Map.of("manifest_version", "0.3", "name", "mcdev-mcp", "version", "3.0.0", "tools", List.of(Map.of("name", "mc_record_video", "description", "Record a video.", "inputSchema", Map.of("type", "object")))));

        assertEquals(List.of(Map.of("name", "mc_record_video", "description", "Record a video.")), staging.get("tools"));
        Map<String, Object> server = map(staging.get("server"));
        assertEquals("node", server.get("type"));
        assertEquals("bootstrap.cjs", server.get("entry_point"));
        assertEquals("node", map(server.get("mcp_config")).get("command"));
    }

    @Test
    void writerProducesIdenticalRootAndPackerCatalogs() throws Exception {
        var root = Files.createTempDirectory("mcpb-manifest");
        var template = root.resolve("template.json");
        Files.writeString(template, new JsonResourceReader(McpJsonDefaults.getMapper()).readText("/contracts/mcpb/manifest.json"));
        var rootManifest = root.resolve("manifest.json");
        var stagingManifest = root.resolve("stage/manifest.json");

        McpbManifestGenerator.generate(template, rootManifest, stagingManifest, "3.0.0");

        var mapper = McpJsonDefaults.getMapper();
        Map<String, Object> generatedRoot = mapper.readValue(Files.readString(rootManifest), new TypeRef<>() {
        });
        Map<String, Object> generatedStaging = mapper.readValue(Files.readString(stagingManifest), new TypeRef<>() {
        });
        assertEquals("3.0.0", generatedRoot.get("version"));
        assertEquals(generatedRoot.get("name"), generatedStaging.get("name"));
        assertFalse(generatedRoot.containsKey("server"));
        assertTrue(generatedStaging.containsKey("server"));

        try (var composition = McpServerFactory.declarativeComposition(new dev.mcdevmcp.support.AppEnvironment(Map.of()), mapper)) {
            List<ToolDefinition> definitions = composition.definitions();
            List<Map<String, Object>> rootTools = maps(generatedRoot.get("tools"));
            List<Map<String, Object>> stagingTools = maps(generatedStaging.get("tools"));
            assertEquals(33, definitions.size());
            assertEquals(definitions.size(), rootTools.size());
            assertEquals(definitions.size(), stagingTools.size());
            for (int index = 0; index < definitions.size(); index++) {
                ToolDefinition definition = definitions.get(index);
                Map<String, Object> rootTool = rootTools.get(index);
                Map<String, Object> stagingTool = stagingTools.get(index);
                assertEquals(definition.name(), rootTool.get("name"));
                assertEquals(definition.description(), rootTool.get("description"));
                assertEquals(McpContractTestSupport.normalize(definition.inputSchema()), McpContractTestSupport.normalize(rootTool.get("inputSchema")));
                assertEquals(Map.of("name", definition.name(), "description", definition.description()), stagingTool);
                assertFalse(stagingTool.containsKey("inputSchema"));
            }
        }
        assertEquals(Map.of("entry_point", "bootstrap.cjs", "type", "node", "mcp_config", Map.of("command", "node", "args", List.of("bootstrap.cjs"), "env", Map.of("MCDEV_SESSION_LOG_DIR", "${user_config.script_logs}", "MCDEV_RUN_COMMAND", "${user_config.run_command}", "MCDEV_MCP_DEBUG_LOG", "${user_config.debug_log}", "MCDEV_INDEX_THREADS", "${user_config.index_threads}", "DEBUGBRIDGE_PORT", "${user_config.debugbridge_port}"))), generatedStaging.get("server"));
    }

    @Test
    void repeatedGenerationProducesIdenticalBytesAndTemplateOrder() throws Exception {
        var root = Files.createTempDirectory("mcpb-manifest-reproducibility");
        var template = root.resolve("template.json");
        Files.writeString(template, new JsonResourceReader(McpJsonDefaults.getMapper()).readText("/contracts/mcpb/manifest.json"));
        var firstRoot = root.resolve("first/manifest.json");
        var firstStaging = root.resolve("first/staging/manifest.json");
        var secondRoot = root.resolve("second/manifest.json");
        var secondStaging = root.resolve("second/staging/manifest.json");

        McpbManifestGenerator.generate(template, firstRoot, firstStaging, "3.0.0");
        McpbManifestGenerator.generate(template, secondRoot, secondStaging, "3.0.0");

        assertArrayEquals(Files.readAllBytes(firstRoot), Files.readAllBytes(secondRoot));
        assertArrayEquals(Files.readAllBytes(firstStaging), Files.readAllBytes(secondStaging));
        assertLfTerminatedJson(Files.readAllBytes(firstRoot));
        assertLfTerminatedJson(Files.readAllBytes(firstStaging));
        Map<String, Object> generated = McpJsonDefaults.getMapper().readValue(Files.readString(firstRoot), new TypeRef<>() {
        });
        assertEquals(List.of("manifest_version", "name", "display_name", "description", "author", "version", "tools", "user_config"), List.copyOf(generated.keySet()));
    }

    @Test
    void checkedInRootManifestMatchesCurrentCatalog() throws Exception {
        var root = Files.createTempDirectory("mcpb-manifest-checked-in");
        var generatedRoot = root.resolve("manifest.json");
        var generatedStaging = root.resolve("staging/manifest.json");
        var checkedInManifestPath = Path.of("manifest.json");
        var template = Path.of("packaging/mcpb/manifest.template.json");
        byte[] checkedInManifest = Files.readAllBytes(checkedInManifestPath);
        assertLfTerminatedJson(checkedInManifest);
        Files.copy(checkedInManifestPath, generatedRoot);

        McpbManifestGenerator.generate(template, generatedRoot, generatedStaging);

        assertArrayEquals(checkedInManifest, Files.readAllBytes(generatedRoot));
        byte[] firstStagingManifest = Files.readAllBytes(generatedStaging);
        McpbManifestGenerator.generate(template, generatedRoot, generatedStaging);
        assertArrayEquals(checkedInManifest, Files.readAllBytes(generatedRoot));
        assertArrayEquals(firstStagingManifest, Files.readAllBytes(generatedStaging));
        Map<String, Object> generated = McpJsonDefaults.getMapper().readValue(Files.readString(generatedRoot), new TypeRef<>() {
        });
        assertEquals(AppVersion.current(), generated.get("version"));
    }

    private static void assertLfTerminatedJson(byte[] bytes) {
        assertTrue(bytes.length >= 2);
        assertEquals('}', bytes[bytes.length - 2]);
        assertEquals('\n', bytes[bytes.length - 1]);
        for (byte value : bytes) {
            assertNotEquals('\r', value, "Manifest JSON must use platform-independent LF line endings");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertInstanceOf(Map.class, value);
        return (Map<String, Object>) value;
    }

    private static List<Map<String, Object>> maps(Object value) {
        assertInstanceOf(List.class, value);
        return ((List<?>) value).stream().map(McpbManifestGeneratorTest::map).toList();
    }
}

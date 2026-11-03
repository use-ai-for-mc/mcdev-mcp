package dev.mcdevmcp.packaging;

import dev.mcdevmcp.mcp.McpContractTestSupport;
import dev.mcdevmcp.mcp.McpServerFactory;
import dev.mcdevmcp.mcp.tool.ToolDefinition;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.AppVersion;
import dev.mcdevmcp.support.JsonResourceReader;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpbManifestGeneratorTest {
    private static final Map<String, String> SETTINGS = Map.of("MCDEV_SESSION_LOG_DIR", "${user_config.script_logs}", "MCDEV_RUN_COMMAND", "${user_config.run_command}", "MCDEV_MCP_DEBUG_LOG", "${user_config.debug_log}", "MCDEV_INDEX_THREADS", "${user_config.index_threads}", "DEBUGBRIDGE_PORT", "${user_config.debugbridge_port}");

    private static void assertLfTerminatedJson(byte[] bytes) {
        assertTrue(bytes.length >= 2);
        assertEquals('}', bytes[bytes.length - 2]);
        assertEquals('\n', bytes[bytes.length - 1]);
        for (byte value : bytes) {
            assertNotEquals('\r', value, "Manifest JSON must use platform-independent LF line endings");
        }
    }

    private static Map<?, ?> map(Object value) {
        return assertInstanceOf(Map.class, value);
    }

    private static List<Map<?, ?>> maps(Object value) {
        List<?> elements = assertInstanceOf(List.class, value);
        return elements.stream().<Map<?, ?>>map(McpbManifestGeneratorTest::map).toList();
    }

    private static Map<String, Object> read(Path manifest) throws IOException {
        return McpJsonDefaults.getMapper().readValue(Files.readString(manifest), new TypeRef<>() {
        });
    }

    private static Path template(Path directory) throws IOException {
        return Files.writeString(directory.resolve("template.json"), new JsonResourceReader(McpJsonDefaults.getMapper()).readText("/contracts/mcpb/manifest.json"));
    }

    @Test
    void generatedCatalogUsesTheJavaToolMetadataAndConfiguredVersion() throws Exception {
        var mapper = McpJsonDefaults.getMapper();
        Map<String, Object> template = mapper.readValue(new JsonResourceReader(mapper).readText("/contracts/mcpb/manifest.json"), new TypeRef<>() {
        });
        Map<String, Object> manifest;
        List<ToolDefinition> definitions;
        try (var composition = McpServerFactory.declarativeComposition(new AppEnvironment(Map.of()), mapper)) {
            definitions = composition.definitions();
            manifest = McpbManifestGenerator.generatedRootManifest(template, "3.0.0", definitions);
        }

        assertEquals("0.3", manifest.get("manifest_version"));
        assertEquals("3.0.0", manifest.get("version"));
        assertNull(manifest.get("server"));
        assertNull(manifest.get("compatibility"));
        List<Map<?, ?>> tools = maps(manifest.get("tools"));
        assertEquals(definitions.size(), tools.size());
        assertTrue(tools.stream().anyMatch(tool -> "mc_record_video".equals(tool.get("name"))));
        for (ToolDefinition tool : definitions) {
            Map<?, ?> generated = tools.stream().filter(candidate -> tool.name().equals(candidate.get("name"))).findFirst().orElseThrow();
            assertEquals(tool.description(), generated.get("description"));
            assertEquals(tool.inputSchema(), generated.get("inputSchema"));
        }
    }

    @Test
    void platformsNameTheLauncherOfTheImageBuiltOnEachSystem() {
        assertEquals(List.of("manifest-windows.json win32 mcdev-mcp/mcdev-mcp.exe", "manifest-linux.json linux mcdev-mcp/bin/mcdev-mcp", "manifest-macos.json darwin mcdev-mcp.app/Contents/MacOS/mcdev-mcp"), Arrays.stream(McpbPlatform.values()).map(platform -> platform.manifestFileName() + " " + platform.manifestName() + " " + platform.launcher()).toList());
    }

    @ParameterizedTest
    @EnumSource(McpbPlatform.class)
    void stagingManifestStartsTheLauncherOfItsOwnPlatformAndNothingElse(McpbPlatform platform) {
        Map<String, Object> staging = McpbManifestGenerator.stagingManifest(Map.of("manifest_version", "0.3", "name", "mcdev-mcp", "version", "3.0.0", "tools", List.of(Map.of("name", "mc_record_video", "description", "Record a video.", "inputSchema", Map.of("type", "object")))), platform);

        assertEquals(List.of(Map.of("name", "mc_record_video", "description", "Record a video.")), staging.get("tools"));
        assertEquals(Map.of("type", "binary", "entry_point", platform.launcher(), "mcp_config", Map.of("command", "${__dirname}/" + platform.launcher(), "args", List.of("serve"), "env", SETTINGS)), staging.get("server"));
        assertEquals(Map.of("platforms", List.of(platform.manifestName())), staging.get("compatibility"));
    }

    @Test
    void writerProducesIdenticalRootAndPackerCatalogs() throws Exception {
        var root = Files.createTempDirectory("mcpb-manifest");
        var rootManifest = root.resolve("manifest.json");
        var staging = root.resolve("stage");

        McpbManifestGenerator.generate(template(root), rootManifest, staging, "3.0.0");

        Map<String, Object> generatedRoot = read(rootManifest);
        assertEquals("3.0.0", generatedRoot.get("version"));
        assertFalse(generatedRoot.containsKey("server"));
        List<Map<?, ?>> rootTools = maps(generatedRoot.get("tools"));
        try (var composition = McpServerFactory.declarativeComposition(new AppEnvironment(Map.of()), McpJsonDefaults.getMapper())) {
            List<ToolDefinition> definitions = composition.definitions();
            assertEquals(33, definitions.size());
            assertEquals(definitions.size(), rootTools.size());
            for (McpbPlatform platform : McpbPlatform.values()) {
                Map<String, Object> generatedStaging = read(staging.resolve(platform.manifestFileName()));
                assertEquals(generatedRoot.get("name"), generatedStaging.get("name"));
                assertEquals(platform.launcher(), map(generatedStaging.get("server")).get("entry_point"));
                List<Map<?, ?>> stagingTools = maps(generatedStaging.get("tools"));
                assertEquals(definitions.size(), stagingTools.size());
                for (int index = 0; index < definitions.size(); index++) {
                    ToolDefinition definition = definitions.get(index);
                    Map<?, ?> rootTool = rootTools.get(index);
                    assertEquals(definition.name(), rootTool.get("name"));
                    assertEquals(definition.description(), rootTool.get("description"));
                    assertEquals(McpContractTestSupport.normalize(definition.inputSchema()), McpContractTestSupport.normalize(rootTool.get("inputSchema")));
                    assertEquals(Map.of("name", definition.name(), "description", definition.description()), stagingTools.get(index));
                }
            }
        }
        assertEquals(Map.of("entry_point", "mcdev-mcp/mcdev-mcp.exe", "type", "binary", "mcp_config", Map.of("command", "${__dirname}/mcdev-mcp/mcdev-mcp.exe", "args", List.of("serve"), "env", SETTINGS)), read(staging.resolve("manifest-windows.json")).get("server"));
    }

    @Test
    void repeatedGenerationProducesIdenticalBytesAndTemplateOrder() throws Exception {
        var root = Files.createTempDirectory("mcpb-manifest-reproducibility");
        var template = template(root);
        var firstRoot = root.resolve("first/manifest.json");
        var firstStaging = root.resolve("first/staging");
        var secondRoot = root.resolve("second/manifest.json");
        var secondStaging = root.resolve("second/staging");

        McpbManifestGenerator.generate(template, firstRoot, firstStaging, "3.0.0");
        McpbManifestGenerator.generate(template, secondRoot, secondStaging, "3.0.0");

        assertArrayEquals(Files.readAllBytes(firstRoot), Files.readAllBytes(secondRoot));
        assertLfTerminatedJson(Files.readAllBytes(firstRoot));
        for (McpbPlatform platform : McpbPlatform.values()) {
            byte[] first = Files.readAllBytes(firstStaging.resolve(platform.manifestFileName()));
            assertArrayEquals(first, Files.readAllBytes(secondStaging.resolve(platform.manifestFileName())));
            assertLfTerminatedJson(first);
        }
        assertEquals(List.of("manifest_version", "name", "display_name", "description", "author", "version", "tools", "user_config"), List.copyOf(read(firstRoot).keySet()));
        assertEquals(List.of("manifest_version", "name", "display_name", "description", "author", "version", "tools", "user_config", "server", "compatibility"), List.copyOf(read(firstStaging.resolve("manifest-linux.json")).keySet()));
    }

    @Test
    void checkedInRootManifestMatchesCurrentCatalog() throws Exception {
        var root = Files.createTempDirectory("mcpb-manifest-checked-in");
        var generatedRoot = root.resolve("manifest.json");
        var generatedStaging = root.resolve("staging");
        var checkedInManifestPath = Path.of("manifest.json");
        var template = Path.of("packaging/mcpb/manifest.template.json");
        byte[] checkedInManifest = Files.readAllBytes(checkedInManifestPath);
        assertLfTerminatedJson(checkedInManifest);
        Files.copy(checkedInManifestPath, generatedRoot);

        McpbManifestGenerator.generate(template, generatedRoot, generatedStaging);

        assertArrayEquals(checkedInManifest, Files.readAllBytes(generatedRoot));
        assertEquals(AppVersion.current(), read(generatedRoot).get("version"));
    }
}

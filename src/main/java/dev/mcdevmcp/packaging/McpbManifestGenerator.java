package dev.mcdevmcp.packaging;

import dev.mcdevmcp.mcp.McpServerFactory;
import dev.mcdevmcp.mcp.tool.ToolDefinition;
import dev.mcdevmcp.support.AppVersion;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Generates the Java-owned MCPB catalog manifest and the packer-only staging manifest.
 */
public final class McpbManifestGenerator {
    private static final String STAGING_ENTRY_POINT = "bootstrap.cjs";

    private McpbManifestGenerator() {
    }

    static void main(String[] arguments) {
        if (arguments.length != 4) {
            throw new IllegalArgumentException("Usage: McpbManifestGenerator <template> <root-manifest> <staging-manifest> <version>");
        }
        generate(Path.of(arguments[0]), Path.of(arguments[1]), Path.of(arguments[2]), arguments[3]);
    }

    public static void generate(Path template, Path rootManifest, Path stagingManifest) {
        generate(template, rootManifest, stagingManifest, AppVersion.current());
    }

    public static void generate(Path template, Path rootManifest, Path stagingManifest, String version) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(rootManifest, "rootManifest");
        Objects.requireNonNull(stagingManifest, "stagingManifest");
        McpJsonMapper mapper = McpJsonDefaults.getMapper();
        try (var composition = McpServerFactory.declarativeComposition(new dev.mcdevmcp.support.AppEnvironment(Map.of()), mapper)) {
            Map<String, Object> root = generatedRootManifest(readTemplate(mapper, template), version, composition.definitions());
            write(mapper, rootManifest, root);
            write(mapper, stagingManifest, stagingManifest(root));
        }
    }

    static Map<String, Object> generatedRootManifest(Map<String, Object> template, String version, List<ToolDefinition> tools) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(tools, "tools");
        var manifest = new LinkedHashMap<>(template);
        manifest.put("version", version);
        manifest.put("tools", toolDefinitions(tools));
        return Collections.unmodifiableMap(manifest);
    }

    static Map<String, Object> stagingManifest(Map<String, Object> rootManifest) {
        Objects.requireNonNull(rootManifest, "rootManifest");
        var staging = new LinkedHashMap<>(rootManifest);
        staging.computeIfPresent("tools", (_, tools) -> stagingTools(tools));
        staging.put("server", stagingServer());
        return Collections.unmodifiableMap(staging);
    }

    private static List<Map<String, Object>> stagingTools(Object value) {
        if (!(value instanceof List<?> tools)) {
            throw new IllegalArgumentException("MCPB root manifest tools must be an array");
        }
        var result = new ArrayList<Map<String, Object>>(tools.size());
        for (Object tool : tools) {
            if (!(tool instanceof Map<?, ?> metadata) || !(metadata.get("name") instanceof String name) || !(metadata.get("description") instanceof String description)) {
                throw new IllegalArgumentException("MCPB root manifest tool metadata is malformed");
            }
            var entry = new LinkedHashMap<String, Object>();
            entry.put("name", name);
            entry.put("description", description);
            result.add(Collections.unmodifiableMap(entry));
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> readTemplate(McpJsonMapper mapper, Path template) {
        try {
            Map<String, Object> result = mapper.readValue(Files.readString(template, StandardCharsets.UTF_8), new TypeRef<>() {
            });
            if (!"0.3".equals(result.get("manifest_version"))) {
                throw new IllegalArgumentException("MCPB manifest template must use manifest_version 0.3");
            }
            return Collections.unmodifiableMap(new LinkedHashMap<>(result));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read MCPB manifest template: " + template, exception);
        }
    }

    private static List<Map<String, Object>> toolDefinitions(List<ToolDefinition> tools) {
        var result = new ArrayList<Map<String, Object>>(tools.size());
        for (ToolDefinition tool : tools) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("name", tool.name());
            entry.put("description", tool.description());
            entry.put("inputSchema", tool.inputSchema());
            result.add(Collections.unmodifiableMap(entry));
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> stagingServer() {
        var environment = new LinkedHashMap<String, Object>();
        environment.put("MCDEV_SESSION_LOG_DIR", "${user_config.script_logs}");
        environment.put("MCDEV_RUN_COMMAND", "${user_config.run_command}");
        environment.put("MCDEV_MCP_DEBUG_LOG", "${user_config.debug_log}");
        environment.put("MCDEV_INDEX_THREADS", "${user_config.index_threads}");
        environment.put("DEBUGBRIDGE_PORT", "${user_config.debugbridge_port}");

        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("command", "node");
        configuration.put("args", List.of(STAGING_ENTRY_POINT));
        configuration.put("env", Collections.unmodifiableMap(environment));

        Map<String, Object> server = new LinkedHashMap<>();
        server.put("type", "node");
        server.put("entry_point", STAGING_ENTRY_POINT);
        server.put("mcp_config", Collections.unmodifiableMap(configuration));
        return Collections.unmodifiableMap(server);
    }

    private static void write(McpJsonMapper mapper, Path target, Map<String, Object> value) {
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, mapper.writeValueAsString(value) + "\n", StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write MCPB manifest: " + target, exception);
        }
    }
}

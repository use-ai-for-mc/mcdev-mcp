package dev.mcdevmcp.parity;

import dev.mcdevmcp.mcp.McpContractTestSupport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@Tag("parity")
@ResourceLock("node-oracle-materializer")
class DifferentialMcpTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();
    private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() {
    };
    private static final Set<String> STATIC_TOOLS = Set.of("mc_version", "mc_search", "mc_get_class", "mc_get_method", "mc_find_refs", "mc_list_classes", "mc_list_packages", "mc_find_hierarchy");
    private static final Set<String> RUNTIME_TOOLS = Set.of("mc_connect", "mc_execute", "mc_snapshot", "mc_screenshot", "mc_record_video", "mc_nearby_entities", "mc_entity_details", "mc_nearby_blocks", "mc_block_details", "mc_looked_at_entity", "mc_set_entity_glow", "mc_set_block_glow", "mc_clear_block_glow", "mc_get_item_texture", "mc_get_entity_item_texture", "mc_get_item_texture_by_id", "mc_chat_history", "mc_screen_inspect", "mc_join_server", "mc_leave_server", "mc_wait_until_in_world", "mc_quit_client", "mc_wait_for_bridge", "mc_script_logs", "mc_run_command");
    private static final Set<String> REVIEWED_RECORD_VIDEO_BRIDGE_KEYS = Set.of("frames", "interval", "output", "gridCols", "downscale", "quality");
    private static final Set<String> REVIEWED_ENTITY_ITEM_BRIDGE_KEYS = Set.of("entityId", "slot");
    private static final Map<String, LimitTypePair> TYPED_CATALOG_LIMIT_TYPE_DIFFS = Map.ofEntries(Map.entry("mc_search:inputSchema.properties.limit.type", new LimitTypePair("number", "integer")), Map.entry("mc_find_refs:inputSchema.properties.limit.type", new LimitTypePair("number", "integer")), Map.entry("mc_list_classes:inputSchema.properties.limit.type", new LimitTypePair("number", "integer")), Map.entry("mc_list_packages:inputSchema.properties.limit.type", new LimitTypePair("number", "integer")), Map.entry("mc_find_hierarchy:inputSchema.properties.limit.type", new LimitTypePair("number", "integer")));
    private static final Map<String, LimitDescriptionPair> TYPED_CATALOG_LIMIT_DESCRIPTION_DIFFS = Map.ofEntries(Map.entry("mc_search:inputSchema.properties.limit.description", new LimitDescriptionPair("Optional: max results to return (default 50, ceiling 1000). Non-positive or non-finite values fall back to the default.", "Optional: max results to return (default 50, ceiling 1000). Omit this property to use the default; values below 1 are invalid; oversized values are capped.")), Map.entry("mc_list_packages:inputSchema.properties.limit.description", new LimitDescriptionPair("Optional: max results to return (default 500, ceiling 5000). Non-positive or non-finite values fall back to the default.", "Optional: max results to return (default 500, ceiling 5000). Omit this property to use the default; values below 1 are invalid; oversized values are capped.")), Map.entry("mc_list_classes:inputSchema.properties.limit.description", new LimitDescriptionPair("Optional: max results to return (default 200, ceiling 5000). Non-positive or non-finite values fall back to the default.", "Optional: max results to return (default 200, ceiling 5000). Omit this property to use the default; values below 1 are invalid; oversized values are capped.")), Map.entry("mc_find_hierarchy:inputSchema.properties.limit.description", new LimitDescriptionPair("Optional: max results to return (default 200, ceiling 5000). Non-positive or non-finite values fall back to the default.", "Optional: max results to return (default 200, ceiling 5000). Omit this property to use the default; values below 1 are invalid; oversized values are capped.")), Map.entry("mc_find_refs:inputSchema.properties.limit.description", new LimitDescriptionPair("Optional: max results to return (default 100, ceiling 5000). Non-positive or non-finite values fall back to the default.", "Optional: max results to return (default 100, ceiling 5000). Omit this property to use the default; values below 1 are invalid; oversized values are capped.")));
    private static final Set<String> TYPED_CATALOG_DEFAULTS = Set.of("mc_connect:inputSchema.properties.reset.default", "mc_execute:inputSchema.properties.timeoutSeconds.default", "mc_join_server:inputSchema.properties.acceptResourcePacks.default", "mc_join_server:inputSchema.properties.wait.default", "mc_join_server:inputSchema.properties.timeoutSeconds.default", "mc_wait_until_in_world:inputSchema.properties.timeoutSeconds.default", "mc_wait_until_in_world:inputSchema.properties.requireAbsenceFirst.default", "mc_quit_client:inputSchema.properties.waitForExit.default", "mc_quit_client:inputSchema.properties.timeoutSeconds.default", "mc_wait_for_bridge:inputSchema.properties.timeoutSeconds.default", "mc_record_video:inputSchema.properties.output.default", "mc_record_video:inputSchema.properties.downscale.default", "mc_record_video:inputSchema.properties.quality.default", "mc_nearby_entities:inputSchema.properties.range.default", "mc_nearby_entities:inputSchema.properties.limit.default", "mc_nearby_entities:inputSchema.properties.includeIcons.default", "mc_nearby_blocks:inputSchema.properties.range.default", "mc_nearby_blocks:inputSchema.properties.limit.default", "mc_looked_at_entity:inputSchema.properties.range.default", "mc_chat_history:inputSchema.properties.limit.default", "mc_chat_history:inputSchema.properties.includeJson.default", "mc_screen_inspect:inputSchema.properties.includeIcons.default", "mc_screenshot:inputSchema.properties.downscale.default", "mc_screenshot:inputSchema.properties.quality.default", "mc_script_logs:inputSchema.properties.mode.default", "mc_script_logs:inputSchema.properties.limit.default");
    private static final String REVIEWED_NODE_RECORD_VIDEO_DESCRIPTION = "Capture a short burst of the Minecraft client framebuffer for debugging\ntemporal rendering issues — animation glitches, shader bugs, particles,\nsub-tick artifacts that mc_screenshot can't resolve. Use the Read tool to\nview the result.\n\nTwo output modes:\n- \"grid\" (default): one composed JPEG laid out as a frame grid. Best for\n  Claude — the whole recording in a single Read.\n- \"frames\": N separate JPEGs. Use only when you need to inspect individual\n  frames closely.\n\nCaps (validated mod-side, request rejected if exceeded):\n- frames: 1..300 (≈5 s at 60 Hz)\n- interval: \"frame\" (every render tick) or milliseconds >= 1\n- downscale: integer >= 1 (default 2)\n- quality: [0.05, 1.0] (default 0.75)\n\nPick interval deliberately. Default to a numeric ms (50–100 ms is usually\nright) — smooth motion, never drops frames. Use \"frame\" only when sub-tick\ndetail matters; at that cadence the encoder may fall behind and the\nresponse's \"dropped\" count tells you how many frames were skipped.\n\nThe mod and the MCP server must run on the same machine for the returned\npaths to be readable here. Files land under\n<gameDir>/debugbridge-recordings/<requestId>/.";
    private static final String REVIEWED_JAVA_RECORD_VIDEO_DESCRIPTION = "Capture a short burst of the Minecraft client framebuffer for debugging\ntemporal rendering issues — animation glitches, shader bugs, particles,\nsub-tick artifacts that mc_screenshot can't resolve. Use the Read tool to\nview the result.\n\nTwo output modes:\n- \"grid\" (default): one composed JPEG laid out as a frame grid. Best for\n  Claude — the whole recording in a single Read.\n- \"frames\": N separate JPEGs. Use only when you need to inspect individual\n  frames closely.\n\nCaps (validated mod-side, request rejected if exceeded):\n- frames: 1..300 (≈5 s at 60 Hz)\n- interval: {kind:\"frame\"} (every render tick) or {kind:\"fixed\",intervalSeconds:0.05} (fixed seconds >= 0.001)\n- downscale: integer >= 1 (default 2)\n- quality: [0.05, 1.0] (default 0.75)\n\nPick interval deliberately. Default to {kind:\"frame\"} (every render tick). Use a fixed interval such as\n{kind:\"fixed\",intervalSeconds:0.05} for smooth motion, never drops frames. At frame cadence, the\nencoder may fall behind and the response's \"dropped\" count tells you how many frames were skipped.\n\nThe mod and the MCP server must run on the same machine for the returned\npaths to be readable here. Files land under\n<gameDir>/debugbridge-recordings/<requestId>/.";
    private static final String LEGACY_EXECUTE_TIMEOUT_PROSE = "\n\ntimeoutMs: optional per-call deadline in ms (default 10000, max 300000 = 5 min).";
    private static final Set<String> TYPED_CATALOG_EMPTY_REQUIRED = Set.of("mc_list_packages:inputSchema.required", "mc_connect:inputSchema.required", "mc_snapshot:inputSchema.required", "mc_screenshot:inputSchema.required", "mc_nearby_entities:inputSchema.required", "mc_nearby_blocks:inputSchema.required", "mc_looked_at_entity:inputSchema.required", "mc_clear_block_glow:inputSchema.required", "mc_chat_history:inputSchema.required", "mc_screen_inspect:inputSchema.required", "mc_leave_server:inputSchema.required", "mc_wait_until_in_world:inputSchema.required", "mc_quit_client:inputSchema.required", "mc_wait_for_bridge:inputSchema.required", "mc_script_logs:inputSchema.required");
    private static final Set<String> TYPED_CATALOG_ADDITIONAL_PROPERTIES = Set.of("mc_version:inputSchema.additionalProperties", "mc_search:inputSchema.additionalProperties", "mc_get_class:inputSchema.additionalProperties", "mc_get_method:inputSchema.additionalProperties", "mc_find_refs:inputSchema.additionalProperties", "mc_list_classes:inputSchema.additionalProperties", "mc_list_packages:inputSchema.additionalProperties", "mc_find_hierarchy:inputSchema.additionalProperties", "mc_connect:inputSchema.additionalProperties", "mc_execute:inputSchema.additionalProperties", "mc_snapshot:inputSchema.additionalProperties", "mc_screenshot:inputSchema.additionalProperties", "mc_record_video:inputSchema.additionalProperties", "mc_record_video:inputSchema.properties.interval.oneOf[0].additionalProperties", "mc_record_video:inputSchema.properties.interval.oneOf[1].additionalProperties", "mc_nearby_entities:inputSchema.additionalProperties", "mc_entity_details:inputSchema.additionalProperties", "mc_nearby_blocks:inputSchema.additionalProperties", "mc_block_details:inputSchema.additionalProperties", "mc_block_details:inputSchema.properties.position.additionalProperties", "mc_looked_at_entity:inputSchema.additionalProperties", "mc_set_entity_glow:inputSchema.additionalProperties", "mc_set_block_glow:inputSchema.additionalProperties", "mc_set_block_glow:inputSchema.properties.position.additionalProperties", "mc_clear_block_glow:inputSchema.additionalProperties", "mc_get_item_texture:inputSchema.additionalProperties", "mc_get_entity_item_texture:inputSchema.additionalProperties", "mc_get_item_texture_by_id:inputSchema.additionalProperties", "mc_chat_history:inputSchema.additionalProperties", "mc_screen_inspect:inputSchema.additionalProperties", "mc_join_server:inputSchema.additionalProperties", "mc_leave_server:inputSchema.additionalProperties", "mc_wait_until_in_world:inputSchema.additionalProperties", "mc_quit_client:inputSchema.additionalProperties", "mc_wait_for_bridge:inputSchema.additionalProperties", "mc_script_logs:inputSchema.additionalProperties", "mc_run_command:inputSchema.additionalProperties");
    private static final String JAVA_EXECUTE_TIMEOUT_PROSE = "\n\ntimeoutSeconds: optional per-call deadline in seconds (default 10, max 300 = 5 min).";
    private static final Map<String, Set<StaticOutcome>> REQUIRED_PROCESS_STATIC_OUTCOMES = Map.of("mc_version", Set.of(StaticOutcome.SUCCESS, StaticOutcome.ERROR), "mc_search", Set.of(StaticOutcome.SUCCESS, StaticOutcome.ERROR, StaticOutcome.EMPTY, StaticOutcome.TRUNCATED), "mc_get_class", Set.of(StaticOutcome.SUCCESS, StaticOutcome.ERROR, StaticOutcome.EMPTY), "mc_get_method", Set.of(StaticOutcome.SUCCESS, StaticOutcome.ERROR, StaticOutcome.EMPTY), "mc_find_refs", Set.of(StaticOutcome.SUCCESS, StaticOutcome.ERROR, StaticOutcome.EMPTY, StaticOutcome.TRUNCATED), "mc_list_classes", Set.of(StaticOutcome.SUCCESS, StaticOutcome.ERROR, StaticOutcome.EMPTY, StaticOutcome.TRUNCATED), "mc_list_packages", Set.of(StaticOutcome.SUCCESS, StaticOutcome.ERROR, StaticOutcome.EMPTY, StaticOutcome.TRUNCATED), "mc_find_hierarchy", Set.of(StaticOutcome.SUCCESS, StaticOutcome.ERROR, StaticOutcome.EMPTY, StaticOutcome.TRUNCATED));

    @TempDir
    Path temporaryDirectory;

    @Test
    void matchesThePinnedNodeServerAcrossTheCompleteMcpSurface() throws Exception {
        List<Scenario> scenarios = scenarios();
        Path nodeRoot = prepareProcessRoot("node");
        Path javaRoot = prepareProcessRoot("java");

        try (NodeOracleMaterializer oracle = NodeOracleMaterializer.materialize()) {
            StaticParityFixture.prepareNode(nodeRoot, oracle);
            StaticParityFixture.prepareJava(javaRoot);
            try (ScriptedDebugBridge nodeBridge = ScriptedDebugBridge.start(nodeRoot.resolve("bridge"));
                 ScriptedDebugBridge javaBridge = ScriptedDebugBridge.start(javaRoot.resolve("bridge"));
                 McpProcessClient node = McpProcessClient.startAllowingForcedShutdown(configure(oracle.nodeProcess("dist/cli.js", "serve"), nodeRoot, nodeBridge.port()));
                 McpProcessClient java = McpProcessClient.start(configure(javaProcess(javaRoot), javaRoot, javaBridge.port()))) {
                Map<String, Map<String, Object>> nodeResponses = new LinkedHashMap<>();

                for (Scenario scenario : scenarios) {
                    Map<String, Object> nodeResponse = execute(node, scenario, nodeBridge.port(), true);
                    Map<String, Object> javaResponse = execute(java, scenario, javaBridge.port(), false);
                    nodeResponses.put(scenario.label(), nodeResponse);
                    if (scenario.label().equals("runtime-looked-at-entity") || scenario.label().equals("runtime-looked-at-entity-null")) {
                        String expected = scenario.label().equals("runtime-looked-at-entity") ? "{\n  \"entityId\": 7\n}" : "{\n  \"entityId\": null\n}";
                        assertEquals(expected, toolText(nodeResponse), "Pinned Node provider-object rendering: " + scenario.label());
                        assertEquals(expected, toolText(javaResponse), "Java provider-object rendering: " + scenario.label());
                    }
                    if (scenario.kind().equals("static")) {
                        assertStaticOutcome(scenario, nodeResponse, "Node");
                        assertStaticOutcome(scenario, javaResponse, "Java");
                    }
                    if (scenario.kind().equals("initialize")) {
                        assertInitializeVersions(nodeResponse, javaResponse);
                    }
                    switch (scenario.comparison()) {
                        case "exact" ->
                                assertEquivalent(scenario, nodeResponse, javaResponse, nodeRoot, javaRoot, nodeBridge.port(), javaBridge.port());
                        case "find_refs_descriptors" ->
                                assertDescriptorUpgrade(scenario, nodeResponse, javaResponse, nodeRoot, javaRoot, nodeBridge.port(), javaBridge.port());
                        case "java_launcher" ->
                                assertJavaLauncherUpgrade(scenario, nodeResponse, javaResponse, nodeRoot, javaRoot, nodeBridge.port(), javaBridge.port());
                        case "sdk_input_validation" ->
                                assertSdkInputValidationUpgrade(scenario, nodeResponse, javaResponse, nodeRoot, javaRoot, nodeBridge.port(), javaBridge.port());
                        case "typed_catalog" -> assertTypedCatalogUpgrade(nodeResponse, javaResponse);
                        case "pinned_guide_links" ->
                                assertCurrentGuideReferences(scenario, nodeResponse, javaResponse, nodeRoot, javaRoot, nodeBridge.port(), javaBridge.port());
                        case "reviewed_guide" ->
                                assertReviewedGuide(scenario, nodeResponse, javaResponse, nodeRoot, javaRoot, nodeBridge.port(), javaBridge.port());
                        default ->
                                throw new IllegalStateException("Unsupported parity comparison: " + scenario.comparison());
                    }
                }

                assertCatalogCoverage(nodeResponses.get("tools-list"), scenarios);
                assertStaticProcessCoverage(scenarios);
                assertEquals(normalizeValue(node.awaitQuiescence(), nodeRoot, nodeBridge.port()), normalizeValue(java.awaitQuiescence(), javaRoot, javaBridge.port()), "Node and Java must emit the same MCP notifications and no unmatched responses");
                assertTrue(bridgeInvocationsEquivalent(nodeBridge.invocations(), javaBridge.invocations()), "Node and Java must make the same DebugBridge calls in the same order, with only the reviewed bridge projections");
                nodeBridge.assertHealthy();
                javaBridge.assertHealthy();
                assertTrue(node.stderr().isBlank(), () -> "Node server STDERR was not clean:\n" + node.stderr());
                assertTrue(java.stderr().isBlank(), () -> "Java server STDERR was not clean:\n" + java.stderr());
                nodeBridge.shutdown();
                javaBridge.shutdown();
            }
        }
    }

    @Test
    void reviewedBridgeProjectionsRejectUnreviewedRepresentations() {
        var nodeRecord = new ScriptedDebugBridge.Invocation("record_video", object("frames", 4, "interval", 50, "output", "grid", "gridCols", 2, "downscale", 2, "quality", 0.75));
        var javaRecord = new ScriptedDebugBridge.Invocation("record_video", object("frames", 4, "interval", 50.0, "output", "grid", "gridCols", 2, "downscale", 2, "quality", 0.75));
        assertTrue(bridgeInvocationsEquivalent(List.of(nodeRecord), List.of(javaRecord)), "The reviewed 50 / 50.0 record-video pair must compare equal");

        var nodeUnreviewedRecord = new ScriptedDebugBridge.Invocation("record_video", object("frames", 4, "interval", 51, "output", "grid", "gridCols", 2, "downscale", 2, "quality", 0.75));
        var javaUnreviewedRecord = new ScriptedDebugBridge.Invocation("record_video", object("frames", 4, "interval", 51.0, "output", "grid", "gridCols", 2, "downscale", 2, "quality", 0.75));
        assertFalse(bridgeInvocationsEquivalent(List.of(nodeUnreviewedRecord), List.of(javaUnreviewedRecord)), "An unreviewed 51 / 51.0 record-video pair must not compare equal");

        var nodeEntityItem = new ScriptedDebugBridge.Invocation("getEntityItemTexture", object("entityId", 7, "slot", "mainhand"));
        var javaEntityItem = new ScriptedDebugBridge.Invocation("getEntityItemTexture", object("entityId", 7, "slot", "MAINHAND"));
        assertTrue(bridgeInvocationsEquivalent(List.of(nodeEntityItem), List.of(javaEntityItem)), "The reviewed mainhand / MAINHAND entity-item pair must compare equal");

        var nodeUnreviewedEntityItem = new ScriptedDebugBridge.Invocation("getEntityItemTexture", object("entityId", 7, "slot", "head"));
        var javaUnreviewedEntityItem = new ScriptedDebugBridge.Invocation("getEntityItemTexture", object("entityId", 7, "slot", "HEAD"));
        assertFalse(bridgeInvocationsEquivalent(List.of(nodeUnreviewedEntityItem), List.of(javaUnreviewedEntityItem)), "An unreviewed slot spelling must not compare equal");
    }

    private Path prepareProcessRoot(String name) throws IOException {
        Path root = temporaryDirectory.resolve(name).toAbsolutePath().normalize();
        Files.createDirectories(root.resolve("home"));
        Files.createDirectories(root.resolve("local-app-data"));
        Files.createDirectories(root.resolve("roaming-app-data"));
        Files.createDirectories(root.resolve("xdg-cache"));
        Files.createDirectories(root.resolve("tmp"));
        Files.createDirectories(root.resolve("bridge"));
        return root;
    }

    private static ProcessBuilder configure(ProcessBuilder builder, Path root, int bridgePort) {
        Map<String, String> environment = builder.environment();
        environment.keySet().removeIf(name -> {
            String normalized = name.toUpperCase(Locale.ROOT);
            return normalized.startsWith("MCDEV_") || normalized.equals("DEBUGBRIDGE_PORT") || normalized.equals("NODE_OPTIONS") || normalized.equals("JAVA_TOOL_OPTIONS") || normalized.equals("_JAVA_OPTIONS") || normalized.equals("JDK_JAVA_OPTIONS");
        });
        environment.put("HOME", root.resolve("home").toString());
        environment.put("USERPROFILE", root.resolve("home").toString());
        environment.put("LOCALAPPDATA", root.resolve("local-app-data").toString());
        environment.put("APPDATA", root.resolve("roaming-app-data").toString());
        environment.put("XDG_CACHE_HOME", root.resolve("xdg-cache").toString());
        environment.put("TEMP", root.resolve("tmp").toString());
        environment.put("TMP", root.resolve("tmp").toString());
        environment.put("DEBUGBRIDGE_PORT", Integer.toString(bridgePort));
        environment.put("MCDEV_SCRIPT_LOGS", "1");
        environment.put("MCDEV_RUN_COMMAND", "1");
        return builder;
    }

    private static ProcessBuilder javaProcess(Path root) {
        String executable = requiredProperty("mcdevMcpJava");
        Path jar = Path.of(requiredProperty("mcdevMcpJar")).toAbsolutePath().normalize();
        return new ProcessBuilder(executable, "-Dfile.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US", "-Duser.home=" + root.resolve("home"), "-Djava.io.tmpdir=" + root.resolve("tmp"), "-jar", jar.toString(), "serve");
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing Gradle test property '" + name + "'");
        }
        return value;
    }

    private static Map<String, Object> execute(McpProcessClient client, Scenario scenario, int bridgePort, boolean nodeServer) throws IOException {
        Map<String, Object> baseRequest = replaceBridgePort(scenario.request(), bridgePort);
        Map<String, Object> request = nodeServer ? legacyNodeRecordVideo(legacyNodeBlockPosition(baseRequest)) : baseRequest;
        if (scenario.kind().equals("initialize")) {
            return client.initialize(map(request.get("params")));
        }
        return client.request(request);
    }

    private static Map<String, Object> legacyNodeBlockPosition(Map<String, Object> request) {
        Map<String, Object> parameters = map(request.get("params"));
        String tool = Objects.toString(parameters.get("name"), "");
        if (!tool.equals("mc_block_details") && !tool.equals("mc_set_block_glow")) {
            return request;
        }
        Map<String, Object> arguments = map(parameters.get("arguments"));
        Object position = arguments.remove("position");
        if (position instanceof Map<?, ?> coordinates) {
            coordinates.forEach((key, value) -> arguments.put((String) key, value));
        }
        parameters.put("arguments", arguments);
        request.put("params", parameters);
        return request;
    }

    private static Map<String, Object> legacyNodeRecordVideo(Map<String, Object> request) {
        Map<String, Object> parameters = map(request.get("params"));
        if (!Objects.equals(parameters.get("name"), "mc_record_video")) {
            return request;
        }
        Map<String, Object> arguments = map(parameters.get("arguments"));
        Object interval = arguments.get("interval");
        if (interval instanceof Map<?, ?> object && Objects.equals(object.get("kind"), "fixed")) {
            Number seconds = (Number) object.get("intervalSeconds");
            arguments.put("interval", seconds.doubleValue() * 1000.0);
        }
        parameters.put("arguments", arguments);
        request.put("params", parameters);
        return request;
    }

    private static void assertInitializeVersions(Map<String, Object> nodeResponse, Map<String, Object> javaResponse) {
        assertEquals("2.2.1", serverVersion(nodeResponse), "Pinned Node oracle version changed");
        assertEquals(requiredProperty("mcdevMcpVersion"), serverVersion(javaResponse), "Java initialize version must come from the build");
    }

    private static String serverVersion(Map<String, Object> response) {
        return Objects.toString(map(map(response.get("result")).get("serverInfo")).get("version"));
    }

    private static void assertEquivalent(Scenario scenario, Map<String, Object> nodeResponse, Map<String, Object> javaResponse, Path nodeRoot, Path javaRoot, int nodePort, int javaPort) throws IOException {
        Map<String, Object> normalizedNode = normalize(nodeResponse, scenario, nodeRoot, nodePort);
        Map<String, Object> normalizedJava = normalize(javaResponse, scenario, javaRoot, javaPort);
        String difference = firstDifference(normalizedNode, normalizedJava, "");
        if (difference == null) {
            return;
        }
        Path report = writeReport(scenario, nodeResponse, javaResponse, normalizedNode, normalizedJava, difference);
        fail("MCP parity mismatch for '" + scenario.label() + "' at " + difference + ". Report: " + report);
    }

    private static void assertTypedCatalogUpgrade(Map<String, Object> nodeResponse, Map<String, Object> javaResponse) {
        List<?> nodeTools = catalogTools(nodeResponse);
        List<?> javaTools = catalogTools(javaResponse);
        assertEquals(nodeTools.size(), javaTools.size(), "tools/list size changed");
        var exercisedDefaults = new LinkedHashSet<String>();
        var exercisedEmptyRequired = new LinkedHashSet<String>();
        var exercisedLimitDescriptions = new LinkedHashSet<String>();
        var exercisedLimitTypes = new LinkedHashSet<String>();
        var nodeAdditionalProperties = new LinkedHashSet<String>();
        var javaAdditionalProperties = new LinkedHashSet<String>();
        var exercisedRuntimeTools = new LinkedHashSet<String>();
        for (int index = 0; index < nodeTools.size(); index++) {
            Map<String, Object> nodeTool = map(nodeTools.get(index));
            Map<String, Object> javaTool = map(javaTools.get(index));
            String name = Objects.toString(nodeTool.get("name"));
            assertEquals(name, javaTool.get("name"), "Tool order/name changed at index " + index);
            if (RUNTIME_TOOLS.contains(name)) {
                assertTrue(exercisedRuntimeTools.add(name), "Runtime tool was asserted more than once: " + name);
                switch (name) {
                    case "mc_connect" ->
                            assertConnectCatalogSchemas(nodeTool, javaTool, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
                    case "mc_execute" -> {
                        assertNonRecordVideoCatalogEntry(nodeTool, javaTool);
                        assertExecuteCatalogSchemas(nodeTool, javaTool, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
                    }
                    case "mc_snapshot" ->
                            assertSnapshotCatalogSchemas(nodeTool, javaTool, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
                    case "mc_screenshot" ->
                            assertScreenshotCatalogSchemas(nodeTool, javaTool, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
                    case "mc_record_video" -> {
                        assertEquals(nodeTool.keySet(), javaTool.keySet(), "mc_record_video metadata keys changed");
                        assertRecordVideoCatalogDescriptions(nodeTool, javaTool);
                        assertEquals(withoutDescription(withoutSchema(nodeTool)), withoutDescription(withoutSchema(javaTool)), "Only the approved mc_record_video description may differ");
                        assertRecordVideoCatalogSchemas(nodeTool, javaTool, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
                    }
                    case "mc_nearby_entities", "mc_entity_details" ->
                            assertEntityCatalogSchemas(nodeTool, javaTool, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
                    case "mc_nearby_blocks", "mc_block_details" ->
                            assertBlockCatalogSchemas(nodeTool, javaTool, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
                    case "mc_looked_at_entity", "mc_chat_history", "mc_screen_inspect" ->
                            assertQueryCatalogSchemas(nodeTool, javaTool, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
                    case "mc_set_entity_glow", "mc_set_block_glow", "mc_clear_block_glow" ->
                            assertGlowCatalogSchemas(nodeTool, javaTool, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
                    case "mc_get_item_texture", "mc_get_item_texture_by_id" ->
                            assertItemTextureCatalogEntry(nodeTool, javaTool, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
                    case "mc_get_entity_item_texture" ->
                            assertEntityItemTextureCatalogEntry(nodeTool, javaTool, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
                    case "mc_join_server", "mc_leave_server", "mc_wait_until_in_world", "mc_quit_client",
                         "mc_wait_for_bridge" ->
                            assertSessionCatalogSchemas(nodeTool, javaTool, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
                    case "mc_script_logs" ->
                            assertScriptLogsCatalogSchemas(nodeTool, javaTool, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
                    case "mc_run_command" ->
                            assertRunCommandCatalogSchemas(nodeTool, javaTool, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
                    default -> throw new AssertionError("Unasserted runtime tool: " + name);
                }
                continue;
            }
            assertStaticLimitTypes(nodeTool, javaTool, name, exercisedLimitTypes);
            assertStaticLimitDescriptions(nodeTool, javaTool, name, exercisedLimitDescriptions);
            assertNonRecordVideoCatalogEntry(nodeTool, javaTool);
            assertEquals(normalizeTypedSchema(nodeTool.get("inputSchema"), name, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties), normalizeTypedSchema(javaTool.get("inputSchema"), name, exercisedDefaults, exercisedEmptyRequired, javaAdditionalProperties), "Unexpected typed schema drift for " + name);
        }
        assertEquals(RUNTIME_TOOLS, exercisedRuntimeTools, "Every runtime catalog schema must use an explicit family assertion");
        assertEquals(TYPED_CATALOG_DEFAULTS, exercisedDefaults, "Typed catalog default allowlist must be exercised exactly");
        assertEquals(TYPED_CATALOG_EMPTY_REQUIRED, exercisedEmptyRequired, "Typed catalog empty-required allowlist must be exercised exactly");
        assertEquals(TYPED_CATALOG_LIMIT_TYPE_DIFFS.keySet(), exercisedLimitTypes, "Typed catalog limit-type allowlist must be exercised exactly");
        assertEquals(TYPED_CATALOG_LIMIT_DESCRIPTION_DIFFS.keySet(), exercisedLimitDescriptions, "Typed catalog limit-description allowlist must be exercised exactly");
        assertEquals(Set.of(), nodeAdditionalProperties, "Pinned Node must not add the Java-only additionalProperties paths");
        assertEquals(TYPED_CATALOG_ADDITIONAL_PROPERTIES, javaAdditionalProperties, "Java typed catalog additionalProperties allowlist must be exercised exactly");
    }

    private static void assertItemTextureCatalogEntry(Map<String, Object> nodeTool, Map<String, Object> javaTool, Set<String> exercisedDefaults, Set<String> exercisedEmptyRequired, Set<String> nodeAdditionalProperties, Set<String> javaAdditionalProperties) {
        String name = (String) nodeTool.get("name");
        assertEquals(withoutSchema(nodeTool), withoutSchema(javaTool), "Only the reviewed " + name + " input schema may differ");
        if (name.equals("mc_get_item_texture")) {
            Map<String, Object> expectedNode = object("type", "object", "properties", object("slot", object("type", "number", "description", "Inventory slot index (0-35 main inv, 36-39 armor, 40 offhand).")), "required", List.of("slot"));
            Map<String, Object> expectedJava = object("type", "object", "properties", object("slot", object("type", "integer", "minimum", BigDecimal.ZERO, "maximum", BigDecimal.valueOf(40), "description", "Inventory slot index (0-35 main inventory, 36 feet, 37 legs, 38 chest, 39 head, 40 offhand).")), "required", List.of("slot"), "additionalProperties", false);
            assertExactTypedCatalogSchemas(nodeTool, javaTool, expectedNode, expectedJava, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
            return;
        }
        Map<String, Object> expected = object("type", "object", "properties", object("itemId", object("type", "string", "description", "Registry id like \"minecraft:diamond\".")), "required", List.of("itemId"));
        Map<String, Object> expectedJava = object("type", "object", "properties", object("itemId", object("type", "string", "description", "Registry id like \"minecraft:diamond\".")), "required", List.of("itemId"), "additionalProperties", false);
        assertExactTypedCatalogSchemas(nodeTool, javaTool, expected, expectedJava, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
    }

    private static void assertNonRecordVideoCatalogEntry(Map<String, Object> nodeTool, Map<String, Object> javaTool) {
        String name = Objects.toString(nodeTool.get("name"));
        if (!name.equals("mc_execute")) {
            assertEquals(withoutSchema(nodeTool), withoutSchema(javaTool), "Only approved typed schema metadata may change for non-record-video tools");
            return;
        }
        String nodeDescription = Objects.toString(nodeTool.get("description"));
        String javaDescription = Objects.toString(javaTool.get("description"));
        assertTrue(nodeDescription.contains(LEGACY_EXECUTE_TIMEOUT_PROSE), "Pinned Node execute description must retain timeoutMs prose");
        assertEquals(nodeDescription.replace(LEGACY_EXECUTE_TIMEOUT_PROSE, JAVA_EXECUTE_TIMEOUT_PROSE), javaDescription, "Only the approved execute timeout prose may differ");
        assertEquals(withoutDescription(withoutSchema(nodeTool)), withoutDescription(withoutSchema(javaTool)), "Only the approved execute timeout prose may differ");
    }

    private static void assertConnectCatalogSchemas(Map<String, Object> nodeTool, Map<String, Object> javaTool, Set<String> exercisedDefaults, Set<String> exercisedEmptyRequired, Set<String> nodeAdditionalProperties, Set<String> javaAdditionalProperties) {
        Map<String, Object> expectedNode = object("type", "object", "properties", object("port", object("type", "number", "description", "WebSocket port. Default: scan 9876-9886"), "reset", object("type", "boolean", "description", "Disconnect and clear state before connecting (for switching instances)")), "required", List.of());
        Map<String, Object> expectedJava = object("type", "object", "properties", object("port", object("type", "integer", "description", "WebSocket port. Default: scan 9876-9886", "minimum", BigDecimal.ONE, "maximum", new BigDecimal("65535")), "reset", object("type", "boolean", "description", "Disconnect and clear state before connecting (for switching instances)", "default", false)), "additionalProperties", false);
        assertExactTypedCatalogSchemas(nodeTool, javaTool, expectedNode, expectedJava, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
    }

    private static void assertScreenshotCatalogSchemas(Map<String, Object> nodeTool, Map<String, Object> javaTool, Set<String> exercisedDefaults, Set<String> exercisedEmptyRequired, Set<String> nodeAdditionalProperties, Set<String> javaAdditionalProperties) {
        Map<String, Object> expectedNode = object("type", "object", "properties", object("downscale", object("type", "number", "description", "Integer downscale factor. 1 = full window resolution. 2 = half each axis (default)."), "quality", object("type", "number", "description", "JPEG quality in [0.05, 1.0]. Default: 0.75.")), "required", List.of());
        Map<String, Object> expectedJava = object("type", "object", "properties", object("downscale", object("type", "integer", "description", "Integer downscale factor. 1 = full window resolution. 2 = half each axis (default).", "minimum", BigDecimal.ONE, "default", new java.math.BigInteger("2")), "quality", object("type", "number", "description", "JPEG quality in [0.05, 1.0]. Default: 0.75.", "minimum", new BigDecimal("0.05"), "maximum", BigDecimal.ONE, "default", new BigDecimal("0.75"))), "additionalProperties", false);
        assertExactTypedCatalogSchemas(nodeTool, javaTool, expectedNode, expectedJava, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
    }

    private static void assertQueryCatalogSchemas(Map<String, Object> nodeTool, Map<String, Object> javaTool, Set<String> exercisedDefaults, Set<String> exercisedEmptyRequired, Set<String> nodeAdditionalProperties, Set<String> javaAdditionalProperties) {
        String name = (String) nodeTool.get("name");
        Map<String, Object> expectedNode;
        Map<String, Object> expectedJava;
        switch (name) {
            case "mc_looked_at_entity" -> {
                expectedNode = object("type", "object", "properties", object("range", object("type", "number", "description", "Raycast distance in blocks. Default 64.")), "required", List.of());
                expectedJava = object("type", "object", "properties", object("range", object("type", "number", "description", "Raycast distance in blocks. Default 64.", "minimum", BigDecimal.ZERO, "default", new BigDecimal("64"))), "additionalProperties", false);
            }
            case "mc_chat_history" -> {
                expectedNode = object("type", "object", "properties", object("limit", object("type", "number", "description", "Max messages returned. Default 50."), "includeJson", object("type", "boolean", "description", "Include the Component as JSON for each message. Default false.")), "required", List.of());
                expectedJava = object("type", "object", "properties", object("limit", object("type", "integer", "description", "Max messages returned. Default 50.", "minimum", BigDecimal.ZERO, "default", new java.math.BigInteger("50")), "includeJson", object("type", "boolean", "description", "Include the Component as JSON for each message. Default false.", "default", false)), "additionalProperties", false);
            }
            case "mc_screen_inspect" -> {
                expectedNode = object("type", "object", "properties", object("includeIcons", object("type", "boolean", "description", "Render each unique item's icon and attach as an icons map. Default false.")), "required", List.of());
                expectedJava = object("type", "object", "properties", object("includeIcons", object("type", "boolean", "description", "Render each unique item's icon and attach as an icons map. Default false.", "default", false)), "additionalProperties", false);
            }
            default -> throw new AssertionError("Unexpected query tool: " + name);
        }
        assertExactTypedCatalogSchemas(nodeTool, javaTool, expectedNode, expectedJava, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
    }

    private static void assertExecuteCatalogSchemas(Map<String, Object> nodeTool, Map<String, Object> javaTool, Set<String> exercisedDefaults, Set<String> exercisedEmptyRequired, Set<String> nodeAdditionalProperties, Set<String> javaAdditionalProperties) {
        Map<String, Object> expectedNode = object("type", "object", "properties", object("code", object("type", "string", "description", "Groovy code to execute"), "timeoutMs", object("type", "integer", "description", "Optional per-call execution deadline in milliseconds. Range 1000-300000, default 10000 (10s). Use a longer value for bulk reflection or heavy file I/O.", "minimum", 1000, "maximum", 300000)), "required", List.of("code"));
        Map<String, Object> expectedJava = object("type", "object", "properties", object("code", object("type", "string", "description", "Groovy code to execute"), "timeoutSeconds", object("type", "number", "description", "Optional per-call execution deadline in seconds. Range 1-300, default 10 (10s). Use a longer value for bulk reflection or heavy file I/O.", "minimum", BigDecimal.ONE, "maximum", new BigDecimal("300"), "default", new BigDecimal("10"))), "required", List.of("code"), "additionalProperties", false);
        assertExactTypedCatalogSchemas(nodeTool, javaTool, expectedNode, expectedJava, false, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
    }

    private static void assertSnapshotCatalogSchemas(Map<String, Object> nodeTool, Map<String, Object> javaTool, Set<String> exercisedDefaults, Set<String> exercisedEmptyRequired, Set<String> nodeAdditionalProperties, Set<String> javaAdditionalProperties) {
        Map<String, Object> expectedNode = object("type", "object", "properties", object(), "required", List.of());
        Map<String, Object> expectedJava = object("type", "object", "properties", object(), "additionalProperties", false);
        assertExactTypedCatalogSchemas(nodeTool, javaTool, expectedNode, expectedJava, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
    }

    private static void assertEntityCatalogSchemas(Map<String, Object> nodeTool, Map<String, Object> javaTool, Set<String> exercisedDefaults, Set<String> exercisedEmptyRequired, Set<String> nodeAdditionalProperties, Set<String> javaAdditionalProperties) {
        String name = Objects.toString(nodeTool.get("name"));
        Map<String, Object> expectedNode;
        Map<String, Object> expectedJava;
        switch (name) {
            case "mc_nearby_entities" -> {
                expectedNode = object("type", "object", "properties", object("range", object("type", "number", "description", "Search radius in blocks. Default 64."), "limit", object("type", "number", "description", "Max entries returned. Default 100."), "includeIcons", object("type", "boolean", "description", "Render each unique primaryEquipment item's icon. Default false.")), "required", List.of());
                expectedJava = object("type", "object", "properties", object("range", object("type", "number", "description", "Search radius in blocks. Default 64.", "minimum", BigDecimal.ZERO, "default", new BigDecimal("64")), "limit", object("type", "integer", "description", "Max entries returned. Default 100.", "minimum", BigDecimal.ZERO, "default", new java.math.BigInteger("100")), "includeIcons", object("type", "boolean", "description", "Render each unique primaryEquipment item's icon. Default false.", "default", false)), "additionalProperties", false);
            }
            case "mc_entity_details" -> {
                expectedNode = object("type", "object", "properties", object("entityId", object("type", "number", "description", "Entity id from mc_nearby_entities or mc_looked_at_entity.")), "required", List.of("entityId"));
                expectedJava = object("type", "object", "properties", object("entityId", object("type", "integer", "description", "Entity id from mc_nearby_entities or mc_looked_at_entity.")), "required", List.of("entityId"), "additionalProperties", false);
            }
            default -> throw new AssertionError("Unexpected entity tool: " + name);
        }
        assertExactTypedCatalogSchemas(nodeTool, javaTool, expectedNode, expectedJava, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
    }

    private static void assertBlockCatalogSchemas(Map<String, Object> nodeTool, Map<String, Object> javaTool, Set<String> exercisedDefaults, Set<String> exercisedEmptyRequired, Set<String> nodeAdditionalProperties, Set<String> javaAdditionalProperties) {
        String name = Objects.toString(nodeTool.get("name"));
        Map<String, Object> expectedNode;
        Map<String, Object> expectedJava;
        switch (name) {
            case "mc_nearby_blocks" -> {
                expectedNode = object("type", "object", "properties", object("range", object("type", "number", "description", "Search radius in blocks. Default 16."), "limit", object("type", "number", "description", "Max entries returned. Default 100.")), "required", List.of());
                expectedJava = object("type", "object", "properties", object("range", object("type", "number", "description", "Search radius in blocks. Default 16.", "minimum", BigDecimal.ZERO, "default", new BigDecimal("16")), "limit", object("type", "integer", "description", "Max entries returned. Default 100.", "minimum", BigDecimal.ZERO, "default", new java.math.BigInteger("100"))), "additionalProperties", false);
            }
            case "mc_block_details" -> {
                expectedNode = object("type", "object", "properties", object("x", object("type", "number"), "y", object("type", "number"), "z", object("type", "number")), "required", List.of("x", "y", "z"));
                Map<String, Object> position = object("type", "object", "properties", object("x", object("type", "integer"), "y", object("type", "integer"), "z", object("type", "integer")), "required", List.of("x", "y", "z"), "additionalProperties", false);
                expectedJava = object("type", "object", "properties", object("position", position), "required", List.of("position"), "additionalProperties", false);
            }
            default -> throw new AssertionError("Unexpected block tool: " + name);
        }
        assertExactTypedCatalogSchemas(nodeTool, javaTool, expectedNode, expectedJava, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
    }

    private static void assertGlowCatalogSchemas(Map<String, Object> nodeTool, Map<String, Object> javaTool, Set<String> exercisedDefaults, Set<String> exercisedEmptyRequired, Set<String> nodeAdditionalProperties, Set<String> javaAdditionalProperties) {
        String name = Objects.toString(nodeTool.get("name"));
        Map<String, Object> expectedNode;
        Map<String, Object> expectedJava;
        switch (name) {
            case "mc_set_entity_glow" -> {
                expectedNode = object("type", "object", "properties", object("entityId", object("type", "number", "description", "Entity id from mc_nearby_entities."), "glow", object("type", "boolean", "description", "true to outline, false to remove.")), "required", List.of("entityId", "glow"));
                expectedJava = object("type", "object", "properties", object("entityId", object("type", "integer", "description", "Entity id from mc_nearby_entities."), "glow", object("type", "boolean", "description", "true to outline, false to remove.")), "required", List.of("entityId", "glow"), "additionalProperties", false);
            }
            case "mc_set_block_glow" -> {
                expectedNode = object("type", "object", "properties", object("x", object("type", "number"), "y", object("type", "number"), "z", object("type", "number"), "glow", object("type", "boolean", "description", "true to highlight, false to remove this position.")), "required", List.of("x", "y", "z", "glow"));
                Map<String, Object> position = object("type", "object", "properties", object("x", object("type", "integer"), "y", object("type", "integer"), "z", object("type", "integer")), "required", List.of("x", "y", "z"), "additionalProperties", false);
                expectedJava = object("type", "object", "properties", object("position", position, "glow", object("type", "boolean", "description", "true to highlight, false to remove this position.")), "required", List.of("position", "glow"), "additionalProperties", false);
            }
            case "mc_clear_block_glow" -> {
                expectedNode = object("type", "object", "properties", object(), "required", List.of());
                expectedJava = object("type", "object", "properties", object(), "additionalProperties", false);
            }
            default -> throw new AssertionError("Unexpected glow tool: " + name);
        }
        assertExactTypedCatalogSchemas(nodeTool, javaTool, expectedNode, expectedJava, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
    }

    private static void assertEntityItemTextureCatalogEntry(Map<String, Object> nodeTool, Map<String, Object> javaTool, Set<String> exercisedDefaults, Set<String> exercisedEmptyRequired, Set<String> nodeAdditionalProperties, Set<String> javaAdditionalProperties) {
        Map<String, Object> expectedNode = object("type", "object", "properties", object("entityId", object("type", "number"), "slot", object("type", "string", "enum", List.of("mainhand", "offhand", "head", "chest", "legs", "feet"))), "required", List.of("entityId", "slot"));
        Map<String, Object> expectedJava = object("type", "object", "properties", object("entityId", object("type", "integer"), "slot", object("type", "string", "enum", List.of("mainhand", "offhand", "feet", "legs", "chest", "head", "body", "saddle", "frame", "display"))), "required", List.of("entityId", "slot"), "additionalProperties", false);
        assertExactTypedCatalogSchemas(nodeTool, javaTool, expectedNode, expectedJava, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
    }

    private static void assertScriptLogsCatalogSchemas(Map<String, Object> nodeTool, Map<String, Object> javaTool, Set<String> exercisedDefaults, Set<String> exercisedEmptyRequired, Set<String> nodeAdditionalProperties, Set<String> javaAdditionalProperties) {
        Map<String, Object> expectedNode = object("type", "object", "properties", object("mode", object("type", "string", "enum", List.of("errors", "stats", "paths"), "description", "What to show: 'errors' (recent failures), 'stats' (error patterns), 'paths' (file locations)"), "limit", object("type", "number", "description", "Number of entries to show (for 'errors' mode). Default: 20")), "required", List.of());
        Map<String, Object> expectedJava = object("type", "object", "properties", object("mode", object("type", "string", "enum", List.of("errors", "stats", "paths"), "description", "What to show: 'errors' (recent failures), 'stats' (error patterns), 'paths' (file locations)", "default", "errors"), "limit", object("type", "integer", "minimum", BigDecimal.ONE, "description", "Number of entries to show (for 'errors' mode). Default: 20", "default", new java.math.BigInteger("20"))), "additionalProperties", false);
        assertExactTypedCatalogSchemas(nodeTool, javaTool, expectedNode, expectedJava, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
    }

    private static void assertRunCommandCatalogSchemas(Map<String, Object> nodeTool, Map<String, Object> javaTool, Set<String> exercisedDefaults, Set<String> exercisedEmptyRequired, Set<String> nodeAdditionalProperties, Set<String> javaAdditionalProperties) {
        Map<String, Object> expected = object("type", "object", "properties", object("command", object("type", "string", "description", "The command to run")), "required", List.of("command"));
        Map<String, Object> expectedJava = object("type", "object", "properties", object("command", object("type", "string", "description", "The command to run")), "required", List.of("command"), "additionalProperties", false);
        assertExactTypedCatalogSchemas(nodeTool, javaTool, expected, expectedJava, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
    }

    private static void assertSessionCatalogSchemas(Map<String, Object> nodeTool, Map<String, Object> javaTool, Set<String> exercisedDefaults, Set<String> exercisedEmptyRequired, Set<String> nodeAdditionalProperties, Set<String> javaAdditionalProperties) {
        String name = Objects.toString(nodeTool.get("name"));
        Map<String, Object> expectedNode;
        Map<String, Object> expectedJava;
        switch (name) {
            case "mc_join_server" -> {
                expectedNode = object("type", "object", "properties", object("address", object("type", "string", "description", "Server address, \"host\" or \"host:port\" (e.g. \"localhost:25565\")"), "acceptResourcePacks", object("type", "boolean", "description", "Pre-accept the server resource pack. Default true."), "wait", object("type", "boolean", "description", "Poll until in-world / disconnected before returning. Default true."), "timeoutSeconds", object("type", "number", "description", "How long to wait for the join to complete. Default 60.")), "required", List.of("address"));
                expectedJava = object("type", "object", "properties", object("address", object("type", "string", "description", "Server address, \"host\" or \"host:port\" (e.g. \"localhost:25565\")"), "acceptResourcePacks", object("type", "boolean", "description", "Pre-accept the server resource pack. Default true.", "default", true), "wait", object("type", "boolean", "description", "Poll until in-world / disconnected before returning. Default true.", "default", true), "timeoutSeconds", object("type", "number", "description", "How long to wait for the join to complete. Default 60.", "minimum", BigDecimal.ZERO, "default", new BigDecimal("60"))), "required", List.of("address"), "additionalProperties", false);
            }
            case "mc_leave_server" -> {
                expectedNode = object("type", "object", "properties", object(), "required", List.of());
                expectedJava = object("type", "object", "properties", object(), "additionalProperties", false);
            }
            case "mc_wait_until_in_world" -> {
                expectedNode = object("type", "object", "properties", object("timeoutSeconds", object("type", "number", "description", "Give up after this many seconds. Default 60."), "requireAbsenceFirst", object("type", "boolean", "description", "Only count a player snapshot as in-world after the old session visibly dropped (one successful snapshot without a player). Use when a join was issued from inside a world. Default false.")), "required", List.of());
                expectedJava = object("type", "object", "properties", object("timeoutSeconds", object("type", "number", "description", "Give up after this many seconds. Default 60.", "minimum", BigDecimal.ZERO, "default", new BigDecimal("60")), "requireAbsenceFirst", object("type", "boolean", "description", "Only count a player snapshot as in-world after the old session visibly dropped (one successful snapshot without a player). Use when a join was issued from inside a world. Default false.", "default", false)), "additionalProperties", false);
            }
            case "mc_quit_client" -> {
                expectedNode = object("type", "object", "properties", object("waitForExit", object("type", "boolean", "description", "Wait until the client is actually gone — bridge port closed, then the client process exited (when its PID could be resolved) — before returning. Default true."), "timeoutSeconds", object("type", "number", "description", "How long to wait for the whole shutdown (port close + process exit). Default 30.")), "required", List.of());
                expectedJava = object("type", "object", "properties", object("waitForExit", object("type", "boolean", "description", "Wait until the client is actually gone — bridge port closed, then the client process exited (when its PID could be resolved) — before returning. Default true.", "default", true), "timeoutSeconds", object("type", "number", "description", "How long to wait for the whole shutdown (port close + process exit). Default 30.", "minimum", BigDecimal.ZERO, "default", new BigDecimal("30"))), "additionalProperties", false);
            }
            case "mc_wait_for_bridge" -> {
                expectedNode = object("type", "object", "properties", object("expectedVersion", object("type", "string", "description", "Only accept an instance reporting this Minecraft version (e.g. \"1.21.11\"). Overrides the identity remembered from the previous connection — use when deliberately switching instances."), "timeoutSeconds", object("type", "number", "description", "Give up after this many seconds. Default 120.")), "required", List.of());
                expectedJava = object("type", "object", "properties", object("expectedVersion", object("type", "string", "description", "Only accept an instance reporting this Minecraft version (e.g. \"1.21.11\"). Overrides the identity remembered from the previous connection — use when deliberately switching instances."), "timeoutSeconds", object("type", "number", "description", "Give up after this many seconds. Default 120.", "minimum", BigDecimal.ZERO, "default", new BigDecimal("120"))), "additionalProperties", false);
            }
            default -> throw new AssertionError("Unexpected session tool: " + name);
        }
        assertExactTypedCatalogSchemas(nodeTool, javaTool, expectedNode, expectedJava, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
    }

    private static void assertExactTypedCatalogSchemas(Map<String, Object> nodeTool, Map<String, Object> javaTool, Map<String, Object> expectedNode, Map<String, Object> expectedJava, Set<String> exercisedDefaults, Set<String> exercisedEmptyRequired, Set<String> nodeAdditionalProperties, Set<String> javaAdditionalProperties) {
        assertExactTypedCatalogSchemas(nodeTool, javaTool, expectedNode, expectedJava, true, exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
    }

    private static void assertExactTypedCatalogSchemas(Map<String, Object> nodeTool, Map<String, Object> javaTool, Map<String, Object> expectedNode, Map<String, Object> expectedJava, boolean metadataExact, Set<String> exercisedDefaults, Set<String> exercisedEmptyRequired, Set<String> nodeAdditionalProperties, Set<String> javaAdditionalProperties) {
        String name = Objects.toString(nodeTool.get("name"));
        if (metadataExact) {
            assertEquals(withoutSchema(nodeTool), withoutSchema(javaTool), "Only the reviewed " + name + " input schema may differ");
        }
        assertEquals(canonicalJson(expectedNode), canonicalJson(inputSchema(nodeTool)), "Pinned Node " + name + " schema changed");
        assertEquals(canonicalJson(expectedJava), canonicalJson(inputSchema(javaTool)), "Java " + name + " schema must be the exact typed schema");
        exerciseTypedCatalogDifferences(name, inputSchema(nodeTool), inputSchema(javaTool), exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
    }

    private static void exerciseTypedCatalogDifferences(String toolName, Map<String, Object> nodeSchema, Map<String, Object> javaSchema, Set<String> exercisedDefaults, Set<String> exercisedEmptyRequired, Set<String> nodeAdditionalProperties, Set<String> javaAdditionalProperties) {
        for (String key : TYPED_CATALOG_DEFAULTS) {
            if (key.startsWith(toolName + ":")) {
                String path = key.substring(toolName.length() + 1).substring("inputSchema.".length());
                assertEquals(MISSING_SCHEMA_VALUE, schemaValue(nodeSchema, path), "Pinned Node unexpectedly publishes a default at " + key);
                assertNotEquals(MISSING_SCHEMA_VALUE, schemaValue(javaSchema, path), "Java schema is missing the approved default at " + key);
                exercisedDefaults.add(key);
            }
        }
        for (String key : TYPED_CATALOG_EMPTY_REQUIRED) {
            if (key.startsWith(toolName + ":")) {
                String path = key.substring(toolName.length() + 1).substring("inputSchema.".length());
                assertEquals(List.of(), schemaValue(nodeSchema, path), "Pinned Node must retain the empty required array at " + key);
                assertEquals(MISSING_SCHEMA_VALUE, schemaValue(javaSchema, path), "Java schema must omit the empty required array at " + key);
                exercisedEmptyRequired.add(key);
            }
        }
        for (String key : TYPED_CATALOG_ADDITIONAL_PROPERTIES) {
            if (key.startsWith(toolName + ":")) {
                String path = key.substring(toolName.length() + 1).substring("inputSchema.".length());
                assertEquals(MISSING_SCHEMA_VALUE, schemaValue(nodeSchema, path), "Pinned Node unexpectedly publishes additionalProperties at " + key);
                assertEquals(false, schemaValue(javaSchema, path), "Java schema must close the approved object at " + key);
                javaAdditionalProperties.add(key);
            }
        }
        assertTrue(nodeAdditionalProperties.isEmpty(), "Pinned Node must not publish Java-only additionalProperties paths");
    }

    private static final Object MISSING_SCHEMA_VALUE = new Object();

    private static Object schemaValue(Object schema, String path) {
        Object current = schema;
        for (String segment : path.split("\\.")) {
            int bracket = segment.indexOf('[');
            String key = bracket < 0 ? segment : segment.substring(0, bracket);
            if (!key.isEmpty()) {
                if (!(current instanceof Map<?, ?> object) || !object.containsKey(key)) {
                    return MISSING_SCHEMA_VALUE;
                }
                current = object.get(key);
            }
            while (bracket >= 0) {
                int end = segment.indexOf(']', bracket);
                if (!(current instanceof List<?> list) || end < 0) {
                    return MISSING_SCHEMA_VALUE;
                }
                int index;
                try {
                    index = Integer.parseInt(segment.substring(bracket + 1, end));
                } catch (NumberFormatException exception) {
                    return MISSING_SCHEMA_VALUE;
                }
                if (index < 0 || index >= list.size()) {
                    return MISSING_SCHEMA_VALUE;
                }
                current = list.get(index);
                bracket = segment.indexOf('[', end + 1);
            }
        }
        return current;
    }

    private static Map<String, Object> inputSchema(Map<String, Object> tool) {
        return map(tool.get("inputSchema"));
    }

    private static Map<String, Object> withoutDescription(Map<String, Object> tool) {
        var result = new LinkedHashMap<>(tool);
        result.remove("description");
        return result;
    }

    private static Map<String, Object> withoutSchema(Map<String, Object> tool) {
        var result = new LinkedHashMap<>(tool);
        result.remove("inputSchema");
        return result;
    }

    private static void assertStaticLimitDescriptions(Map<String, Object> nodeTool, Map<String, Object> javaTool, String toolName, Set<String> exercisedLimitDescriptions) {
        for (Map.Entry<String, LimitDescriptionPair> entry : TYPED_CATALOG_LIMIT_DESCRIPTION_DIFFS.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(toolName + ":")) {
                continue;
            }
            String path = key.substring(toolName.length() + 1).substring("inputSchema.".length());
            LimitDescriptionPair pair = entry.getValue();
            assertEquals(pair.node(), schemaValue(inputSchema(nodeTool), path), "Pinned Node limit description changed at " + key);
            assertEquals(pair.java(), schemaValue(inputSchema(javaTool), path), "Java limit description changed at " + key);
            assertTrue(exercisedLimitDescriptions.add(key), "Static limit description was asserted more than once: " + key);
        }
    }

    private static void assertStaticLimitTypes(Map<String, Object> nodeTool, Map<String, Object> javaTool, String toolName, Set<String> exercisedLimitTypes) {
        for (Map.Entry<String, LimitTypePair> entry : TYPED_CATALOG_LIMIT_TYPE_DIFFS.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(toolName + ":")) {
                continue;
            }
            String path = key.substring(toolName.length() + 1).substring("inputSchema.".length());
            LimitTypePair pair = entry.getValue();
            assertEquals(pair.node(), schemaValue(inputSchema(nodeTool), path), "Pinned Node limit type changed at " + key);
            assertEquals(pair.java(), schemaValue(inputSchema(javaTool), path), "Java limit type changed at " + key);
            assertTrue(exercisedLimitTypes.add(key), "Static limit type was asserted more than once: " + key);
        }
    }

    private static Object normalizeTypedSchema(Object value, String toolName, Set<String> exercisedDefaults, Set<String> exercisedEmptyRequired, Set<String> exercisedAdditionalProperties) {
        return normalizeTypedSchema(value, toolName, "inputSchema", exercisedDefaults, exercisedEmptyRequired, exercisedAdditionalProperties);
    }

    private static Object normalizeTypedSchema(Object value, String toolName, String path, Set<String> exercisedDefaults, Set<String> exercisedEmptyRequired, Set<String> exercisedAdditionalProperties) {
        if (value instanceof Map<?, ?> object) {
            var normalized = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : object.entrySet()) {
                String key = (String) entry.getKey();
                Object child = entry.getValue();
                String childPath = path + "." + key;
                String allowlistKey = toolName + ":" + childPath;
                if (key.equals("default") && TYPED_CATALOG_DEFAULTS.contains(allowlistKey)) {
                    exercisedDefaults.add(allowlistKey);
                    continue;
                }
                if (key.equals("required") && child instanceof List<?> list && list.isEmpty() && TYPED_CATALOG_EMPTY_REQUIRED.contains(allowlistKey)) {
                    exercisedEmptyRequired.add(allowlistKey);
                    continue;
                }
                if (key.equals("additionalProperties") && Boolean.FALSE.equals(child) && TYPED_CATALOG_ADDITIONAL_PROPERTIES.contains(allowlistKey)) {
                    exercisedAdditionalProperties.add(allowlistKey);
                    continue;
                }
                if (key.equals("description") && TYPED_CATALOG_LIMIT_DESCRIPTION_DIFFS.containsKey(allowlistKey)) {
                    continue;
                }
                if (key.equals("type") && TYPED_CATALOG_LIMIT_TYPE_DIFFS.containsKey(allowlistKey)) {
                    continue;
                }
                normalized.put(key, normalizeTypedSchema(child, toolName, childPath, exercisedDefaults, exercisedEmptyRequired, exercisedAdditionalProperties));
            }
            return normalized;
        }
        if (value instanceof List<?> list) {
            var normalized = new ArrayList<>(list.size());
            for (int index = 0; index < list.size(); index++) {
                normalized.add(normalizeTypedSchema(list.get(index), toolName, path + "[" + index + "]", exercisedDefaults, exercisedEmptyRequired, exercisedAdditionalProperties));
            }
            return normalized;
        }
        return value;
    }

    private static List<?> catalogTools(Map<String, Object> response) {
        return assertInstanceOf(List.class, map(response.get("result")).get("tools"), "tools/list result must contain tools");
    }

    private static void assertRecordVideoCatalogDescriptions(Map<String, Object> nodeTool, Map<String, Object> javaTool) {
        assertEquals(REVIEWED_NODE_RECORD_VIDEO_DESCRIPTION, nodeTool.get("description"), "Pinned Node record-video description changed");
        assertEquals(REVIEWED_JAVA_RECORD_VIDEO_DESCRIPTION, javaTool.get("description"), "Pinned Java record-video description changed");
    }

    private static void assertRecordVideoCatalogSchemas(Map<String, Object> nodeTool, Map<String, Object> javaTool, Set<String> exercisedDefaults, Set<String> exercisedEmptyRequired, Set<String> nodeAdditionalProperties, Set<String> javaAdditionalProperties) {
        Map<String, Object> nodeInterval = intervalSchema(nodeTool);
        Map<String, Object> javaInterval = intervalSchema(javaTool);
        Map<String, Object> expectedNodeSchema = object("type", "object", "properties", object("frames", object("type", "number", "description", "Number of frames to capture, 1..300. Required."), "interval", object("description", "\"frame\" for every render tick (~60 Hz), or milliseconds (number, >= 1). Default \"frame\". Recommended: 50–100 ms unless you specifically need sub-tick detail.", "oneOf", List.of(object("type", "string", "enum", List.of("frame")), object("type", "number", "minimum", BigDecimal.ONE))), "output", object("type", "string", "enum", List.of("grid", "frames"), "description", "\"grid\" (one composed JPEG, default) or \"frames\" (N separate JPEGs)."), "gridCols", object("type", "number", "description", "Columns in grid layout. Default ceil(sqrt(frames)). Only used in \"grid\" mode."), "downscale", object("type", "number", "description", "Integer downscale factor. Default 2 (half each axis)."), "quality", object("type", "number", "description", "JPEG quality in [0.05, 1.0]. Default 0.75. In \"grid\" mode applies once to the composed image.")), "required", List.of("frames"));
        Map<String, Object> expectedJavaSchema = object("type", "object", "properties", object("frames", object("type", "integer", "description", "Number of frames to capture, 1..300. Required.", "minimum", BigDecimal.ONE, "maximum", BigDecimal.valueOf(300)), "interval", object("description", "Use {kind:\"fixed\",intervalSeconds:0.05} for a fixed cadence, or {kind:\"frame\"} for every render tick (~60 Hz). Default {kind:\"frame\"}.", "oneOf", List.of(object("type", "object", "properties", object("kind", object("type", "string", "const", "frame")), "required", List.of("kind"), "additionalProperties", false), object("type", "object", "properties", object("kind", object("type", "string", "const", "fixed"), "intervalSeconds", object("type", "number", "minimum", new BigDecimal("0.001"))), "required", List.of("kind", "intervalSeconds"), "additionalProperties", false))), "output", object("type", "string", "enum", List.of("grid", "frames"), "description", "\"grid\" (one composed JPEG, default) or \"frames\" (N separate JPEGs).", "default", "grid"), "gridCols", object("type", "integer", "description", "Columns in grid layout. Default max(1, ceil(sqrt(frames))). Only used in \"grid\" mode.", "minimum", BigDecimal.ONE), "downscale", object("type", "integer", "description", "Integer downscale factor. Default 2 (half each axis).", "minimum", BigDecimal.ONE, "default", new java.math.BigInteger("2")), "quality", object("type", "number", "description", "JPEG quality in [0.05, 1.0]. Default 0.75. In \"grid\" mode applies once to the composed image.", "minimum", new BigDecimal("0.05"), "maximum", BigDecimal.ONE, "default", new BigDecimal("0.75"))), "required", List.of("frames"), "additionalProperties", false);
        assertEquals(canonicalJson(expectedNodeSchema), canonicalJson(inputSchema(nodeTool)), "Pinned Node record-video schema changed");
        assertEquals(canonicalJson(expectedJavaSchema), canonicalJson(inputSchema(javaTool)), "Java record-video schema must be the exact typed schema");
        Map<String, Object> expectedNode = object("description", "\"frame\" for every render tick (~60 Hz), or milliseconds (number, >= 1). Default \"frame\". Recommended: 50–100 ms unless you specifically need sub-tick detail.", "oneOf", List.of(object("type", "string", "enum", List.of("frame")), object("type", "number", "minimum", BigDecimal.ONE)));
        Map<String, Object> expectedJavaFrame = object("type", "object", "properties", object("kind", object("type", "string", "const", "frame")), "additionalProperties", false, "required", List.of("kind"));
        Map<String, Object> expectedJavaFixed = object("type", "object", "properties", object("kind", object("type", "string", "const", "fixed"), "intervalSeconds", object("type", "number", "minimum", new BigDecimal("0.001"))), "additionalProperties", false, "required", List.of("kind", "intervalSeconds"));
        Map<String, Object> expectedJava = object("oneOf", List.of(expectedJavaFrame, expectedJavaFixed), "description", "Use {kind:\"fixed\",intervalSeconds:0.05} for a fixed cadence, or {kind:\"frame\"} for every render tick (~60 Hz). Default {kind:\"frame\"}.");
        assertEquals(json(expectedNode), json(nodeInterval), "Pinned Node interval schema must remain exactly the reviewed legacy map");
        assertEquals(canonicalJson(expectedJava), canonicalJson(javaInterval), "Java interval schema must remain exactly the reviewed semantic map");
        exerciseTypedCatalogDifferences("mc_record_video", inputSchema(nodeTool), inputSchema(javaTool), exercisedDefaults, exercisedEmptyRequired, nodeAdditionalProperties, javaAdditionalProperties);
    }

    private static Map<String, Object> intervalSchema(Map<String, Object> tool) {
        return map(map(map(tool.get("inputSchema")).get("properties")).get("interval"));
    }

    private static Map<String, Object> object(Object... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("Object helper requires key/value pairs");
        }
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return result;
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException exception) {
            throw new AssertionError("Unable to serialize catalog schema", exception);
        }
    }

    private static String canonicalJson(Object value) {
        return json(canonicalJsonValue(value));
    }

    private static Object canonicalJsonValue(Object value) {
        if (value instanceof Map<?, ?> object) {
            var canonical = new java.util.TreeMap<String, Object>();
            for (Map.Entry<?, ?> entry : object.entrySet()) {
                canonical.put((String) entry.getKey(), canonicalJsonValue(entry.getValue()));
            }
            return canonical;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(DifferentialMcpTest::canonicalJsonValue).toList();
        }
        return value;
    }

    private static void assertDescriptorUpgrade(Scenario scenario, Map<String, Object> nodeResponse, Map<String, Object> javaResponse, Path nodeRoot, Path javaRoot, int nodePort, int javaPort) throws IOException {
        String expectedNode;
        String expectedJava;
        if (scenario.label().equals("static-refs-callers-descriptors")) {
            expectedNode = "Found 2 callers:\ncaller.Described.entry (line 11)\ncaller.Legacy.entry (line 12)\nTotal: 2 callers";
            expectedJava = "Found 2 callers:\ncaller.Described.entry()V (line 11)\ncaller.Legacy.entry (line 12)\nTotal: 2 callers";
        }
        else if (scenario.label().equals("static-refs-callees-descriptors")) {
            expectedNode = "Found 2 callees:\ncallee.First.work (line 21)\ncallee.Second.stop\nTotal: 2 callees";
            expectedJava = "Found 2 callees:\ncallee.First.work(Ljava/lang/String;)V (line 21)\ncallee.Second.stop\nTotal: 2 callees";
        }
        else {
            throw new IllegalArgumentException("Unknown descriptor-upgrade scenario: " + scenario.label());
        }
        assertEquals(expectedNode, toolText(nodeResponse), "Pinned Node descriptor rendering changed for " + scenario.label());
        assertEquals(expectedJava, toolText(javaResponse), "Java descriptor rendering must preserve the approved Task 7 improvement for " + scenario.label());

        Map<String, Object> normalizedNode = withApprovedToolText(normalize(nodeResponse, scenario, nodeRoot, nodePort), "$APPROVED_DESCRIPTOR_RENDERING");
        Map<String, Object> normalizedJava = withApprovedToolText(normalize(javaResponse, scenario, javaRoot, javaPort), "$APPROVED_DESCRIPTOR_RENDERING");
        String difference = firstDifference(normalizedNode, normalizedJava, "");
        if (difference != null) {
            Path report = writeReport(scenario, nodeResponse, javaResponse, normalizedNode, normalizedJava, difference);
            fail("MCP parity mismatch outside the approved descriptor text for '" + scenario.label() + "' at " + difference + ". Report: " + report);
        }
    }

    private static void assertJavaLauncherUpgrade(Scenario scenario, Map<String, Object> nodeResponse, Map<String, Object> javaResponse, Path nodeRoot, Path javaRoot, int nodePort, int javaPort) throws IOException {
        Map<String, Object> arguments = map(map(scenario.request().get("params")).get("arguments"));
        String version = Objects.toString(arguments.get("version"));
        String prefix = "Version " + version + " not initialized. STOP and ask the USER to run this command in their terminal:\n  ";
        String suffix = " init -v " + version + "\n\nThis will download, decompile, and index Minecraft " + version + " sources (including callgraph).";
        assertEquals(prefix + "node dist/cli.js" + suffix, toolText(nodeResponse), "Pinned Node launcher guidance changed for " + scenario.label());
        assertEquals(prefix + "java -jar mcdev-mcp-" + requiredProperty("mcdevMcpVersion") + ".jar" + suffix, toolText(javaResponse), "Java missing-cache guidance must use the distributable JAR launcher for " + scenario.label());

        Map<String, Object> normalizedNode = withApprovedToolText(normalize(nodeResponse, scenario, nodeRoot, nodePort), "$APPROVED_JAVA_LAUNCHER");
        Map<String, Object> normalizedJava = withApprovedToolText(normalize(javaResponse, scenario, javaRoot, javaPort), "$APPROVED_JAVA_LAUNCHER");
        String difference = firstDifference(normalizedNode, normalizedJava, "");
        if (difference != null) {
            Path report = writeReport(scenario, nodeResponse, javaResponse, normalizedNode, normalizedJava, difference);
            fail("MCP parity mismatch outside the approved Java launcher guidance for '" + scenario.label() + "' at " + difference + ". Report: " + report);
        }
    }

    private static void assertSdkInputValidationUpgrade(Scenario scenario, Map<String, Object> nodeResponse, Map<String, Object> javaResponse, Path nodeRoot, Path javaRoot, int nodePort, int javaPort) throws IOException {
        assertEquals("Error executing mc_search: query.toLowerCase is not a function", toolText(nodeResponse), "Pinned Node malformed-input behavior changed");
        assertEquals("Tool (mc_search) input validation failed: Validation failed: JSON schema validation errors: [/query: integer found, string expected]", toolText(javaResponse), "Java must reject malformed tool arguments at the SDK schema boundary");

        Map<String, Object> normalizedNode = withApprovedToolText(normalize(nodeResponse, scenario, nodeRoot, nodePort), "$APPROVED_SDK_INPUT_VALIDATION");
        Map<String, Object> normalizedJava = withApprovedToolText(normalize(javaResponse, scenario, javaRoot, javaPort), "$APPROVED_SDK_INPUT_VALIDATION");
        String difference = firstDifference(normalizedNode, normalizedJava, "");
        if (difference != null) {
            Path report = writeReport(scenario, nodeResponse, javaResponse, normalizedNode, normalizedJava, difference);
            fail("MCP parity mismatch outside the approved SDK input validation for '" + scenario.label() + "' at " + difference + ". Report: " + report);
        }
    }

    private static void assertCurrentGuideReferences(Scenario scenario, Map<String, Object> nodeResponse, Map<String, Object> javaResponse, Path nodeRoot, Path javaRoot, int nodePort, int javaPort) throws IOException {
        String nodeText = resourceText(nodeResponse);
        String javaText = resourceText(javaResponse);
        assertTrue(nodeText.contains("src/tools/runtime/session.ts"), "Pinned Node guide must retain its captured source reference");
        assertFalse(javaText.contains("src/tools/runtime/"), "Java guide must not name deleted runtime source paths");
        assertTrue(javaText.contains("dev.mcdevmcp.bridge.BridgeSession"), "Java guide must identify the current bridge implementation");
        assertTrue(javaText.contains("dev.mcdevmcp.tools.runtime.McExecuteTool"), "Java guide must identify the current execute handler");
        assertEquals(resourceText(McpContractTestSupport.readContract("resource-python-scripting.json")), javaText, "Java Python guide must match its reviewed distribution contract");
        assertNotEquals(nodeText, javaText, "Pinned Node and Java guide fixtures must continue exercising the approved distribution-guide difference");

        Map<String, Object> normalizedNode = withApprovedResourceText(normalize(nodeResponse, scenario, nodeRoot, nodePort), "$APPROVED_JAVA_GUIDE_REFERENCES");
        Map<String, Object> normalizedJava = withApprovedResourceText(normalize(javaResponse, scenario, javaRoot, javaPort), "$APPROVED_JAVA_GUIDE_REFERENCES");
        String difference = firstDifference(normalizedNode, normalizedJava, "");
        if (difference != null) {
            Path report = writeReport(scenario, nodeResponse, javaResponse, normalizedNode, normalizedJava, difference);
            fail("MCP parity mismatch outside the approved Java guide references for '" + scenario.label() + "' at " + difference + ". Report: " + report);
        }
    }

    private static void assertReviewedGuide(Scenario scenario, Map<String, Object> nodeResponse, Map<String, Object> javaResponse, Path nodeRoot, Path javaRoot, int nodePort, int javaPort) throws IOException {
        String nodeText = resourceText(nodeResponse);
        String javaText = resourceText(javaResponse);
        assertEquals(resourceText(McpContractTestSupport.readContract("resource-dev-loop.json")), javaText, "Java dev-loop guide must match its reviewed distribution contract");
        assertNotEquals(nodeText, javaText, "Pinned Node and Java guide fixtures must continue exercising the approved reviewed-guide difference");

        Map<String, Object> normalizedNode = withApprovedResourceText(normalize(nodeResponse, scenario, nodeRoot, nodePort), "$APPROVED_REVIEWED_GUIDE");
        Map<String, Object> normalizedJava = withApprovedResourceText(normalize(javaResponse, scenario, javaRoot, javaPort), "$APPROVED_REVIEWED_GUIDE");
        String difference = firstDifference(normalizedNode, normalizedJava, "");
        if (difference != null) {
            Path report = writeReport(scenario, nodeResponse, javaResponse, normalizedNode, normalizedJava, difference);
            fail("MCP parity mismatch outside the approved reviewed guide for '" + scenario.label() + "' at " + difference + ". Report: " + report);
        }
    }

    private static String toolText(Map<String, Object> response) {
        Object content = map(response.get("result")).get("content");
        List<?> items = assertInstanceOf(List.class, content, "tools/call result must contain content");
        assertEquals(1, items.size(), "Parity tool response must contain exactly one content item");
        return Objects.toString(map(items.getFirst()).get("text"));
    }

    private static String resourceText(Map<String, Object> response) {
        Object contents = map(response.get("result")).get("contents");
        List<?> items = assertInstanceOf(List.class, contents, "resources/read result must contain contents");
        assertEquals(1, items.size(), "Parity resource response must contain exactly one content item");
        return Objects.toString(map(items.getFirst()).get("text"));
    }

    private static Map<String, Object> withApprovedResourceText(Map<String, Object> response, String placeholder) {
        Map<String, Object> copy = map(response);
        Map<String, Object> result = map(copy.get("result"));
        List<?> contents = assertInstanceOf(List.class, result.get("contents"));
        List<Object> replacedContents = new ArrayList<>(contents);
        Map<String, Object> item = map(replacedContents.getFirst());
        item.put("text", placeholder);
        replacedContents.set(0, item);
        result.put("contents", replacedContents);
        copy.put("result", result);
        return copy;
    }

    private static Map<String, Object> withApprovedToolText(Map<String, Object> response, String placeholder) {
        Map<String, Object> copy = map(response);
        Map<String, Object> result = map(copy.get("result"));
        List<?> content = assertInstanceOf(List.class, result.get("content"));
        List<Object> replacedContent = new ArrayList<>(content);
        Map<String, Object> item = map(replacedContent.getFirst());
        item.put("text", placeholder);
        replacedContent.set(0, item);
        result.put("content", replacedContent);
        copy.put("result", result);
        return copy;
    }

    private static Map<String, Object> normalize(Map<String, Object> response, Scenario scenario, Path root, int port) {
        Map<String, Object> normalized = map(normalizeValue(response, root, port));
        if (normalized.containsKey("id")) {
            normalized.put("id", "$JSON_RPC_ID");
        }
        if (scenario.kind().equals("initialize")) {
            Map<String, Object> result = map(normalized.get("result"));
            Map<String, Object> serverInfo = map(result.get("serverInfo"));
            serverInfo.put("version", "$SERVER_VERSION");
            result.put("serverInfo", serverInfo);
            normalized.put("result", result);
        }
        return normalized;
    }

    private static Object normalizeValue(Object value, Path root, int port) {
        if (value instanceof Map<?, ?> object) {
            var normalized = new LinkedHashMap<String, Object>();
            object.forEach((key, child) -> normalized.put((String) key, normalizeValue(child, root, port)));
            return normalized;
        }
        if (value instanceof List<?> array) {
            return array.stream().map(child -> normalizeValue(child, root, port)).toList();
        }
        if (value instanceof String text) {
            return normalizeText(text, root, port);
        }
        return value;
    }

    private static boolean bridgeInvocationsEquivalent(List<ScriptedDebugBridge.Invocation> nodeInvocations, List<ScriptedDebugBridge.Invocation> javaInvocations) {
        if (nodeInvocations.size() != javaInvocations.size()) {
            return false;
        }
        for (int index = 0; index < nodeInvocations.size(); index++) {
            ScriptedDebugBridge.Invocation node = nodeInvocations.get(index);
            ScriptedDebugBridge.Invocation java = javaInvocations.get(index);
            if (!Objects.equals(node.type(), java.type()) || !node.payload().keySet().equals(java.payload().keySet())) {
                return false;
            }
            var nodePayload = new LinkedHashMap<>(node.payload());
            var javaPayload = new LinkedHashMap<>(java.payload());
            if (reviewedRecordVideoIntervalPair(node, java)) {
                nodePayload.put("interval", "$REVIEWED_RECORD_VIDEO_INTERVAL");
                javaPayload.put("interval", "$REVIEWED_RECORD_VIDEO_INTERVAL");
            }
            else if (reviewedEntityItemSlotPair(node, java)) {
                nodePayload.put("slot", "$REVIEWED_ENTITY_ITEM_SLOT");
                javaPayload.put("slot", "$REVIEWED_ENTITY_ITEM_SLOT");
            }
            if (!nodePayload.equals(javaPayload)) {
                return false;
            }
        }
        return true;
    }

    private static boolean reviewedRecordVideoIntervalPair(ScriptedDebugBridge.Invocation node, ScriptedDebugBridge.Invocation java) {
        return node.type().equals("record_video") && java.type().equals("record_video") && node.payload().keySet().equals(REVIEWED_RECORD_VIDEO_BRIDGE_KEYS) && java.payload().keySet().equals(REVIEWED_RECORD_VIDEO_BRIDGE_KEYS) && Objects.equals(numberText(node.payload().get("interval")), "50") && Objects.equals(numberText(java.payload().get("interval")), "50.0");
    }

    private static boolean reviewedEntityItemSlotPair(ScriptedDebugBridge.Invocation node, ScriptedDebugBridge.Invocation java) {
        return node.type().equals("getEntityItemTexture") && java.type().equals("getEntityItemTexture") && node.payload().keySet().equals(REVIEWED_ENTITY_ITEM_BRIDGE_KEYS) && java.payload().keySet().equals(REVIEWED_ENTITY_ITEM_BRIDGE_KEYS) && Objects.equals(node.payload().get("slot"), "mainhand") && Objects.equals(java.payload().get("slot"), "MAINHAND");
    }

    private static String numberText(Object value) {
        return value instanceof Number number ? number.toString() : null;
    }

    private static String normalizeText(String text, Path root, int port) {
        String nativeRoot = root.toString();
        String slashRoot = nativeRoot.replace('\\', '/');
        String normalized = text.replace(nativeRoot, "$PROCESS_ROOT").replace(slashRoot, "$PROCESS_ROOT");
        normalized = normalized.replace("127.0.0.1:" + port, "127.0.0.1:$DEBUGBRIDGE_PORT");
        normalized = normalized.replace("localhost:" + port, "localhost:$DEBUGBRIDGE_PORT");
        normalized = normalized.replace("Port: " + port, "Port: $DEBUGBRIDGE_PORT");
        normalized = normalized.replace("port " + port, "port $DEBUGBRIDGE_PORT");
        return normalized.equals(Integer.toString(port)) ? "$DEBUGBRIDGE_PORT" : normalized;
    }

    private static Map<String, Object> replaceBridgePort(Map<String, Object> request, int port) {
        return map(replaceBridgePortValue(request, port));
    }

    private static Object replaceBridgePortValue(Object value, int port) {
        if (value instanceof Map<?, ?> object) {
            var replaced = new LinkedHashMap<String, Object>();
            object.forEach((key, child) -> replaced.put((String) key, replaceBridgePortValue(child, port)));
            return replaced;
        }
        if (value instanceof List<?> array) {
            return array.stream().map(child -> replaceBridgePortValue(child, port)).toList();
        }
        return "$DEBUGBRIDGE_PORT".equals(value) ? port : value;
    }

    private static void assertCatalogCoverage(Map<String, Object> toolsListResponse, List<Scenario> scenarios) {
        Set<String> advertised = toolNames(toolsListResponse);
        Map<String, Set<String>> coveredByKind = new LinkedHashMap<>();
        for (Scenario scenario : scenarios) {
            if (!scenario.request().get("method").equals("tools/call")) {
                continue;
            }
            Map<String, Object> parameters = map(scenario.request().get("params"));
            String name = Objects.toString(parameters.get("name"));
            if (advertised.contains(name)) {
                coveredByKind.computeIfAbsent(scenario.kind(), ignored -> new LinkedHashSet<>()).add(name);
            }
        }
        Set<String> staticCovered = coveredByKind.getOrDefault("static", Set.of());
        Set<String> runtimeCovered = coveredByKind.getOrDefault("runtime", Set.of());
        var expectedCatalog = new LinkedHashSet<>(STATIC_TOOLS);
        expectedCatalog.addAll(RUNTIME_TOOLS);
        var allCovered = new LinkedHashSet<>(staticCovered);
        allCovered.addAll(runtimeCovered);

        assertEquals(expectedCatalog, advertised, "The parity catalog fixture must expose every production tool, including opt-in developer tools");
        assertEquals(STATIC_TOOLS, staticCovered, "Every static handler must cross the stdio process boundary");
        assertEquals(RUNTIME_TOOLS, runtimeCovered, "Every runtime handler must cross the stdio process boundary");
        assertEquals(advertised, allCovered, "The parity corpus must fail when a newly advertised tool lacks a scenario");
    }

    private static Set<String> toolNames(Map<String, Object> response) {
        Object tools = map(response.get("result")).get("tools");
        List<?> toolList = assertInstanceOf(List.class, tools, "tools/list result must contain an array");
        var names = new LinkedHashSet<String>();
        for (Object tool : toolList) {
            assertTrue(names.add(Objects.toString(map(tool).get("name"))), "tools/list must not contain duplicate names");
        }
        return names;
    }

    private static void assertStaticProcessCoverage(List<Scenario> scenarios) {
        Map<String, Set<StaticOutcome>> outcomesByTool = new LinkedHashMap<>();
        for (Scenario scenario : scenarios) {
            if (!scenario.kind().equals("static")) {
                continue;
            }
            Map<String, Object> parameters = map(scenario.request().get("params"));
            String tool = Objects.toString(parameters.get("name"));
            outcomesByTool.computeIfAbsent(tool, ignored -> new LinkedHashSet<>()).add(scenario.staticOutcome());
        }
        assertEquals(REQUIRED_PROCESS_STATIC_OUTCOMES, outcomesByTool, "Static process parity must prove every applicable response outcome for all eight handlers");
    }

    private static void assertStaticOutcome(Scenario scenario, Map<String, Object> response, String server) {
        Map<String, Object> result = map(response.get("result"));
        boolean protocolError = Boolean.TRUE.equals(result.get("isError"));
        String text = toolText(response);
        boolean unavailable = text.startsWith("Version ") && text.contains(" not initialized.");
        boolean empty = text.startsWith("No ") || text.startsWith("Class not found:") || text.startsWith("Method \"");
        boolean truncated = text.contains(" (showing first ") && text.contains("pass a larger `limit` to see more");

        switch (scenario.staticOutcome()) {
            case ERROR ->
                    assertTrue(protocolError || unavailable, () -> server + " response for " + scenario.label() + " did not exercise a real error outcome: " + text);
            case EMPTY ->
                    assertTrue(!protocolError && !unavailable && empty && !truncated, () -> server + " response for " + scenario.label() + " was not an empty result: " + text);
            case TRUNCATED ->
                    assertTrue(!protocolError && !unavailable && !empty && truncated, () -> server + " response for " + scenario.label() + " was not a truncated result: " + text);
            case SUCCESS ->
                    assertTrue(!protocolError && !unavailable && !empty && !truncated && !text.isBlank(), () -> server + " response for " + scenario.label() + " was not a nonempty, nontruncated success: " + text);
        }
    }

    private static Path writeReport(Scenario scenario, Map<String, Object> rawNode, Map<String, Object> rawJava, Map<String, Object> normalizedNode, Map<String, Object> normalizedJava, String difference) throws IOException {
        Path reportDirectory = Path.of("build", "reports", "parity").toAbsolutePath().normalize();
        Files.createDirectories(reportDirectory);
        Path report = reportDirectory.resolve(scenario.label() + ".json");
        var contents = new LinkedHashMap<String, Object>();
        contents.put("label", scenario.label());
        contents.put("kind", scenario.kind());
        contents.put("request", scenario.request());
        contents.put("firstDifference", difference);
        contents.put("nodeRaw", rawNode);
        contents.put("javaRaw", rawJava);
        contents.put("nodeNormalized", normalizedNode);
        contents.put("javaNormalized", normalizedJava);
        Files.writeString(report, MAPPER.writeValueAsString(contents) + System.lineSeparator(), StandardCharsets.UTF_8);
        return report;
    }

    private static String firstDifference(Object node, Object java, String pointer) {
        if (node instanceof Map<?, ?> nodeMap && java instanceof Map<?, ?> javaMap) {
            if (!nodeMap.keySet().equals(javaMap.keySet())) {
                return pointer + "/<keys>";
            }
            for (Object key : nodeMap.keySet()) {
                String difference = firstDifference(nodeMap.get(key), javaMap.get(key), pointer + "/" + escapePointer(key.toString()));
                if (difference != null) {
                    return difference;
                }
            }
            return null;
        }
        if (node instanceof List<?> nodeList && java instanceof List<?> javaList) {
            if (nodeList.size() != javaList.size()) {
                return pointer + "/<length>";
            }
            for (int index = 0; index < nodeList.size(); index++) {
                String difference = firstDifference(nodeList.get(index), javaList.get(index), pointer + "/" + index);
                if (difference != null) {
                    return difference;
                }
            }
            return null;
        }
        return Objects.equals(node, java) ? null : pointer;
    }

    private static String escapePointer(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    private static List<Scenario> scenarios() throws IOException {
        return readParityRequests().stream().map(document -> new Scenario(Objects.toString(document.get("label")), Objects.toString(document.get("kind")), Objects.toString(document.get("comparison"), "exact"), StaticOutcome.from(document.get("outcome")), map(document.get("request")))).toList();
    }

    private static List<Map<String, Object>> readParityRequests() throws IOException {
        String resource = "contracts/parity/requests.jsonl";
        try (InputStream input = DifferentialMcpTest.class.getClassLoader().getResourceAsStream(resource)) {
            Objects.requireNonNull(input, "Missing test resource " + resource);
            List<Map<String, Object>> documents = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                int lineNumber = 0;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        documents.add(MAPPER.readValue(line, MAP_TYPE));
                    } catch (RuntimeException exception) {
                        throw new IOException("Invalid JSON value at line " + lineNumber + " in " + resource, exception);
                    }
                }
            }
            return List.copyOf(documents);
        }
    }

    private static Map<String, Object> map(Object value) {
        return MAPPER.convertValue(value, MAP_TYPE);
    }

    private enum StaticOutcome {
        SUCCESS, ERROR, EMPTY, TRUNCATED;

        private static StaticOutcome from(Object value) {
            return value == null ? null : valueOf(Objects.toString(value).toUpperCase(Locale.ROOT));
        }
    }

    private record LimitDescriptionPair(String node, String java) {
    }

    private record LimitTypePair(String node, String java) {
    }

    private record Scenario(String label, String kind, String comparison, StaticOutcome staticOutcome, Map<String, Object> request) {
        private Scenario {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(comparison, "comparison");
            if (!comparison.equals("exact") && !comparison.equals("find_refs_descriptors") && !comparison.equals("java_launcher") && !comparison.equals("sdk_input_validation") && !comparison.equals("typed_catalog") && !comparison.equals("pinned_guide_links") && !comparison.equals("reviewed_guide")) {
                throw new IllegalArgumentException("Unknown parity comparison mode: " + comparison);
            }
            if (kind.equals("static")) {
                Objects.requireNonNull(staticOutcome, "Static parity scenarios require an outcome: " + label);
            }
            else if (staticOutcome != null) {
                throw new IllegalArgumentException("Only static parity scenarios may declare an outcome: " + label);
            }
            request = Map.copyOf(Objects.requireNonNull(request, "request"));
        }
    }
}

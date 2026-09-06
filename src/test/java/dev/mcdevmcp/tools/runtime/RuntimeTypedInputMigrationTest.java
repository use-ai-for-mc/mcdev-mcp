package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeRequest;
import dev.mcdevmcp.bridge.BridgeResponse;
import dev.mcdevmcp.bridge.BridgeTestHarness;
import dev.mcdevmcp.bridge.payload.*;
import dev.mcdevmcp.minecraft.ResourceIdentifier;
import dev.mcdevmcp.mcp.tool.CompleteToolBindings;
import dev.mcdevmcp.mcp.tool.CountingMcpJsonMapper;
import dev.mcdevmcp.mcp.tool.ToolCatalog;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolInput;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeTypedInputMigrationTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();
    private static final AppEnvironment ENVIRONMENT = new AppEnvironment(Map.of("MCDEV_RUN_COMMAND", "1"));
    private static final Path RUNTIME_SOURCES = Path.of("src", "main", "java", "dev", "mcdevmcp", "tools", "runtime");
    private static final List<BindingExpectation> MIGRATED_BINDINGS = List.of(expectation("mc_connect", ConnectArguments.class, "McConnectTool", "ConnectWireArguments", Map.of("port", "integer", "reset", "boolean"), List.of()), expectation("mc_execute", ExecuteArguments.class, "McExecuteTool", "ExecuteWireArguments", Map.of("code", "string", "timeoutSeconds", "number"), List.of("code")), expectation("mc_run_command", RunCommandArguments.class, "McRunCommandTool", "RunCommandWireArguments", Map.of("command", "string"), List.of("command")), expectation("mc_entity_details", EntityDetailsArguments.class, "McEntityDetailsTool", null, Map.of("entityId", "integer"), List.of("entityId")), expectation("mc_block_details", BlockDetailsArguments.class, "McBlockDetailsTool", null, Map.of("position", "object"), List.of("position")), expectation("mc_set_entity_glow", EntityGlowArguments.class, "McSetEntityGlowTool", null, Map.of("entityId", "integer", "glow", "boolean"), List.of("entityId", "glow")), expectation("mc_set_block_glow", BlockGlowArguments.class, "McSetBlockGlowTool", null, Map.of("position", "object", "glow", "boolean"), List.of("position", "glow")), expectation("mc_snapshot", RuntimeEmptyArguments.class, "McSnapshotTool", null, Map.of(), List.of()), expectation("mc_clear_block_glow", RuntimeEmptyArguments.class, "McClearBlockGlowTool", null, Map.of(), List.of()), expectation("mc_leave_server", RuntimeEmptyArguments.class, "McLeaveServerTool", null, Map.of(), List.of()), expectation("mc_nearby_entities", NearbyEntitiesArguments.class, "McNearbyEntitiesTool", "NearbyEntitiesWireArguments", Map.of("range", "number", "limit", "integer", "includeIcons", "boolean"), List.of()), expectation("mc_nearby_blocks", NearbyBlocksArguments.class, "McNearbyBlocksTool", "NearbyBlocksWireArguments", Map.of("range", "number", "limit", "integer"), List.of()), expectation("mc_looked_at_entity", LookedAtEntityArguments.class, "McLookedAtEntityTool", "LookedAtEntityWireArguments", Map.of("range", "number"), List.of()), expectation("mc_chat_history", ChatHistoryArguments.class, "McChatHistoryTool", "ChatHistoryWireArguments", Map.of("limit", "integer", "includeJson", "boolean"), List.of()), expectation("mc_screen_inspect", ScreenInspectArguments.class, "McScreenInspectTool", "ScreenInspectWireArguments", Map.of("includeIcons", "boolean"), List.of()), expectation("mc_screenshot", ScreenshotArguments.class, "McScreenshotTool", "ScreenshotWireArguments", Map.of("downscale", "integer", "quality", "number"), List.of()), expectation("mc_get_item_texture", ItemTextureArguments.class, "McGetItemTextureTool", "ItemTextureWireArguments", Map.of("slot", "integer"), List.of("slot")), expectation("mc_get_entity_item_texture", EntityItemTextureArguments.class, "McGetEntityItemTextureTool", "EntityItemTextureWireArguments", Map.of("entityId", "integer", "slot", "string"), List.of("entityId", "slot")), expectation("mc_get_item_texture_by_id", ItemTextureByIdArguments.class, "McGetItemTextureByIdTool", "ItemTextureByIdWireArguments", Map.of("itemId", "string"), List.of("itemId")), expectation("mc_script_logs", ScriptLogsArguments.class, "McScriptLogsTool", "ScriptLogsWireArguments", Map.of("mode", "string", "limit", "integer"), List.of()));

    private static final List<BindingExpectation> SESSION_BINDINGS = List.of(expectation("mc_join_server", JoinServerArguments.class, "McJoinServerTool", "JoinServerWireArguments", Map.of("address", "string", "acceptResourcePacks", "boolean", "wait", "boolean", "timeoutSeconds", "number"), List.of("address")), expectation("mc_wait_until_in_world", WaitUntilInWorldArguments.class, "McWaitUntilInWorldTool", "WaitUntilInWorldWireArguments", Map.of("timeoutSeconds", "number", "requireAbsenceFirst", "boolean"), List.of()), expectation("mc_quit_client", QuitClientArguments.class, "McQuitClientTool", "QuitClientWireArguments", Map.of("waitForExit", "boolean", "timeoutSeconds", "number"), List.of()), expectation("mc_wait_for_bridge", WaitForBridgeArguments.class, "McWaitForBridgeTool", "WaitForBridgeWireArguments", Map.of("expectedVersion", "string", "timeoutSeconds", "number"), List.of()));

    private static List<BindingExpectation> allMigratedBindings() {
        return java.util.stream.Stream.concat(MIGRATED_BINDINGS.stream(), SESSION_BINDINGS.stream()).toList();
    }

    @Test
    void migratedRuntimeBindingsExposeFinalInputTypesAndCompletePropertySchemas() {
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, _) -> CompletableFuture.failedFuture(new AssertionError("No bridge call should occur in this test")))) {
            Map<String, ToolBinding<?>> bindings = RuntimeToolModule.handlers(harness.session(), MAPPER, ENVIRONMENT);

            allMigratedBindings().forEach(expectation -> assertTypedInputBinding(bindings, expectation));
        }
    }

    @Test
    void sessionBindingsDecodeValidInputsWithExactlyOneMapperConversion() {
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, _) -> CompletableFuture.failedFuture(new AssertionError("Decode-only guard must not call the bridge")))) {
            Map<String, ToolBinding<?>> bindings = RuntimeToolModule.handlers(harness.session(), MAPPER, ENVIRONMENT);
            Map<String, Map<String, Object>> valid = Map.of("mc_join_server", Map.of("address", "localhost:25565", "acceptResourcePacks", false, "wait", false, "timeoutSeconds", new BigDecimal("1.25")), "mc_wait_until_in_world", Map.of("timeoutSeconds", new BigDecimal("0.5"), "requireAbsenceFirst", true), "mc_quit_client", Map.of("timeoutSeconds", 0, "waitForExit", false), "mc_wait_for_bridge", Map.of("expectedVersion", "1.21.11", "timeoutSeconds", 0));
            for (BindingExpectation expectation : SESSION_BINDINGS) {
                ToolInput<?> input = bindings.get(expectation.toolName()).input();
                var mapper = new CountingMcpJsonMapper(MAPPER);
                Object decoded = input.decode(mapper, valid.get(expectation.toolName()));
                assertInstanceOf(expectation.argumentType(), decoded, expectation.toolName());
                assertEquals(1, mapper.convertValueCalls(), expectation.toolName() + " must perform one mapper conversion");
            }
            assertEquals(List.of(), harness.requests());
        }
    }

    @Test
    @SuppressWarnings("ALL")
    void validSimpleBindingDispatchesConvertExactlyOnceAndPreserveBridgePayloads() throws Exception {
        assertValidDispatch("mc_connect", Map.of("port", 9876, "reset", true), "status", Map.of());
        assertValidDispatch("mc_execute", Map.of("code", "return 1", "timeoutSeconds", new BigDecimal("1.25")), "execute", Map.of("code", "return 1", "timeoutMs", 1250L));
        assertValidDispatch("mc_snapshot", Map.of(), "snapshot", Map.of());
        assertValidDispatch("mc_clear_block_glow", Map.of(), "clearBlockGlow", Map.of());
        assertValidDispatch("mc_leave_server", Map.of(), "disconnect", Map.of());
        assertValidDispatch("mc_join_server", Map.of("address", "localhost:25565", "acceptResourcePacks", false, "wait", false), "joinServer", Map.of("address", "localhost:25565", "acceptResourcePacks", false));
        assertValidDispatch("mc_run_command", Map.of("command", "/say hi"), "runCommand", Map.of("command", "say hi"));
        assertValidDispatch("mc_screenshot", Map.of(), "screenshot", Map.of("downscale", 2, "quality", 0.75));
        assertValidDispatch("mc_screenshot", Map.of("downscale", 1, "quality", 0.9), "screenshot", Map.of("downscale", 1, "quality", 0.9));
        assertValidDispatch("mc_entity_details", Map.of("entityId", 42), "entityDetails", Map.of("entityId", 42));
        assertValidDispatch("mc_block_details", Map.of("position", Map.of("x", 1, "y", 2, "z", 3)), "blockDetails", Map.of("x", 1, "y", 2, "z", 3));
        assertValidDispatch("mc_set_entity_glow", Map.of("entityId", 44, "glow", false), "setEntityGlow", Map.of("entityId", 44, "glow", false));
        assertValidDispatch("mc_set_block_glow", Map.of("position", Map.of("x", 1, "y", 2, "z", 3), "glow", true), "setBlockGlow", Map.of("x", 1, "y", 2, "z", 3, "glow", true));
        assertRecordVideoDispatch(Map.of("frames", 4, "interval", Map.of("kind", "fixed", "intervalSeconds", 0.05), "output", "grid", "gridCols", 2, "downscale", 2, "quality", 0.75), Map.of("frames", 4, "interval", 50.0, "output", "grid", "gridCols", 2, "downscale", 2, "quality", 0.75));
        assertRecordVideoDispatch(Map.of("frames", 1, "interval", Map.of("kind", "fixed", "intervalSeconds", 0.0015), "output", "frames", "gridCols", 1, "downscale", 1, "quality", 0.5), Map.of("frames", 1, "interval", 1.5, "output", "frames", "gridCols", 1, "downscale", 1, "quality", 0.5));
        assertRecordVideoDispatch(Map.of("frames", 3, "interval", Map.of("kind", "frame")), Map.of("frames", 3, "interval", "frame", "output", "grid", "gridCols", 2, "downscale", 2, "quality", 0.75));
        assertValidDispatch("mc_get_item_texture", Map.of("slot", 0), "getItemTexture", Map.of("slot", 0));
        assertValidDispatch("mc_get_item_texture_by_id", Map.of("itemId", "minecraft:diamond"), "getItemTextureById", Map.of("itemId", "minecraft:diamond"));
        assertValidDispatch("mc_get_item_texture_by_id", Map.of("itemId", "diamond"), "getItemTextureById", Map.of("itemId", "minecraft:diamond"));
    }

    @Test
    @SuppressWarnings("ALL")
    void queryBindingDispatchesConvertExactlyOnceAndPreservesOmissionAndPrimitivePayloads() throws Exception {
        assertQueryDispatch("mc_nearby_entities", Map.of(), "nearbyEntities", Map.of("includeIcons", false));
        assertQueryDispatch("mc_nearby_entities", Map.of("range", 0, "limit", 0, "includeIcons", false), "nearbyEntities", Map.of("range", 0.0, "limit", 0, "includeIcons", false));
        assertQueryDispatch("mc_nearby_blocks", Map.of(), "nearbyBlocks", Map.of());
        assertQueryDispatch("mc_nearby_blocks", Map.of("range", 0, "limit", 0), "nearbyBlocks", Map.of("range", 0.0, "limit", 0));
        assertQueryDispatch("mc_looked_at_entity", Map.of(), "lookedAtEntity", Map.of());
        assertQueryDispatch("mc_looked_at_entity", Map.of("range", 0), "lookedAtEntity", Map.of("range", 0.0));
        assertQueryDispatch("mc_chat_history", Map.of(), "chatHistory", Map.of("includeJson", false));
        assertQueryDispatch("mc_chat_history", Map.of("limit", 0, "includeJson", false), "chatHistory", Map.of("limit", 0, "includeJson", false));
        assertQueryDispatch("mc_screen_inspect", Map.of(), "screenInspect", Map.of("includeIcons", false));
        assertQueryDispatch("mc_screen_inspect", Map.of("includeIcons", false), "screenInspect", Map.of("includeIcons", false));
    }

    @Test
    void connectAndExecuteUseTypedDefaultsBoundsAndSeconds() {
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, _) -> CompletableFuture.failedFuture(new AssertionError("No bridge call should occur in this test")))) {
            Map<String, ToolBinding<?>> bindings = RuntimeToolModule.handlers(harness.session(), MAPPER, ENVIRONMENT);

            ToolInput<ConnectArguments> connectInput = typedInput(bindings.get("mc_connect"), ConnectArguments.class);
            Map<String, Object> connectSchema = connectInput.schema().value();
            Map<?, ?> connectProperties = assertInstanceOf(Map.class, connectSchema.get("properties"));
            assertEquals(Map.of("type", "integer", "description", "WebSocket port. Default: scan 9876-9886", "minimum", BigDecimal.ONE, "maximum", new BigDecimal("65535")), connectProperties.get("port"));
            assertEquals(Map.of("type", "boolean", "description", "Disconnect and clear state before connecting (for switching instances)", "default", false), connectProperties.get("reset"));
            assertEquals(false, connectSchema.get("additionalProperties"));
            ConnectArguments connectDefaults = connectInput.decode(MAPPER, Map.of());
            assertNull(connectDefaults.port());
            assertFalse(connectDefaults.reset());

            ToolInput<ExecuteArguments> executeInput = typedInput(bindings.get("mc_execute"), ExecuteArguments.class);
            Map<String, Object> executeSchema = executeInput.schema().value();
            Map<?, ?> executeProperties = assertInstanceOf(Map.class, executeSchema.get("properties"));
            assertEquals(Map.of("type", "string", "description", "Groovy code to execute"), executeProperties.get("code"));
            assertEquals(Map.of("type", "number", "description", "Optional per-call execution deadline in seconds. Range 1-300, default 10 (10s). Use a longer value for bulk reflection or heavy file I/O.", "minimum", BigDecimal.ONE, "maximum", new BigDecimal("300"), "default", new BigDecimal("10")), executeProperties.get("timeoutSeconds"));
            assertEquals(List.of("code"), executeSchema.get("required"));
            assertEquals(false, executeSchema.get("additionalProperties"));
            ExecuteArguments executeDefaults = executeInput.decode(MAPPER, Map.of("code", "return 1"));
            assertEquals(Duration.ofSeconds(10), executeDefaults.timeoutSeconds());
            ExecuteArguments fractional = executeInput.decode(MAPPER, Map.of("code", "return 1", "timeoutSeconds", new BigDecimal("1.25")));
            assertEquals(Duration.ofMillis(1250), fractional.timeoutSeconds());
        }
    }

    @Test
    void screenshotUsesPrimitiveComponentsExactSchemaAndDirectDefaults() {
        assertEquals(int.class, ScreenshotArguments.class.getRecordComponents()[0].getType());
        assertEquals(double.class, ScreenshotArguments.class.getRecordComponents()[1].getType());
        assertThrows(IllegalArgumentException.class, () -> new ScreenshotArguments(0, 0.75));
        assertThrows(IllegalArgumentException.class, () -> new ScreenshotArguments(2, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new ScreenshotArguments(2, 0.049));
        assertThrows(IllegalArgumentException.class, () -> new ScreenshotArguments(2, 1.001));

        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, _) -> CompletableFuture.failedFuture(new AssertionError("No bridge call should occur in this test")))) {
            ToolInput<ScreenshotArguments> input = typedInput(RuntimeToolModule.handlers(harness.session(), MAPPER, ENVIRONMENT).get("mc_screenshot"), ScreenshotArguments.class);
            Map<String, Object> schema = input.schema().value();
            Map<?, ?> properties = assertInstanceOf(Map.class, schema.get("properties"));
            assertEquals(Map.of("type", "integer", "description", "Integer downscale factor. 1 = full window resolution. 2 = half each axis (default).", "minimum", BigDecimal.ONE, "default", new java.math.BigInteger("2")), properties.get("downscale"));
            assertEquals(Map.of("type", "number", "description", "JPEG quality in [0.05, 1.0]. Default: 0.75.", "minimum", new BigDecimal("0.05"), "maximum", BigDecimal.ONE, "default", new BigDecimal("0.75")), properties.get("quality"));
            assertFalse(schema.containsKey("required"));
            assertEquals(false, schema.get("additionalProperties"));

            var mapper = new CountingMcpJsonMapper(MAPPER);
            ScreenshotArguments defaults = input.decode(mapper, Map.of());
            assertEquals(2, defaults.downscale());
            assertEquals(0.75, defaults.quality());
            assertEquals(1, mapper.convertValueCalls());
        }
    }

    @Test
    void screenshotRejectsInvalidInputBeforeMappingOrBridgeDispatch() {
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, _) -> CompletableFuture.failedFuture(new AssertionError("No bridge call should occur on invalid screenshot arguments")))) {
            ToolBinding<?> binding = RuntimeToolModule.handlers(harness.session(), MAPPER, ENVIRONMENT).get("mc_screenshot");
            List<Map<String, Object>> invalid = List.of(nullArgument("downscale"), nullArgument("quality"), Map.of("downscale", 1.5), Map.of("downscale", 0), Map.of("quality", 0.049), Map.of("quality", 1.001), Map.of("quality", Double.NaN), Map.of("downscale", "2"), Map.of("quality", "0.75"), Map.of("unknown", true));
            for (Map<String, Object> arguments : invalid) {
                var mapper = new CountingMcpJsonMapper(MAPPER);
                assertThrows(IllegalArgumentException.class, () -> binding.invoke(mapper, arguments, Cancellation.none()));
                assertEquals(0, mapper.convertValueCalls());
            }
            assertEquals(List.of(), harness.requests());
        }
    }

    @Test
    void queryRecordsUseSourceExactPrimitiveTypesAndDescriptiveDefaults() {
        assertEquals(Integer.class, ConnectArguments.class.getRecordComponents()[0].getType());
        assertEquals(boolean.class, ConnectArguments.class.getRecordComponents()[1].getType());
        assertEquals(Double.class, NearbyEntitiesArguments.class.getRecordComponents()[0].getType());
        assertEquals(Integer.class, NearbyEntitiesArguments.class.getRecordComponents()[1].getType());
        assertEquals(boolean.class, NearbyEntitiesArguments.class.getRecordComponents()[2].getType());
        assertEquals(Double.class, NearbyBlocksArguments.class.getRecordComponents()[0].getType());
        assertEquals(Integer.class, NearbyBlocksArguments.class.getRecordComponents()[1].getType());
        assertEquals(Double.class, LookedAtEntityArguments.class.getRecordComponents()[0].getType());
        assertEquals(Integer.class, ChatHistoryArguments.class.getRecordComponents()[0].getType());
        assertEquals(boolean.class, ChatHistoryArguments.class.getRecordComponents()[1].getType());
        assertEquals(boolean.class, ScreenInspectArguments.class.getRecordComponents()[0].getType());

        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, _) -> CompletableFuture.failedFuture(new AssertionError("No bridge call should occur in this test")))) {
            Map<String, ToolBinding<?>> bindings = RuntimeToolModule.handlers(harness.session(), MAPPER, ENVIRONMENT);

            assertQuerySchema(bindings.get("mc_connect"), ConnectArguments.class, Map.of("port", Map.of("type", "integer", "description", "WebSocket port. Default: scan 9876-9886", "minimum", BigDecimal.ONE, "maximum", new BigDecimal("65535")), "reset", Map.of("type", "boolean", "description", "Disconnect and clear state before connecting (for switching instances)", "default", false)));
            assertQuerySchema(bindings.get("mc_nearby_entities"), NearbyEntitiesArguments.class, Map.of("range", Map.of("type", "number", "description", "Search radius in blocks. Default 64.", "minimum", BigDecimal.ZERO, "default", new BigDecimal("64")), "limit", Map.of("type", "integer", "description", "Max entries returned. Default 100.", "minimum", BigDecimal.ZERO, "default", new java.math.BigInteger("100")), "includeIcons", Map.of("type", "boolean", "description", "Render each unique primaryEquipment item's icon. Default false.", "default", false)));
            assertQuerySchema(bindings.get("mc_nearby_blocks"), NearbyBlocksArguments.class, Map.of("range", Map.of("type", "number", "description", "Search radius in blocks. Default 16.", "minimum", BigDecimal.ZERO, "default", new BigDecimal("16")), "limit", Map.of("type", "integer", "description", "Max entries returned. Default 100.", "minimum", BigDecimal.ZERO, "default", new java.math.BigInteger("100"))));
            assertQuerySchema(bindings.get("mc_looked_at_entity"), LookedAtEntityArguments.class, Map.of("range", Map.of("type", "number", "description", "Raycast distance in blocks. Default 64.", "minimum", BigDecimal.ZERO, "default", new BigDecimal("64"))));
            assertQuerySchema(bindings.get("mc_chat_history"), ChatHistoryArguments.class, Map.of("limit", Map.of("type", "integer", "description", "Max messages returned. Default 50.", "minimum", BigDecimal.ZERO, "default", new java.math.BigInteger("50")), "includeJson", Map.of("type", "boolean", "description", "Include the Component as JSON for each message. Default false.", "default", false)));
            assertQuerySchema(bindings.get("mc_screen_inspect"), ScreenInspectArguments.class, Map.of("includeIcons", Map.of("type", "boolean", "description", "Render each unique item's icon and attach as an icons map. Default false.", "default", false)));

            assertEquals(new ConnectArguments(null, false), typedInput(bindings.get("mc_connect"), ConnectArguments.class).decode(MAPPER, Map.of()));
            assertEquals(new NearbyEntitiesArguments(null, null, false), typedInput(bindings.get("mc_nearby_entities"), NearbyEntitiesArguments.class).decode(MAPPER, Map.of()));
            assertEquals(new NearbyBlocksArguments(null, null), typedInput(bindings.get("mc_nearby_blocks"), NearbyBlocksArguments.class).decode(MAPPER, Map.of()));
            assertEquals(new LookedAtEntityArguments(null), typedInput(bindings.get("mc_looked_at_entity"), LookedAtEntityArguments.class).decode(MAPPER, Map.of()));
            assertEquals(new ChatHistoryArguments(null, false), typedInput(bindings.get("mc_chat_history"), ChatHistoryArguments.class).decode(MAPPER, Map.of()));
            assertEquals(new ScreenInspectArguments(false), typedInput(bindings.get("mc_screen_inspect"), ScreenInspectArguments.class).decode(MAPPER, Map.of()));
        }
    }

    @Test
    void queryRecordConstructionRejectsNegativeAndNonFiniteValues() {
        assertThrows(IllegalArgumentException.class, () -> new NearbyEntitiesArguments(-0.1, null, false));
        assertThrows(IllegalArgumentException.class, () -> new NearbyEntitiesArguments(Double.NaN, null, false));
        assertThrows(IllegalArgumentException.class, () -> new NearbyEntitiesArguments(null, -1, false));
        assertThrows(IllegalArgumentException.class, () -> new NearbyBlocksArguments(-0.1, null));
        assertThrows(IllegalArgumentException.class, () -> new NearbyBlocksArguments(Double.POSITIVE_INFINITY, null));
        assertThrows(IllegalArgumentException.class, () -> new NearbyBlocksArguments(null, -1));
        assertThrows(IllegalArgumentException.class, () -> new LookedAtEntityArguments(-0.1));
        assertThrows(IllegalArgumentException.class, () -> new LookedAtEntityArguments(Double.NEGATIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new ChatHistoryArguments(-1, false));
    }

    @Test
    void queryInputsRejectNullFractionalWrongTypeAndBoundsBeforeBridgeDispatch() {
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, _) -> CompletableFuture.failedFuture(new AssertionError("No bridge call should occur on invalid query arguments")))) {
            Map<String, ToolBinding<?>> bindings = RuntimeToolModule.handlers(harness.session(), MAPPER, ENVIRONMENT);
            List<Map.Entry<String, Map<String, Object>>> invalid = List.of(Map.entry("mc_connect", nullArgument("port")), Map.entry("mc_connect", Map.of("port", 1.5)), Map.entry("mc_connect", Map.of("port", 0)), Map.entry("mc_connect", Map.of("port", 65536)), Map.entry("mc_connect", nullArgument("reset")), Map.entry("mc_nearby_entities", nullArgument("range")), Map.entry("mc_nearby_entities", Map.of("range", -0.1)), Map.entry("mc_nearby_entities", Map.of("limit", 1.5)), Map.entry("mc_nearby_entities", Map.of("limit", -1)), Map.entry("mc_nearby_entities", nullArgument("includeIcons")), Map.entry("mc_nearby_blocks", Map.of("range", -0.1)), Map.entry("mc_nearby_blocks", Map.of("limit", 1.5)), Map.entry("mc_nearby_blocks", Map.of("limit", -1)), Map.entry("mc_looked_at_entity", Map.of("range", -0.1)), Map.entry("mc_chat_history", Map.of("limit", 1.5)), Map.entry("mc_chat_history", Map.of("limit", -1)), Map.entry("mc_chat_history", nullArgument("includeJson")), Map.entry("mc_screen_inspect", nullArgument("includeIcons")), Map.entry("mc_screen_inspect", Map.of("includeIcons", "false")));
            for (Map.Entry<String, Map<String, Object>> testCase : invalid) {
                CountingMcpJsonMapper mapper = new CountingMcpJsonMapper(MAPPER);
                ToolBinding<?> binding = bindings.get(testCase.getKey());
                assertNotNull(binding, testCase.getKey());
                assertThrows(IllegalArgumentException.class, () -> binding.invoke(mapper, testCase.getValue(), Cancellation.none()), testCase.getKey());
                assertEquals(0, mapper.convertValueCalls(), testCase.getKey() + " must reject before mapping");
            }
            assertEquals(List.of(), harness.requests());
        }
    }

    private static <A> void assertQuerySchema(ToolBinding<?> binding, Class<A> type, Map<String, Object> properties) {
        assertNotNull(binding);
        ToolInput<A> input = typedInput(binding, type);
        assertEquals(Map.of("type", "object", "properties", properties, "additionalProperties", false), input.schema().value());
    }

    private static Map<String, Object> nullArgument(String name) {
        var arguments = new java.util.LinkedHashMap<String, Object>();
        arguments.put(name, null);
        return arguments;
    }

    @Test
    void itemTextureSchemasPreserveDescriptionsAndBounds() {
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, _) -> CompletableFuture.failedFuture(new AssertionError("No bridge call should occur in this test")))) {
            ToolInput<ItemTextureArguments> itemInput = typedInput(RuntimeToolModule.handlers(harness.session(), MAPPER, ENVIRONMENT).get("mc_get_item_texture"), ItemTextureArguments.class);
            Map<String, Object> itemSchema = itemInput.schema().value();
            assertEquals(false, itemSchema.get("additionalProperties"));
            assertEquals(List.of("slot"), itemSchema.get("required"));
            Map<?, ?> itemProperties = assertInstanceOf(Map.class, itemSchema.get("properties"));
            assertEquals(Map.of("type", "integer", "minimum", BigDecimal.ZERO, "maximum", new BigDecimal("40"), "description", "Inventory slot index (0-35 main inventory, 36 feet, 37 legs, 38 chest, 39 head, 40 offhand)."), itemProperties.get("slot"));

            ToolInput<ItemTextureByIdArguments> itemByIdInput = typedInput(RuntimeToolModule.handlers(harness.session(), MAPPER, ENVIRONMENT).get("mc_get_item_texture_by_id"), ItemTextureByIdArguments.class);
            Map<String, Object> itemByIdSchema = itemByIdInput.schema().value();
            assertEquals(false, itemByIdSchema.get("additionalProperties"));
            assertEquals(List.of("itemId"), itemByIdSchema.get("required"));
            Map<?, ?> itemByIdProperties = assertInstanceOf(Map.class, itemByIdSchema.get("properties"));
            assertEquals(Map.of("type", "string", "description", "Registry id like \"minecraft:diamond\"."), itemByIdProperties.get("itemId"));
        }
    }

    @Test
    void entityItemTextureUsesIntegerIdAndAllLowercaseEquipmentSlots() {
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, _) -> CompletableFuture.failedFuture(new AssertionError("No bridge call should occur in this test")))) {
            ToolInput<?> input = RuntimeToolModule.handlers(harness.session(), MAPPER, ENVIRONMENT).get("mc_get_entity_item_texture").input();
            Class<?> inputClass = input.type().rawClass();
            assertNotNull(inputClass);
            Class<?>[] componentTypes = java.util.Arrays.stream(inputClass.getRecordComponents()).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);
            assertEquals(int.class, componentTypes[0]);
            assertEquals("EntityItemSlot", componentTypes[1].getSimpleName());

            Map<String, Object> schema = input.schema().value();
            assertEquals(List.of("entityId", "slot"), schema.get("required"));
            assertEquals(false, schema.get("additionalProperties"));
            Map<?, ?> properties = assertInstanceOf(Map.class, schema.get("properties"));
            assertEquals(List.of("entityId", "slot"), List.copyOf(properties.keySet()));
            assertEquals(Map.of("type", "integer"), properties.get("entityId"));
            assertEquals(Map.of("type", "string", "enum", List.of("mainhand", "offhand", "feet", "legs", "chest", "head", "body", "saddle", "frame", "display")), properties.get("slot"));

            Object decoded = input.decode(MAPPER, Map.of("entityId", 7, "slot", "mainhand"));
            Enum<?> slot = ((EntityItemTextureArguments) decoded).slot();
            assertEquals("MAINHAND", slot.name());
        }
    }

    @Test
    void entityItemTextureProjectsEveryEquipmentSlotAsUppercaseAtTheBridgeBoundary() throws Exception {
        Map<String, String> bridgeNames = Map.of("mainhand", "MAINHAND", "offhand", "OFFHAND", "feet", "FEET", "legs", "LEGS", "chest", "CHEST", "head", "HEAD", "body", "BODY", "saddle", "SADDLE", "frame", "FRAME", "display", "DISPLAY");
        for (Map.Entry<String, String> slot : bridgeNames.entrySet()) {
            assertValidDispatch("mc_get_entity_item_texture", Map.of("entityId", 7, "slot", slot.getKey()), "getEntityItemTexture", Map.of("entityId", 7, "slot", slot.getValue()));
        }
    }

    @Test
    void scriptLogsUsesDirectLowercaseEnumInputWithValidatedIntegerDefault() {
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, _) -> CompletableFuture.failedFuture(new AssertionError("No bridge call should occur in this test")))) {
            ToolInput<?> input = RuntimeToolModule.handlers(harness.session(), MAPPER, ENVIRONMENT).get("mc_script_logs").input();
            assertEquals(ScriptLogsArguments.class, input.type().rawClass());
            Map<String, Object> schema = input.schema().value();
            Map<?, ?> properties = assertInstanceOf(Map.class, schema.get("properties"));
            assertEquals(List.of("mode", "limit"), new java.util.ArrayList<>(properties.keySet()));
            assertFalse(schema.containsKey("required"));
            assertEquals(false, schema.get("additionalProperties"));
            assertEquals(Map.of("type", "string", "enum", List.of("errors", "stats", "paths"), "description", "What to show: 'errors' (recent failures), 'stats' (error patterns), 'paths' (file locations)", "default", "errors"), properties.get("mode"));
            assertEquals(Map.of("type", "integer", "minimum", BigDecimal.ONE, "description", "Number of entries to show (for 'errors' mode). Default: 20", "default", java.math.BigInteger.valueOf(20)), properties.get("limit"));

            ScriptLogsArguments defaults = new ScriptLogsArguments(null, 0);
            assertEquals("ERRORS", defaults.mode().name());
            assertEquals(20, defaults.limit());
            ScriptLogsArguments selected = (ScriptLogsArguments) input.decode(MAPPER, Map.of("mode", "paths", "limit", 3));
            assertEquals("PATHS", selected.mode().name());
            assertEquals(3, selected.limit());
        }
    }

    @Test
    void scriptLogsEmptyArgumentsDecodeDefaultsThroughOneMapperConversion() {
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, _) -> CompletableFuture.failedFuture(new AssertionError("No bridge call should occur in this test")))) {
            ToolInput<?> input = RuntimeToolModule.handlers(harness.session(), MAPPER, ENVIRONMENT).get("mc_script_logs").input();
            CountingMcpJsonMapper mapper = new CountingMcpJsonMapper(MAPPER);

            ScriptLogsArguments arguments = (ScriptLogsArguments) input.decode(mapper, Map.of());

            assertEquals(ScriptLogMode.ERRORS, arguments.mode());
            assertEquals(20, arguments.limit());
            assertEquals(1, mapper.convertValueCalls());
        }
    }

    @Test
    void entityTextureAndScriptLogsRejectMalformedPropertiesBeforeMapperConversion() {
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, _) -> CompletableFuture.failedFuture(new AssertionError("No bridge call should occur on invalid arguments")))) {
            Map<String, ToolBinding<?>> bindings = RuntimeToolModule.handlers(harness.session(), MAPPER, ENVIRONMENT);
            List<Map.Entry<String, Map<String, Object>>> invalid = List.of(Map.entry("mc_get_entity_item_texture", Map.of()), Map.entry("mc_get_entity_item_texture", Map.of("entityId", "7", "slot", "mainhand")), Map.entry("mc_get_entity_item_texture", Map.of("entityId", 7.5, "slot", "mainhand")), Map.entry("mc_get_entity_item_texture", Map.of("entityId", 7, "slot", "unknown")), Map.entry("mc_get_entity_item_texture", Map.of("entityId", 7, "slot", "mainhand", "unknown", true)), Map.entry("mc_script_logs", Map.of("mode", "bogus")), Map.entry("mc_script_logs", Map.of("limit", 0)), Map.entry("mc_script_logs", Map.of("limit", -1)), Map.entry("mc_script_logs", Map.of("limit", 1.5)), Map.entry("mc_script_logs", Map.of("limit", "20")), Map.entry("mc_script_logs", Map.of("unknown", true)));
            for (Map.Entry<String, Map<String, Object>> testCase : invalid) {
                CountingMcpJsonMapper mapper = new CountingMcpJsonMapper(MAPPER);
                ToolBinding<?> binding = bindings.get(testCase.getKey());
                assertNotNull(binding);
                assertThrows(IllegalArgumentException.class, () -> binding.invoke(mapper, testCase.getValue(), Cancellation.none()), testCase.getKey());
                assertEquals(0, mapper.convertValueCalls(), testCase.getKey() + " must reject before mapping");
            }
            assertEquals(List.of(), harness.requests());
        }
    }

    @Test
    void executeUsesTheSecondsDurationForBothBridgeWireAndRequestDeadline() throws Exception {
        CountingMcpJsonMapper mapper = new CountingMcpJsonMapper(MAPPER);
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, request) -> successfulResponse(request))) {
            ToolBinding<?> binding = RuntimeToolModule.handlers(harness.session(), MAPPER, ENVIRONMENT).get("mc_execute");

            ToolResult<?> result = binding.invoke(mapper, Map.of("code", "return 1", "timeoutSeconds", new BigDecimal("1.25")), Cancellation.none()).toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertFalse(result.isError());
            assertEquals(1, mapper.convertValueCalls());
            assertEquals(Map.of("code", "return 1", "timeoutMs", 1250L), mapPayload(harness.requests().getLast().payload()));
            assertEquals(List.of(Duration.ofSeconds(10), Duration.ofMillis(6_250)), harness.effectiveTimeouts());
        }
    }

    @Test
    void connectAndExecuteRejectMalformedLegacyArgumentsBeforeMappingOrBridge() {
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, _) -> CompletableFuture.failedFuture(new AssertionError("No bridge call should occur on invalid arguments")))) {
            Map<String, ToolBinding<?>> bindings = RuntimeToolModule.handlers(harness.session(), MAPPER, ENVIRONMENT);
            List<Map.Entry<String, Map<String, Object>>> invalid = List.of(Map.entry("mc_connect", Map.of("port", 0)), Map.entry("mc_connect", Map.of("port", 65536)), Map.entry("mc_connect", Map.of("port", 1.5)), Map.entry("mc_connect", Map.of("port", "9876")), Map.entry("mc_connect", Map.of("reset", "false")), Map.entry("mc_execute", Map.of()), Map.entry("mc_execute", Map.of("code", "return 1", "timeoutSeconds", 0.5)), Map.entry("mc_execute", Map.of("code", "return 1", "timeoutSeconds", 300.1)), Map.entry("mc_execute", Map.of("code", "return 1", "timeoutSeconds", "1.25")), Map.entry("mc_execute", Map.of("code", "return 1", "timeoutMs", 1000)));
            for (Map.Entry<String, Map<String, Object>> testCase : invalid) {
                CountingMcpJsonMapper mapper = new CountingMcpJsonMapper(MAPPER);
                assertThrows(IllegalArgumentException.class, () -> bindings.get(testCase.getKey()).invoke(mapper, testCase.getValue(), Cancellation.none()), testCase.getKey());
                assertEquals(0, mapper.convertValueCalls(), testCase.getKey() + " must reject before mapping");
            }
            assertEquals(List.of(), harness.requests());
        }
    }

    @SuppressWarnings("unchecked")
    private static <A> ToolInput<A> typedInput(ToolBinding<?> binding, Class<A> type) {
        assertNotNull(binding);
        ToolInput<?> input = binding.input();
        assertEquals(type, input.type().rawClass());
        return (ToolInput<A>) input;
    }

    @Test
    void recordVideoBindingExposesTaggedDurationIntervalAndPrimitiveInput() {
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, _) -> CompletableFuture.failedFuture(new AssertionError("No bridge call should occur in this test")))) {
            ToolBinding<?> binding = RuntimeToolModule.handlers(harness.session(), MAPPER, ENVIRONMENT).get("mc_record_video");
            assertNotNull(binding);
            ToolInput<?> input = binding.input();
            assertEquals(RecordVideoArguments.class, input.type().rawClass());

            Map<String, Object> schema = input.schema().value();
            Map<?, ?> properties = assertInstanceOf(Map.class, schema.get("properties"));
            assertEquals(List.of("frames", "interval", "output", "gridCols", "downscale", "quality"), List.copyOf(properties.keySet()));
            Map<?, ?> interval = assertInstanceOf(Map.class, properties.get("interval"));
            assertEquals(List.of(Map.of("type", "object", "properties", Map.of("kind", Map.of("type", "string", "const", "frame")), "required", List.of("kind"), "additionalProperties", false), Map.of("type", "object", "properties", Map.of("kind", Map.of("type", "string", "const", "fixed"), "intervalSeconds", Map.of("type", "number", "minimum", new BigDecimal("0.001"))), "required", List.of("kind", "intervalSeconds"), "additionalProperties", false)), interval.get("oneOf"));
            assertEquals(Map.of("type", "string", "enum", List.of("grid", "frames"), "description", "\"grid\" (one composed JPEG, default) or \"frames\" (N separate JPEGs).", "default", "grid"), properties.get("output"));
            assertEquals("integer", ((Map<?, ?>) properties.get("frames")).get("type"));
            assertEquals("integer", ((Map<?, ?>) properties.get("gridCols")).get("type"));
            assertEquals("integer", ((Map<?, ?>) properties.get("downscale")).get("type"));
            assertEquals("number", ((Map<?, ?>) properties.get("quality")).get("type"));
            assertEquals(List.of("frames"), schema.get("required"));
            assertEquals(false, schema.get("additionalProperties"));
        }
    }

    @Test
    void recordVideoDecodesSemanticIntervalVariantsAndRejectsUnknownDiscriminatorsBeforeMapping() throws Exception {
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, _) -> CompletableFuture.failedFuture(new AssertionError("No bridge call should occur in this test")))) {
            ToolInput<?> input = RuntimeToolModule.handlers(harness.session(), MAPPER, ENVIRONMENT).get("mc_record_video").input();

            RecordVideoArguments fixed = (RecordVideoArguments) input.decode(MAPPER, Map.of("frames", 2, "interval", Map.of("kind", "fixed", "intervalSeconds", 0.05)));
            assertEquals("Fixed", fixed.interval().getClass().getSimpleName());
            assertEquals(Duration.ofMillis(50), fixed.interval().getClass().getRecordComponents()[0].getAccessor().invoke(fixed.interval()));

            RecordVideoArguments frame = (RecordVideoArguments) input.decode(MAPPER, Map.of("frames", 2, "interval", Map.of("kind", "frame")));
            assertInstanceOf(RecordInterval.Frame.class, frame.interval());

            for (Map<String, Object> invalid : List.of(Map.of("frames", 2, "interval", Map.of("kind", "unknown")), Map.of("frames", 2, "interval", Map.of("kind", "text", "value", "frame")), Map.of("frames", 2, "interval", Map.of("kind", "fixed", "intervalSeconds", 0.0009)))) {
                var mapper = new CountingMcpJsonMapper(MAPPER);
                assertThrows(IllegalArgumentException.class, () -> input.decode(mapper, invalid));
                assertEquals(0, mapper.convertValueCalls(), "Unknown interval discriminator must fail before mapping");
            }
        }
    }

    @Test
    void recordVideoMaterializesDefaultsAndUsesExactFinalComponentTypes() {
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, _) -> CompletableFuture.failedFuture(new AssertionError("No bridge call should occur in this test")))) {
            ToolInput<?> input = RuntimeToolModule.handlers(harness.session(), MAPPER, ENVIRONMENT).get("mc_record_video").input();
            var mapper = new CountingMcpJsonMapper(MAPPER);
            RecordVideoArguments arguments = (RecordVideoArguments) input.decode(mapper, Map.of("frames", 5));

            assertEquals(int.class, RecordVideoArguments.class.getRecordComponents()[0].getType());
            assertEquals(RecordInterval.class, RecordVideoArguments.class.getRecordComponents()[1].getType());
            assertEquals(RecordVideoOutput.class, RecordVideoArguments.class.getRecordComponents()[2].getType());
            assertEquals(int.class, RecordVideoArguments.class.getRecordComponents()[3].getType());
            assertEquals(int.class, RecordVideoArguments.class.getRecordComponents()[4].getType());
            assertEquals(double.class, RecordVideoArguments.class.getRecordComponents()[5].getType());
            assertEquals(Duration.class, RecordInterval.Fixed.class.getRecordComponents()[0].getType());
            var creator = java.util.Arrays.stream(RecordVideoArguments.class.getDeclaredMethods()).filter(method -> Modifier.isStatic(method.getModifiers()) && RecordVideoArguments.class.isAssignableFrom(method.getReturnType())).findFirst().orElseThrow();
            assertEquals(List.of(Integer.class, RecordInterval.class, RecordVideoOutput.class, Integer.class, Integer.class, Double.class), List.of(creator.getParameterTypes()));
            assertEquals(5, arguments.frames());
            assertEquals("Frame", arguments.interval().getClass().getSimpleName());
            assertEquals(RecordVideoOutput.GRID, arguments.output());
            assertEquals(3, arguments.gridCols());
            assertEquals(2, arguments.downscale());
            assertEquals(0.75, arguments.quality());
            assertEquals(1, mapper.convertValueCalls());
        }
    }

    @Test
    void recordVideoFinalConstructionEnforcesDomainBounds() {
        var frame = new RecordInterval.Frame();
        assertThrows(IllegalArgumentException.class, () -> new RecordVideoArguments(0, frame, RecordVideoOutput.GRID, 1, 1, 0.75));
        assertThrows(IllegalArgumentException.class, () -> new RecordVideoArguments(301, frame, RecordVideoOutput.GRID, 1, 1, 0.75));
        assertThrows(IllegalArgumentException.class, () -> new RecordVideoArguments(4, frame, RecordVideoOutput.GRID, 5, 1, 0.75));
        assertThrows(IllegalArgumentException.class, () -> new RecordVideoArguments(4, frame, RecordVideoOutput.GRID, 1, 0, 0.75));
        assertThrows(IllegalArgumentException.class, () -> new RecordVideoArguments(4, frame, RecordVideoOutput.GRID, 1, 1, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new RecordVideoArguments(4, frame, RecordVideoOutput.GRID, 1, 1, 0.049));
        assertThrows(IllegalArgumentException.class, () -> new RecordVideoArguments(4, null, RecordVideoOutput.GRID, 1, 1, 0.75));
        assertThrows(IllegalArgumentException.class, () -> new RecordVideoArguments(4, frame, null, 1, 1, 0.75));
        assertThrows(IllegalArgumentException.class, () -> new RecordInterval.Fixed(null));
    }

    @Test
    void recordVideoRejectsInvalidRootAndNestedValuesBeforeMappingOrBridgeDispatch() {
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, _) -> CompletableFuture.failedFuture(new AssertionError("No bridge call should occur on invalid record-video arguments")))) {
            ToolBinding<?> binding = RuntimeToolModule.handlers(harness.session(), MAPPER, ENVIRONMENT).get("mc_record_video");
            List<Map<String, Object>> invalid = List.of(Map.of("frames", "4"), Map.of("frames", 1.5), Map.of("frames", 0), Map.of("frames", 301), mapWithNull("frames"), Map.of("frames", 2, "interval", "frame"), Map.of("frames", 2, "interval", Map.of("kind", "fixed")), Map.of("frames", 2, "interval", Map.of("kind", "fixed", "intervalSeconds", 0.0009)), Map.of("frames", 2, "interval", Map.of("kind", "fixed", "intervalSeconds", 0.05, "unknown", true)), mapWithNull("interval"), Map.of("frames", 2, "output", "GRID"), mapWithNull("output"), Map.of("frames", 2, "gridCols", 0), Map.of("frames", 2, "gridCols", 3), Map.of("frames", 2, "gridCols", 1.5), mapWithNull("gridCols"), Map.of("frames", 2, "downscale", 0), Map.of("frames", 2, "downscale", 1.5), mapWithNull("downscale"), Map.of("frames", 2, "quality", 0.049), Map.of("frames", 2, "quality", 1.001), Map.of("frames", 2, "quality", Double.NaN), Map.of("frames", 2, "quality", "0.75"), mapWithNull("quality"), Map.of("frames", 2, "unknown", true));
            for (Map<String, Object> arguments : invalid) {
                var mapper = new CountingMcpJsonMapper(MAPPER);
                assertThrows(IllegalArgumentException.class, () -> binding.invoke(mapper, arguments, Cancellation.none()));
                int expectedConversions = arguments.get("gridCols") instanceof Integer gridCols && gridCols > 2 ? 1 : 0;
                assertEquals(expectedConversions, mapper.convertValueCalls(), "Invalid record-video input must not dispatch to the bridge: " + arguments);
            }
            assertEquals(List.of(), harness.requests());
        }
    }

    @Test
    void itemTextureBindingsRejectInvalidRuntimePropertiesBeforeMappingAndBridgeDispatch() {
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, _) -> CompletableFuture.failedFuture(new AssertionError("No bridge call should occur on invalid item texture arguments")))) {
            Map<String, ToolBinding<?>> bindings = RuntimeToolModule.handlers(harness.session(), MAPPER, ENVIRONMENT);
            List<Map.Entry<String, Map<String, Object>>> invalid = List.of(Map.entry("mc_get_item_texture", Map.of()), Map.entry("mc_get_item_texture", Map.of("slot", "0")), Map.entry("mc_get_item_texture", Map.of("slot", 1.5)), Map.entry("mc_get_item_texture", Map.of("slot", -1)), Map.entry("mc_get_item_texture", Map.of("slot", 41)), Map.entry("mc_get_item_texture", Map.of("slot", 0, "unknown", true)), Map.entry("mc_get_item_texture_by_id", Map.of()), Map.entry("mc_get_item_texture_by_id", Map.of("itemId", 41)), Map.entry("mc_get_item_texture_by_id", Map.of("itemId", "minecraft:diamond", "unknown", true)), Map.entry("mc_get_item_texture_by_id", itemIdArguments(null)), Map.entry("mc_get_item_texture_by_id", itemIdArguments("")), Map.entry("mc_get_item_texture_by_id", itemIdArguments("Minecraft:diamond")), Map.entry("mc_get_item_texture_by_id", itemIdArguments("one:two:three")), Map.entry("mc_get_item_texture_by_id", itemIdArguments(":diamond")), Map.entry("mc_get_item_texture_by_id", itemIdArguments("minecraft:")), Map.entry("mc_get_item_texture_by_id", itemIdArguments("minecraft:diamond?")));
            for (Map.Entry<String, Map<String, Object>> invalidCase : invalid) {
                CountingMcpJsonMapper mapper = new CountingMcpJsonMapper(MAPPER);
                assertThrows(IllegalArgumentException.class, () -> bindings.get(invalidCase.getKey()).invoke(mapper, invalidCase.getValue(), Cancellation.none()), invalidCase.getKey());
                Object itemId = invalidCase.getValue().get("itemId");
                int expectedConversions = itemId instanceof String && !invalidCase.getValue().containsKey("unknown") ? 1 : 0;
                assertEquals(expectedConversions, mapper.convertValueCalls(), invalidCase.getKey() + " mapper conversion count");
            }
            assertEquals(List.of(), harness.requests());
        }
    }

    @Test
    void itemTextureArgumentRecordsEnforceConstructionInvariantsAndRejectInvalidInputs() {
        assertEquals(int.class, ItemTextureArguments.class.getRecordComponents()[0].getType());
        assertEquals(ResourceIdentifier.class, ItemTextureByIdArguments.class.getRecordComponents()[0].getType());
        assertThrows(IllegalArgumentException.class, () -> new ItemTextureArguments(-1));
        assertThrows(IllegalArgumentException.class, () -> new ItemTextureArguments(41));
        assertThrows(IllegalArgumentException.class, () -> new ItemTextureByIdArguments(null));
    }

    @Test
    void itemTextureByIdDecodesResourceIdentifierAndProjectsItsCanonicalValue() {
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, _) -> CompletableFuture.failedFuture(new AssertionError("No bridge call should occur in this test")))) {
            ToolInput<ItemTextureByIdArguments> input = typedInput(RuntimeToolModule.handlers(harness.session(), MAPPER, ENVIRONMENT).get("mc_get_item_texture_by_id"), ItemTextureByIdArguments.class);

            ItemTextureByIdArguments arguments = input.decode(MAPPER, Map.of("itemId", "diamond"));

            assertEquals("minecraft:diamond", arguments.itemId().value());
        }
    }

    private static Map<String, Object> itemIdArguments(Object itemId) {
        var arguments = new java.util.LinkedHashMap<String, Object>();
        arguments.put("itemId", itemId);
        return arguments;
    }

    private static Map<String, Object> mapWithNull(String... names) {
        var arguments = new java.util.LinkedHashMap<String, Object>();
        arguments.put("frames", 2);
        for (String name : names) {
            arguments.put(name, null);
        }
        return arguments;
    }

    @Test
    void outOfSchemaValuesFailBeforeAnyBridgeCall() throws Exception {
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, _) -> CompletableFuture.failedFuture(new AssertionError("No bridge call should occur on invalid arguments")))) {
            ToolCatalog catalog = ToolCatalog.load(ENVIRONMENT, CompleteToolBindings.including(MAPPER, RuntimeToolModule.handlers(harness.session(), MAPPER, ENVIRONMENT)), MAPPER);

            assertInvalidArgumentsReject(catalog, harness, "mc_run_command", Map.of());
            assertInvalidArgumentsReject(catalog, harness, "mc_entity_details", Map.of("entityId", "42"));
            assertInvalidArgumentsReject(catalog, harness, "mc_block_details", Map.of("position", Map.of("x", "1", "y", 2, "z", 3)));
            assertInvalidArgumentsReject(catalog, harness, "mc_set_entity_glow", Map.of("entityId", 44, "glow", "false"));
            assertInvalidArgumentsReject(catalog, harness, "mc_set_block_glow", Map.of("position", Map.of("x", 1, "y", 2, "z", 3), "glow", 1));
            assertInvalidArgumentsReject(catalog, harness, "mc_nearby_entities", Map.of("range", "64"));
            assertInvalidArgumentsReject(catalog, harness, "mc_screen_inspect", Map.of("includeIcons", "true"));
            assertInvalidArgumentsReject(catalog, harness, "mc_screenshot", Map.of("downscale", "2"));
            assertInvalidArgumentsReject(catalog, harness, "mc_record_video", Map.of("frames", 2, "interval", "50"));
            assertInvalidArgumentsReject(catalog, harness, "mc_record_video", Map.of("frames", 2, "interval", Map.of("kind", "unknown", "intervalSeconds", 0.05)));
            assertInvalidArgumentsReject(catalog, harness, "mc_record_video", Map.of("frames", 2, "interval", Map.of("kind", "text", "value", "frame")));
            assertInvalidArgumentsReject(catalog, harness, "mc_snapshot", Map.of("unknown", true));
            assertInvalidArgumentsReject(catalog, harness, "mc_record_video", Map.of("frames", 2, "unknown", true));
            assertInvalidArgumentsReject(catalog, harness, "mc_record_video", Map.of("frames", 2, "interval", Map.of("kind", "frame", "unknown", true)));

            assertEquals(List.of(), harness.requests());
        }

        Path recordVideoTool = RUNTIME_SOURCES.resolve("McRecordVideoTool.java");
        Path recordVideoArguments = RUNTIME_SOURCES.resolve("RecordVideoArguments.java");
        assertFalse(Files.readString(recordVideoTool).contains("ArgumentDecoder"));
        assertFalse(Files.readString(recordVideoTool).contains("ToolBinding.compatibility"));
        assertFalse(Files.exists(RUNTIME_SOURCES.resolve("RecordVideoWireArguments.java")));
        assertFalse(Files.readString(recordVideoArguments).contains("RecordVideoWireArguments"));
        var creator = java.util.Arrays.stream(RecordVideoArguments.class.getDeclaredMethods()).filter(method -> Modifier.isStatic(method.getModifiers()) && RecordVideoArguments.class.isAssignableFrom(method.getReturnType())).findFirst().orElseThrow();
        assertEquals(com.fasterxml.jackson.annotation.JsonCreator.Mode.PROPERTIES, creator.getAnnotation(com.fasterxml.jackson.annotation.JsonCreator.class).mode());
    }

    @Test
    void migratedSourcesCannotReintroduceWireRecordsOrCompatibilityDecoders() throws Exception {
        for (BindingExpectation expectation : allMigratedBindings()) {
            Path toolSource = RUNTIME_SOURCES.resolve(expectation.toolClassName() + ".java");
            String toolText = Files.readString(toolSource);
            assertFalse(toolText.contains("ArgumentDecoder"), expectation.toolName() + " reintroduced ArgumentDecoder");
            assertFalse(toolText.contains("ToolBinding.compatibility"), expectation.toolName() + " reintroduced a compatibility binding");

            Path argumentSource = RUNTIME_SOURCES.resolve(expectation.argumentType().getSimpleName() + ".java");
            assertTrue(Files.isRegularFile(argumentSource), "Missing final argument source: " + argumentSource);
            assertTrue(expectation.argumentType().isRecord(), expectation.argumentType().getSimpleName() + " must be a record");
            assertTrue(Modifier.isFinal(expectation.argumentType().getModifiers()), expectation.argumentType().getSimpleName() + " must be final");
            assertFalse(java.util.Arrays.stream(expectation.argumentType().getDeclaredMethods()).anyMatch(method -> Modifier.isStatic(method.getModifiers()) && expectation.argumentType().isAssignableFrom(method.getReturnType()) && (method.getAnnotation(com.fasterxml.jackson.annotation.JsonCreator.class) == null || method.getAnnotation(com.fasterxml.jackson.annotation.JsonCreator.class).mode() != com.fasterxml.jackson.annotation.JsonCreator.Mode.PROPERTIES)), expectation.argumentType().getSimpleName() + " reintroduced a static converter");

            if (expectation.legacyWireName() != null) {
                assertFalse(Files.exists(RUNTIME_SOURCES.resolve(expectation.legacyWireName() + ".java")), expectation.legacyWireName() + " must stay deleted");
                assertFalse(toolText.contains(expectation.legacyWireName()), expectation.toolName() + " references its deleted wire record");
                assertFalse(Files.readString(argumentSource).contains(expectation.legacyWireName()), expectation.argumentType().getSimpleName() + " references its deleted wire record");
            }
        }
    }

    @Test
    void unknownRuntimePropertiesFailBeforeMapperConversion() {
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, _) -> CompletableFuture.failedFuture(new AssertionError("No bridge call should occur on unknown arguments")))) {
            Map<String, ToolBinding<?>> bindings = RuntimeToolModule.handlers(harness.session(), MAPPER, ENVIRONMENT);
            List<Map.Entry<String, Map<String, Object>>> cases = List.of(Map.entry("mc_snapshot", Map.of("unknown", true)), Map.entry("mc_nearby_entities", Map.of("unknown", true)), Map.entry("mc_record_video", Map.of("frames", 2, "interval", Map.of("kind", "frame", "unknown", true))));
            for (Map.Entry<String, Map<String, Object>> testCase : cases) {
                CountingMcpJsonMapper mapper = new CountingMcpJsonMapper(MAPPER);
                assertThrows(IllegalArgumentException.class, () -> bindings.get(testCase.getKey()).invoke(mapper, testCase.getValue(), Cancellation.none()));
                assertEquals(0, mapper.convertValueCalls(), testCase.getKey() + " must reject before mapping");
            }
            assertEquals(List.of(), harness.requests());
        }
    }

    private static BindingExpectation expectation(String toolName, Class<?> argumentType, String toolClassName, String legacyWireName, Map<String, String> propertyTypes, List<String> requiredProperties) {
        return new BindingExpectation(toolName, argumentType, toolClassName, legacyWireName, propertyTypes, requiredProperties);
    }

    private static void assertTypedInputBinding(Map<String, ToolBinding<?>> bindings, BindingExpectation expectation) {
        ToolBinding<?> binding = assertInstanceOf(ToolBinding.class, bindings.get(expectation.toolName()), () -> "Missing binding: " + expectation.toolName());
        ToolInput<?> input = binding.input();
        assertEquals(expectation.argumentType(), input.type().rawClass(), expectation.toolName() + " input type");

        Map<String, Object> schema = input.schema().value();
        assertEquals("object", schema.get("type"), expectation.toolName());
        assertEquals(false, schema.get("additionalProperties"), expectation.toolName() + " must be closed");
        Object rawProperties = schema.get("properties");
        assertInstanceOf(Map.class, rawProperties, expectation.toolName() + " properties");
        Map<?, ?> properties = (Map<?, ?>) rawProperties;
        assertEquals(expectation.propertyTypes().keySet(), properties.keySet(), expectation.toolName() + " property names");
        expectation.propertyTypes().forEach((name, type) -> {
            Object rawProperty = properties.get(name);
            assertInstanceOf(Map.class, rawProperty, expectation.toolName() + '.' + name);
            assertEquals(type, ((Map<?, ?>) rawProperty).get("type"), expectation.toolName() + '.' + name);
        });

        if (expectation.requiredProperties().isEmpty()) {
            assertFalse(schema.containsKey("required"), expectation.toolName() + " should not have required fields");
        }
        else {
            assertEquals(expectation.requiredProperties(), schema.get("required"), expectation.toolName() + " required properties");
        }
    }

    private static void assertValidDispatch(String toolName, Map<String, Object> arguments, String endpoint, Map<String, Object> expectedPayload) throws Exception {
        var mapper = new CountingMcpJsonMapper(MAPPER);
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, request) -> successfulResponse(request))) {
            ToolBinding<?> binding = RuntimeToolModule.handlers(harness.session(), MAPPER, ENVIRONMENT).get(toolName);
            assertNotNull(binding, "Missing binding: " + toolName);

            ToolResult<?> result = binding.invoke(mapper, arguments, Cancellation.none()).toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertFalse(result.isError(), toolName);
            assertFalse(result.content().isEmpty(), toolName + " result content");
            assertEquals(1, mapper.convertValueCalls(), toolName + " mapper conversion count");
            List<BridgeRequest> requests = harness.requests();
            assertFalse(requests.isEmpty(), toolName + " made no bridge request");
            assertEquals(EmptyBridgePayload.class, requests.getFirst().payload().getClass(), toolName + " connection status payload type");
            BridgeRequest target = requests.getLast();
            assertEquals(endpoint, target.endpoint().wireName(), toolName);
            assertEquals(expectedPayloadType(toolName, arguments), target.payload().getClass(), toolName + " payload type");
            assertEquals(expectedPayload, mapPayload(target.payload()), toolName);
        }
    }

    private static void assertQueryDispatch(String toolName, Map<String, Object> arguments, String endpoint, Map<String, Object> expectedPayload) throws Exception {
        var mapper = new CountingMcpJsonMapper(MAPPER);
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, request) -> successfulQueryResponse(request))) {
            ToolBinding<?> binding = RuntimeToolModule.handlers(harness.session(), MAPPER, ENVIRONMENT).get(toolName);
            assertNotNull(binding, "Missing binding: " + toolName);

            ToolResult<?> result = binding.invoke(mapper, arguments, Cancellation.none()).toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertFalse(result.isError(), toolName);
            assertFalse(result.content().isEmpty(), toolName + " result content");
            assertEquals(1, mapper.convertValueCalls(), toolName + " mapper conversion count");
            List<BridgeRequest> requests = harness.requests();
            assertEquals(endpoint.equals("status") ? 1 : 2, requests.size(), toolName + " bridge request count");
            assertEquals("status", requests.getFirst().endpoint().wireName(), toolName + " status request");
            assertEquals(EmptyBridgePayload.class, requests.getFirst().payload().getClass(), toolName + " status payload type");
            assertEquals(endpoint, requests.getLast().endpoint().wireName(), toolName);
            assertEquals(expectedPayloadType(toolName, arguments), requests.getLast().payload().getClass(), toolName + " payload type");
            assertEquals(expectedPayload, mapPayload(requests.getLast().payload()), toolName + " payload");
        }
    }

    private static void assertRecordVideoDispatch(Map<String, Object> arguments, Map<String, Object> expectedPayload) throws Exception {
        var mapper = new CountingMcpJsonMapper(MAPPER);
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, request) -> successfulRecordVideoResponse(request))) {
            ToolBinding<?> binding = RuntimeToolModule.handlers(harness.session(), MAPPER, ENVIRONMENT).get("mc_record_video");
            ToolResult<?> result = binding.invoke(mapper, arguments, Cancellation.none()).toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertFalse(result.isError());
            assertEquals(1, mapper.convertValueCalls());
            assertEquals(arguments.get("interval") instanceof Map<?, ?> interval && "fixed".equals(interval.get("kind")) ? RecordVideoTimedPayload.class : RecordVideoFramePayload.class, harness.requests().getLast().payload().getClass());
            Map<String, Object> actualPayload = mapPayload(harness.requests().getLast().payload());
            assertEquals(MAPPER.writeValueAsString(new java.util.TreeMap<>(expectedPayload)), MAPPER.writeValueAsString(new java.util.TreeMap<>(actualPayload)));
        }
    }

    private static Map<String, Object> mapPayload(Object payload) {
        return MAPPER.convertValue(payload, new TypeRef<>() {
        });
    }

    private static Class<? extends dev.mcdevmcp.bridge.BridgePayload> expectedPayloadType(String toolName, Map<String, Object> arguments) {
        return switch (toolName) {
            case "mc_connect", "mc_snapshot", "mc_clear_block_glow", "mc_leave_server" -> EmptyBridgePayload.class;
            case "mc_execute" -> ExecutePayload.class;
            case "mc_join_server" -> JoinServerPayload.class;
            case "mc_run_command" -> RunCommandPayload.class;
            case "mc_screenshot" -> ScreenshotPayload.class;
            case "mc_entity_details" -> EntityDetailsPayload.class;
            case "mc_block_details" -> BlockDetailsPayload.class;
            case "mc_set_entity_glow" -> SetEntityGlowPayload.class;
            case "mc_set_block_glow" -> SetBlockGlowPayload.class;
            case "mc_get_item_texture" -> ItemTexturePayload.class;
            case "mc_get_entity_item_texture" -> EntityItemTexturePayload.class;
            case "mc_get_item_texture_by_id" -> ItemTextureByIdPayload.class;
            case "mc_nearby_entities" -> NearbyEntitiesPayload.class;
            case "mc_nearby_blocks" -> NearbyBlocksPayload.class;
            case "mc_looked_at_entity" -> LookedAtEntityPayload.class;
            case "mc_chat_history" -> ChatHistoryPayload.class;
            case "mc_screen_inspect" -> ScreenInspectPayload.class;
            default -> throw new AssertionError("Unhandled payload type for " + toolName + " " + arguments);
        };
    }

    private static CompletableFuture<BridgeResponse> successfulResponse(BridgeRequest request) {
        return switch (request.endpoint().wireName()) {
            case "status" -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id()));
            case "getItemTexture", "getEntityItemTexture", "getItemTextureById" ->
                    CompletableFuture.completedFuture(new BridgeResponse(request.id(), true, true, Map.of("base64Png", "iVBORw0KGgo=", "width", 16, "height", 16, "spriteName", "minecraft:item/diamond"), null, null));
            case "screenshot" ->
                    CompletableFuture.completedFuture(new BridgeResponse(request.id(), true, true, Map.of("path", RuntimeContractFixtures.fixturePath("C:\\Game\\shot.jpg"), "width", 1920, "height", 1080, "sizeBytes", 1536, "mimeType", "image/jpeg"), null, null));
            default ->
                    CompletableFuture.completedFuture(new BridgeResponse(request.id(), true, true, Map.of("ok", true), null, null));
        };
    }

    private static CompletableFuture<BridgeResponse> successfulQueryResponse(BridgeRequest request) {
        if (request.endpoint().wireName().equals("status")) {
            return CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id()));
        }
        Object result = request.endpoint().wireName().equals("lookedAtEntity") ? Map.of("entityId", 7) : Map.of("ok", true);
        return CompletableFuture.completedFuture(new BridgeResponse(request.id(), true, true, result, null, null));
    }

    private static CompletableFuture<BridgeResponse> successfulRecordVideoResponse(BridgeRequest request) {
        if (request.endpoint().wireName().equals("status")) {
            return CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id()));
        }
        return CompletableFuture.completedFuture(new BridgeResponse(request.id(), true, true, Map.ofEntries(Map.entry("mode", "grid"), Map.entry("path", RuntimeContractFixtures.fixturePath("C:\\Game\\grid.jpg")), Map.entry("mimeType", "image/jpeg"), Map.entry("width", 640), Map.entry("height", 360), Map.entry("sizeBytes", 2560), Map.entry("frameCount", 4), Map.entry("frameWidth", 320), Map.entry("frameHeight", 180), Map.entry("gridCols", 2), Map.entry("gridRows", 2), Map.entry("captureMs", 200), Map.entry("intervalMs", 50), Map.entry("dropped", 0)), null, null));
    }

    private static void assertInvalidArgumentsReject(ToolCatalog catalog, BridgeTestHarness harness, String tool, Map<String, Object> arguments) throws Exception {
        ToolResult<?> result = catalog.dispatch(tool, arguments, Cancellation.none()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(result.isError(), () -> "Expected invalid arguments for " + tool);
        assertEquals(List.of(), harness.requests(), () -> "Expected no bridge calls for " + tool + " with invalid arguments");
    }

    private record BindingExpectation(String toolName, Class<?> argumentType, String toolClassName, String legacyWireName, Map<String, String> propertyTypes, List<String> requiredProperties) {
    }
}

package dev.mcdevmcp.mcp.tool;

import dev.mcdevmcp.mcp.McpContractTestSupport;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolCancellation;
import dev.mcdevmcp.mcp.tool.api.ToolHandlers;
import dev.mcdevmcp.mcp.tool.api.ToolInput;
import dev.mcdevmcp.mcp.tool.api.ToolOutput;
import dev.mcdevmcp.mcp.tool.api.ToolOutputBinding;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.mcp.tool.api.JsonValueSchema;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCatalogContractTest {
    private static String contentText(ToolResult<?> result) {
        return assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text();
    }

    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();
    private static final ToolInput<TestEmptyArguments> EMPTY_INPUT = ToolInput.of(TestEmptyArguments.class, dev.mcdevmcp.mcp.tool.api.RecordInputSchemaFactory.standard());

    private static List<Map<String, Object>> contractTools(String name) throws Exception {
        return MAPPER.convertValue(McpContractTestSupport.readContract(name).get("result"), new TypeRef<Map<String, List<Map<String, Object>>>>() {
        }).get("tools");
    }

    private static List<Map<String, Object>> toToolList(List<ToolDefinition> definitions) {
        return definitions.stream().map(definition -> Map.of("name", definition.name(), "description", definition.description(), "inputSchema", definition.inputSchema())).toList();
    }

    private static ToolMetadata[] metadata(String... names) {
        var metadata = new ArrayList<ToolMetadata>();
        for (String name : names) {
            metadata.add(new ToolMetadata(name, "description"));
        }
        return metadata.toArray(ToolMetadata[]::new);
    }

    private static ToolBinding<TestEmptyArguments> binding() {
        return ToolBinding.content(EMPTY_INPUT, (_, _) -> ToolHandlers.completed(ToolResult.text("ok")));
    }

    private static ToolCatalog productionCatalog(AppEnvironment environment) {
        return ToolCatalog.load(environment, CompleteToolBindings.including(MAPPER, Map.of()), MAPPER);
    }

    @Test
    void defaultToolListMatchesTheNodeContract() throws Exception {
        var catalog = productionCatalog(new AppEnvironment(Map.of()));

        assertEquals(McpContractTestSupport.normalize(contractTools("tools-list-default.json")), McpContractTestSupport.normalize(toToolList(catalog.enabledDefinitions())));
        assertFalse(catalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_script_logs")));
        assertFalse(catalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_run_command")));
    }

    @Test
    void devEnabledToolListMatchesTheNodeContractInExactMetadataOrder() throws Exception {
        var catalog = productionCatalog(new AppEnvironment(Map.of("MCDEV_SESSION_LOG_DIR", "/tmp/mcdev/session-logs", "MCDEV_RUN_COMMAND", "1")));

        assertEquals(McpContractTestSupport.normalize(contractTools("tools-list-dev.json")), McpContractTestSupport.normalize(toToolList(catalog.enabledDefinitions())));
        assertTrue(catalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_record_video")));
    }

    @Test
    void actualProductionInputsExhaustivelyOwnBothFrozenToolLists() throws Exception {
        var defaultCatalog = productionCatalog(new AppEnvironment(Map.of()));
        var devCatalog = productionCatalog(new AppEnvironment(Map.of("MCDEV_SESSION_LOG_DIR", "/tmp/mcdev/session-logs", "MCDEV_RUN_COMMAND", "1")));
        var defaultDefinitions = defaultCatalog.definitions();
        var devDefinitions = devCatalog.definitions();

        assertEquals(33, defaultDefinitions.size());
        assertEquals(33, devDefinitions.size());
        assertEquals(McpContractTestSupport.normalize(contractTools("tools-list-default.json")), McpContractTestSupport.normalize(toToolList(defaultDefinitions.stream().filter(definition -> !definition.name().equals("mc_script_logs") && !definition.name().equals("mc_run_command")).toList())));
        assertEquals(McpContractTestSupport.normalize(contractTools("tools-list-dev.json")), McpContractTestSupport.normalize(toToolList(devDefinitions)));
        for (int index = 0; index < devDefinitions.size(); index++) {
            ToolDefinition definition = devDefinitions.get(index);
            ToolBinding<?> binding = devCatalog.binding(definition.name());
            assertNotNull(binding.input(), () -> "missing typed input: " + definition.name());
            assertNotNull(binding.input().schema(), () -> "missing generated schema: " + definition.name());
            assertSame(definition.input(), binding.input(), () -> "definition input is not the declared production input: " + definition.name());
            assertEquals(binding.input().schema().value(), definition.inputSchema(), () -> "definition schema is not generated from its input: " + definition.name());
            assertEquals(definition.name(), defaultDefinitions.get(index).name());
            assertEquals(definition.description(), defaultDefinitions.get(index).description());
            assertEquals(definition.inputSchema(), defaultDefinitions.get(index).inputSchema());
        }
    }

    @Test
    void retainedScriptLogSwitchEnablesTheNodeContractTool() {
        var catalog = productionCatalog(new AppEnvironment(Map.of("MCDEV_SCRIPT_LOGS", "1")));

        assertTrue(catalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_script_logs")));
        assertFalse(catalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_run_command")));
    }

    @Test
    void availabilityGatesTreatBlankLogPathAndUntrimmedRunCommandCorrectly() {
        var catalog = productionCatalog(new AppEnvironment(Map.of("MCDEV_SESSION_LOG_DIR", "   ", "MCDEV_RUN_COMMAND", "TRUE")));

        assertFalse(catalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_script_logs")));
        assertTrue(catalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_run_command")));
    }

    @Test
    void unknownToolReturnsTheNodeCompatibilityError() {
        var result = productionCatalog(new AppEnvironment(Map.of())).dispatch("not_a_tool", Map.of(), Cancellation.none()).toCompletableFuture().resultNow();

        assertTrue(result.isError());
        assertEquals("Unknown tool: not_a_tool", contentText(result));
    }

    @Test
    void startupRejectsMissingToolHandlers() {
        var exception = assertThrows(IllegalArgumentException.class, () -> ToolCatalog.fromMetadata(new AppEnvironment(Map.of()), MAPPER, metadata("mc_version"), List.of()));

        assertEquals("Missing tool handler: mc_version", exception.getMessage());
    }

    @Test
    void startupRejectsDuplicateMetadataHandlersAndMetadataOnlyInputs() {
        List<Map.Entry<String, ToolBinding<?>>> duplicateHandlers = List.of(new AbstractMap.SimpleImmutableEntry<>("mc_version", binding()), new AbstractMap.SimpleImmutableEntry<>("mc_version", binding()));

        assertEquals("Duplicate tool metadata: mc_version", assertThrows(IllegalArgumentException.class, () -> ToolCatalog.fromMetadata(new AppEnvironment(Map.of()), MAPPER, metadata("mc_version", "mc_version"), List.of())).getMessage());
        assertEquals("Duplicate tool handler: mc_version", assertThrows(IllegalArgumentException.class, () -> ToolCatalog.fromMetadata(new AppEnvironment(Map.of()), MAPPER, metadata("mc_version"), duplicateHandlers)).getMessage());
        assertEquals("Handler without tool metadata: missing", assertThrows(IllegalArgumentException.class, () -> ToolCatalog.fromMetadata(new AppEnvironment(Map.of()), MAPPER, metadata("mc_version"), Map.<String, ToolBinding<?>>of("missing", binding()).entrySet())).getMessage());

        var metadataOnly = new AbstractMap.SimpleImmutableEntry<String, ToolBinding<?>>("mc_version", null);
        assertThrows(NullPointerException.class, () -> ToolCatalog.fromMetadata(new AppEnvironment(Map.of()), MAPPER, metadata("mc_version"), List.of(metadataOnly)));
    }

    @Test
    void generatedSchemaIsTheOnlyDefinitionSchema() {
        ToolBinding<TestEmptyArguments> binding = binding();
        ToolCatalog catalog = ToolCatalog.fromMetadata(new AppEnvironment(Map.of()), MAPPER, metadata("mc_version"), List.of(new AbstractMap.SimpleImmutableEntry<>("mc_version", binding)));

        ToolDefinition definition = catalog.enabledDefinitions().getFirst();
        assertSame(binding, catalog.binding("mc_version"));
        assertSame(binding.input().schema().value(), definition.inputSchema());
    }

    @Test
    void declarationExposesTypedBlockingOutputBinding() {
        ToolDeclaration<TestEmptyArguments> declaration = ToolDeclaration.of("typed-output", TestEmptyArguments.class);
        ToolOutput<TestEmptyArguments> output = ToolOutput.of(TestEmptyArguments.class, JsonValueSchema.of(Map.of("type", "object")));

        ToolOutputBinding<TestEmptyArguments, TestEmptyArguments> binding = declaration.bindBlocking(output, (_, _) -> ToolResult.text("ok"));

        assertSame(output, binding.declaredOutput());
        assertSame(declaration.input(), binding.input());
    }

    @Test
    void productionCompositionRejectsDeclarationMetadataAndBindingSetDrift() {
        Map<String, ToolBinding<?>> bindings = CompleteToolBindings.including(MAPPER, Map.of());
        List<ToolDeclaration<?>> extraDeclaration = new ArrayList<>(ToolDeclarations.all());
        extraDeclaration.add(ToolDeclaration.of("extra", TestEmptyArguments.class));
        assertEquals("Typed tool declaration without metadata: extra", assertThrows(IllegalArgumentException.class, () -> ToolCatalog.load(new AppEnvironment(Map.of()), extraDeclaration, bindings, MAPPER)).getMessage());

        List<ToolDeclaration<?>> missingDeclaration = ToolDeclarations.all().stream().filter(declaration -> !declaration.name().equals("mc_snapshot")).toList();
        assertEquals("Missing typed tool declaration: mc_snapshot", assertThrows(IllegalArgumentException.class, () -> ToolCatalog.load(new AppEnvironment(Map.of()), missingDeclaration, bindings, MAPPER)).getMessage());

        var missingBinding = new java.util.LinkedHashMap<>(bindings);
        missingBinding.remove("mc_snapshot");
        assertEquals("Missing tool handler: mc_snapshot", assertThrows(IllegalArgumentException.class, () -> ToolCatalog.load(new AppEnvironment(Map.of()), ToolDeclarations.all(), missingBinding, MAPPER)).getMessage());
    }

    @Test
    void synchronousHandlerFailureUsesTheNodeErrorEnvelope() {
        var synchronous = ToolBinding.content(EMPTY_INPUT, (TestEmptyArguments _, ToolCancellation _) -> {
            throw new IllegalStateException("sync failure");
        });
        var syncCatalog = ToolCatalog.fromMetadata(new AppEnvironment(Map.of()), MAPPER, metadata("mc_version"), Map.<String, ToolBinding<?>>of("mc_version", synchronous).entrySet());

        assertEquals("Error executing mc_version: sync failure", contentText(syncCatalog.dispatch("mc_version", Map.of(), Cancellation.none()).toCompletableFuture().resultNow()));
    }

    private record TestEmptyArguments() {
    }
}

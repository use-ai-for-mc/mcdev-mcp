package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.CountingMcpJsonMapper;
import dev.mcdevmcp.mcp.tool.CompleteToolBindings;
import dev.mcdevmcp.mcp.tool.ToolCatalog;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolInput;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.Cancellation;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.SourceNamespace;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticTypedInputMigrationTest {
    private static String contentText(ToolResult<?> result) {
        return assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text();
    }

    private static final List<Expectation> EXPECTATIONS = List.of(expectation("mc_version", VersionArguments.class, "McVersionTool", Map.of("action", "string", "version", "string"), List.of("action"), Map.of("action", "list")), expectation("mc_search", SearchArguments.class, "McSearchTool", Map.of("query", "string", "type", "string", "limit", "integer", "version", "string"), List.of("query"), Map.of("query", "needle")), expectation("mc_get_class", GetClassArguments.class, "McGetClassTool", Map.of("className", "string", "view", "string", "version", "string"), List.of("className"), Map.of("className", "alpha.Alpha")), expectation("mc_get_method", GetMethodArguments.class, "McGetMethodTool", Map.of("className", "string", "methodName", "string", "version", "string"), List.of("className", "methodName"), Map.of("className", "alpha.Alpha", "methodName", "needle")), expectation("mc_list_classes", ListClassesArguments.class, "McListClassesTool", Map.of("packagePath", "string", "limit", "integer", "version", "string"), List.of("packagePath"), Map.of("packagePath", "alpha")), expectation("mc_list_packages", ListPackagesArguments.class, "McListPackagesTool", Map.of("namespace", "string", "limit", "integer", "version", "string"), List.of(), Map.of()), expectation("mc_find_hierarchy", FindHierarchyArguments.class, "McFindHierarchyTool", Map.of("className", "string", "direction", "string", "limit", "integer", "version", "string"), List.of("className", "direction"), Map.of("className", "alpha.Alpha", "direction", "subclasses")), expectation("mc_find_refs", FindRefsArguments.class, "McFindRefsTool", Map.of("className", "string", "methodName", "string", "direction", "string", "limit", "integer", "version", "string"), List.of("className", "methodName", "direction"), Map.of("className", "alpha.Alpha", "methodName", "needle", "direction", "callers")));
    private static final Map<Class<?>, Map<String, Class<?>>> EXPECTED_COMPONENT_TYPES = Map.of(VersionArguments.class, Map.of("action", VersionAction.class, "version", MinecraftVersion.class), SearchArguments.class, Map.of("query", String.class, "type", SearchType.class, "limit", Integer.class, "version", MinecraftVersion.class), GetClassArguments.class, Map.of("className", String.class, "view", ClassView.class, "version", MinecraftVersion.class), GetMethodArguments.class, Map.of("className", String.class, "methodName", String.class, "version", MinecraftVersion.class), ListClassesArguments.class, Map.of("packageName", String.class, "limit", Integer.class, "version", MinecraftVersion.class), ListPackagesArguments.class, Map.of("namespace", SourceNamespace.class, "limit", Integer.class, "version", MinecraftVersion.class), FindHierarchyArguments.class, Map.of("className", String.class, "direction", HierarchyDirection.class, "limit", Integer.class, "version", MinecraftVersion.class), FindRefsArguments.class, Map.of("className", String.class, "methodName", String.class, "direction", ReferenceDirection.class, "limit", Integer.class, "version", MinecraftVersion.class));
    private static final List<String> LEGACY_TYPES = List.of("TextArgument.java", "ArgumentShape.java", "LimitInput.java", "SourceNamespaceArgument.java", "VersionWireArguments.java", "SearchWireArguments.java", "GetClassWireArguments.java", "GetMethodWireArguments.java", "ListClassesWireArguments.java", "ListPackagesWireArguments.java", "FindHierarchyWireArguments.java", "FindRefsWireArguments.java");
    @TempDir
    Path temporaryDirectory;

    @Test
    void exposesDirectTypedInputsAndGeneratedSchemasForEveryStaticTool() {
        Map<String, ToolBinding<?>> bindings = StaticToolModule.handlers(new PlatformPaths(temporaryDirectory));
        EXPECTATIONS.forEach(expectation -> {
            ToolBinding<?> binding = bindings.get(expectation.name());
            assertNotNull(binding, expectation.name());
            ToolInput<?> input = binding.input();
            assertEquals(expectation.argumentType(), input.type().rawClass(), expectation.name());
            assertSchema(input, expectation);
            Map<String, Class<?>> componentTypes = componentTypes(expectation.argumentType());
            assertEquals(EXPECTED_COMPONENT_TYPES.get(expectation.argumentType()), componentTypes, expectation.name() + " component types");
            assertFalse(Arrays.stream(expectation.argumentType().getDeclaredMethods()).anyMatch(method -> Modifier.isStatic(method.getModifiers()) && expectation.argumentType().isAssignableFrom(method.getReturnType())), expectation.name() + " has a wire converter");
            String toolSource = source(expectation.toolClassName() + ".java");
            assertFalse(toolSource.contains("ArgumentDecoder"), expectation.name());
            assertFalse(toolSource.contains("blockingCompatibility"), expectation.name());
            assertFalse(toolSource.contains("ToolBinding.compatibility"), expectation.name());
        });
        for (String legacyType : LEGACY_TYPES) {
            assertFalse(Files.exists(sourcePath(legacyType)), legacyType + " must stay deleted");
        }
    }

    @Test
    void decodesEachStaticArgumentMapExactlyOnceIntoDomainValues() {
        Map<String, ToolBinding<?>> bindings = StaticToolModule.handlers(new PlatformPaths(temporaryDirectory));
        EXPECTATIONS.forEach(expectation -> {
            ToolInput<?> input = bindings.get(expectation.name()).input();
            var mapper = new CountingMcpJsonMapper(McpJsonDefaults.getMapper());
            Object result = input.decode(mapper, expectation.arguments());
            assertInstanceOf(expectation.argumentType(), result, expectation.name());
            assertEquals(1, mapper.convertValueCalls(), expectation.name() + " mapper conversion count");
        });
    }

    @Test
    void listClassesKeepsPackagePathOnTheWireAndPackageNameInJava() {
        ToolInput<ListClassesArguments> input = typed("mc_list_classes", ListClassesArguments.class);
        var mapper = new CountingMcpJsonMapper(McpJsonDefaults.getMapper());

        ListClassesArguments decoded = input.decode(mapper, Map.of("packagePath", "alpha"));

        assertEquals("alpha", decoded.packageName());
        assertEquals(1, mapper.convertValueCalls());
        var aliasMapper = new CountingMcpJsonMapper(McpJsonDefaults.getMapper());
        assertThrows(IllegalArgumentException.class, () -> input.decode(aliasMapper, Map.of("packageName", "alpha")));
        assertEquals(0, aliasMapper.convertValueCalls());
    }

    @Test
    void rejectsUnknownStaticPropertiesBeforeMapperConversion() {
        Map<String, ToolBinding<?>> bindings = StaticToolModule.handlers(new PlatformPaths(temporaryDirectory));
        EXPECTATIONS.forEach(expectation -> {
            ToolInput<?> input = bindings.get(expectation.name()).input();
            Map<String, Object> arguments = new LinkedHashMap<>(expectation.arguments());
            arguments.put("unknown", true);
            var mapper = new CountingMcpJsonMapper(McpJsonDefaults.getMapper());
            assertThrows(IllegalArgumentException.class, () -> input.decode(mapper, arguments), expectation.name());
            assertEquals(0, mapper.convertValueCalls(), expectation.name() + " must reject before mapping");
        });
    }

    @Test
    void rejectsNumericStringsBeforeConversion() {
        EXPECTATIONS.stream().filter(expectation -> expectation.propertyTypes().containsKey("limit")).forEach(expectation -> {
            ToolInput<?> input = typed(expectation.name(), expectation.argumentType());
            for (Object invalidLimit : List.of("3", BigDecimal.valueOf(3.9), 0, -1, Double.NaN, Double.POSITIVE_INFINITY)) {
                var mapper = new CountingMcpJsonMapper(McpJsonDefaults.getMapper());
                Map<String, Object> arguments = new LinkedHashMap<>(expectation.arguments());
                arguments.put("limit", invalidLimit);
                assertThrows(IllegalArgumentException.class, () -> input.decode(mapper, arguments), expectation.name() + " " + invalidLimit);
                assertEquals(0, mapper.convertValueCalls(), expectation.name() + " must reject before mapping");
            }
        });
    }

    @Test
    void rejectsExplicitNullsAtTheSchemaBoundary() {
        EXPECTATIONS.forEach(expectation -> {
            ToolInput<?> input = typed(expectation.name(), expectation.argumentType());
            Map<?, ?> schemaProperties = assertInstanceOf(Map.class, input.schema().value().get("properties"));
            schemaProperties.keySet().forEach(property -> {
                String propertyName = (String) property;
                Map<String, Object> arguments = new LinkedHashMap<>(expectation.arguments());
                arguments.put(propertyName, null);
                var mapper = new CountingMcpJsonMapper(McpJsonDefaults.getMapper());
                assertThrows(IllegalArgumentException.class, () -> input.decode(mapper, arguments), expectation.name() + '.' + propertyName);
                assertEquals(0, mapper.convertValueCalls(), expectation.name() + '.' + propertyName + " must reject before mapping");
            });
        });
    }

    @Test
    void decodesRealJsonIntegerFormsOnceAndDispatchesOnlyRepresentableValues() throws Exception {
        ToolInput<SearchArguments> input = typed("mc_search", SearchArguments.class);
        for (String json : List.of("{\"query\":\"needle\",\"limit\":3.0}", "{\"query\":\"needle\",\"limit\":1e3}", "{\"query\":\"needle\",\"limit\":2147483647}")) {
            Map<String, Object> arguments = McpJsonDefaults.getMapper().readValue(json, new io.modelcontextprotocol.json.TypeRef<>() {
            });
            var mapper = new CountingMcpJsonMapper(McpJsonDefaults.getMapper());
            var dispatched = new java.util.concurrent.atomic.AtomicReference<SearchArguments>();
            ToolBinding<SearchArguments> binding = ToolBinding.content(input, (decoded, _) -> {
                dispatched.set(decoded);
                return dev.mcdevmcp.mcp.tool.api.ToolHandlers.completed(ToolResult.text("ok"));
            });

            binding.invoke(mapper, arguments, dev.mcdevmcp.mcp.tool.api.ToolCancellation.none()).toCompletableFuture().join();

            assertNotNull(dispatched.get(), json);
            assertEquals(json.contains("1e3") ? 1000 : json.contains("2147483647") ? Integer.MAX_VALUE : 3, dispatched.get().limit(), json);
            assertEquals(1, mapper.convertValueCalls(), json);
        }

        Map<String, Object> positiveOverflow = McpJsonDefaults.getMapper().readValue("{\"query\":\"needle\",\"limit\":2147483648}", new io.modelcontextprotocol.json.TypeRef<>() {
        });
        var overflowMapper = new CountingMcpJsonMapper(McpJsonDefaults.getMapper());
        var overflowDispatches = new java.util.concurrent.atomic.AtomicInteger();
        ToolBinding<SearchArguments> overflowBinding = ToolBinding.content(input, (_, _) -> {
            overflowDispatches.incrementAndGet();
            return dev.mcdevmcp.mcp.tool.api.ToolHandlers.completed(ToolResult.text("unexpected"));
        });
        assertThrows(IllegalArgumentException.class, () -> overflowBinding.invoke(overflowMapper, positiveOverflow, dev.mcdevmcp.mcp.tool.api.ToolCancellation.none()));
        assertEquals(1, overflowMapper.convertValueCalls());
        assertEquals(0, overflowDispatches.get());

        Map<String, Object> belowMinimum = McpJsonDefaults.getMapper().readValue("{\"query\":\"needle\",\"limit\":-2147483649}", new io.modelcontextprotocol.json.TypeRef<>() {
        });
        var belowMapper = new CountingMcpJsonMapper(McpJsonDefaults.getMapper());
        var belowDispatches = new java.util.concurrent.atomic.AtomicInteger();
        ToolBinding<SearchArguments> belowBinding = ToolBinding.content(input, (_, _) -> {
            belowDispatches.incrementAndGet();
            return dev.mcdevmcp.mcp.tool.api.ToolHandlers.completed(ToolResult.text("unexpected"));
        });
        assertThrows(IllegalArgumentException.class, () -> belowBinding.invoke(belowMapper, belowMinimum, dev.mcdevmcp.mcp.tool.api.ToolCancellation.none()));
        assertEquals(0, belowMapper.convertValueCalls());
        assertEquals(0, belowDispatches.get());
    }

    @Test
    void rejectsAllInvalidLimitValuesBeforeMappingAndDispatch() {
        ToolInput<SearchArguments> input = typed("mc_search", SearchArguments.class);
        for (Object value : Arrays.asList(null, BigDecimal.valueOf(3.9), 0, -1, "3", Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            Map<String, Object> arguments = new LinkedHashMap<>(Map.of("query", "needle"));
            arguments.put("limit", value);
            var mapper = new CountingMcpJsonMapper(McpJsonDefaults.getMapper());
            var dispatches = new java.util.concurrent.atomic.AtomicInteger();
            ToolBinding<SearchArguments> binding = ToolBinding.content(input, (_, _) -> {
                dispatches.incrementAndGet();
                return dev.mcdevmcp.mcp.tool.api.ToolHandlers.completed(ToolResult.text("unexpected"));
            });
            assertThrows(IllegalArgumentException.class, () -> binding.invoke(mapper, arguments, dev.mcdevmcp.mcp.tool.api.ToolCancellation.none()), String.valueOf(value));
            assertEquals(0, mapper.convertValueCalls(), String.valueOf(value));
            assertEquals(0, dispatches.get(), String.valueOf(value));
        }
    }

    @Test
    void usesIntegerLimitSpecSemanticsAndContainsNoArbitraryPrecisionLimitSource() {
        LimitSpec limits = new LimitSpec(50, 1000);
        assertEquals(new NormalizedLimit(50, false, true), limits.normalize(null));
        assertEquals(new NormalizedLimit(1, false, false), limits.normalize(1));
        assertEquals(new NormalizedLimit(1000, false, false), limits.normalize(1000));
        assertEquals(new NormalizedLimit(1000, true, false), limits.normalize(1001));
        assertEquals(new NormalizedLimit(1000, true, false), limits.normalize(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> limits.normalize(0));
        assertThrows(IllegalArgumentException.class, () -> limits.normalize(-1));
        assertThrows(IllegalArgumentException.class, () -> limits.normalize(Integer.MIN_VALUE));
        for (String sourceFile : List.of("LimitSpec.java", "SearchArguments.java", "ListClassesArguments.java", "ListPackagesArguments.java", "FindHierarchyArguments.java", "FindRefsArguments.java")) {
            String source = source(sourceFile);
            assertFalse(source.contains("BigDecimal"), sourceFile);
            assertFalse(source.contains("BigInteger"), sourceFile);
        }
    }

    @Test
    void mapsAnEmptyMinecraftVersionToAbsentAndRejectsWhitespace() {
        ToolInput<SearchArguments> input = typed("mc_search", SearchArguments.class);
        var mapper = new CountingMcpJsonMapper(McpJsonDefaults.getMapper());
        SearchArguments arguments = input.decode(mapper, Map.of("query", "needle", "version", ""));
        assertNull(arguments.version());
        assertEquals(1, mapper.convertValueCalls());

        assertThrows(IllegalArgumentException.class, () -> input.decode(McpJsonDefaults.getMapper(), Map.of("query", "needle", "version", " ")));
        assertThrows(IllegalArgumentException.class, () -> new MinecraftVersion(""));
    }

    @Test
    void reportsSemanticWhitespaceVersionErrorsAtTheCatalogBoundary() {
        ToolCatalog catalog = ToolCatalog.load(new AppEnvironment(Map.of()), CompleteToolBindings.including(McpJsonDefaults.getMapper(), StaticToolModule.handlers(new PlatformPaths(temporaryDirectory))), McpJsonDefaults.getMapper());
        ToolResult<?> result = catalog.dispatch("mc_search", Map.of("query", "needle", "version", " "), Cancellation.none()).toCompletableFuture().join();
        String message = contentText(result);

        assertTrue(result.isError());
        assertEquals("Error executing mc_search: Invalid Minecraft version path component:  ", message);
        assertFalse(message.contains("dev.mcdevmcp"));
        assertFalse(message.contains("Jackson"));
        assertFalse(message.contains("com.fasterxml"));
        assertFalse(message.contains("java.lang"));
    }

    @Test
    void rejectsUnknownFindRefsDirectionAtTheCatalogBoundary() {
        ToolCatalog catalog = ToolCatalog.load(new AppEnvironment(Map.of()), CompleteToolBindings.including(McpJsonDefaults.getMapper(), StaticToolModule.handlers(new PlatformPaths(temporaryDirectory))), McpJsonDefaults.getMapper());
        ToolResult<?> result = catalog.dispatch("mc_find_refs", Map.of("className", "alpha.Alpha", "methodName", "needle", "direction", "unknown"), Cancellation.none()).toCompletableFuture().join();
        assertTrue(result.isError());
        assertEquals("Error executing mc_find_refs: 'direction' is not one of the permitted values", contentText(result));
    }

    @Test
    void rejectsMissingRequiredStaticArgumentsAtTheSchemaBoundary() {
        ToolInput<GetMethodArguments> input = typed("mc_get_method", GetMethodArguments.class);
        assertThrows(IllegalArgumentException.class, () -> input.decode(McpJsonDefaults.getMapper(), Map.of("className", "alpha.Alpha")));
        assertThrows(IllegalArgumentException.class, () -> input.decode(McpJsonDefaults.getMapper(), Map.of("methodName", "needle")));
    }

    @SuppressWarnings("unchecked")
    private static <A> ToolInput<A> typed(String name, Class<A> type) {
        ToolBinding<?> binding = StaticToolModule.handlers(new PlatformPaths(Path.of("build", "static-typed-input-test"))).get(name);
        assertNotNull(binding);
        ToolInput<?> input = binding.input();
        assertEquals(type, input.type().rawClass());
        return (ToolInput<A>) input;
    }

    private static Expectation expectation(String name, Class<?> argumentType, String toolClassName, Map<String, String> propertyTypes, List<String> requiredProperties, Map<String, Object> arguments) {
        return new Expectation(name, argumentType, toolClassName, propertyTypes, requiredProperties, arguments);
    }

    private static Map<String, Class<?>> componentTypes(Class<?> argumentType) {
        Map<String, Class<?>> componentTypes = new LinkedHashMap<>();
        for (var component : argumentType.getRecordComponents()) {
            componentTypes.put(component.getName(), component.getType());
        }
        return componentTypes;
    }

    private static void assertSchema(ToolInput<?> input, Expectation expectation) {
        Map<String, Object> schema = input.schema().value();
        assertEquals("object", schema.get("type"), expectation.name());
        assertEquals(false, schema.get("additionalProperties"), expectation.name() + " must be closed");
        Map<?, ?> properties = assertInstanceOf(Map.class, schema.get("properties"), expectation.name());
        assertEquals(expectation.propertyTypes().keySet(), properties.keySet(), expectation.name() + " property names");
        expectation.propertyTypes().forEach((name, type) -> assertEquals(type, assertInstanceOf(Map.class, properties.get(name)).get("type"), expectation.name() + '.' + name));
        switch (expectation.name()) {
            case "mc_version" ->
                    assertEquals(List.of("set", "list"), assertInstanceOf(Map.class, properties.get("action")).get("enum"));
            case "mc_search" -> {
                assertEquals(List.of("class", "method", "field"), assertInstanceOf(Map.class, properties.get("type")).get("enum"));
                assertEquals(BigDecimal.ONE, assertInstanceOf(Map.class, properties.get("limit")).get("minimum"));
            }
            case "mc_get_class" ->
                    assertEquals(List.of("summary", "methods", "fields", "full"), assertInstanceOf(Map.class, properties.get("view")).get("enum"));
            case "mc_find_refs" -> {
                assertEquals(List.of("callers", "callees"), assertInstanceOf(Map.class, properties.get("direction")).get("enum"));
                assertEquals(BigDecimal.ONE, assertInstanceOf(Map.class, properties.get("limit")).get("minimum"));
            }
            case "mc_list_packages" -> {
                assertEquals(List.of("minecraft", "fabric"), assertInstanceOf(Map.class, properties.get("namespace")).get("enum"));
                assertEquals(BigDecimal.ONE, assertInstanceOf(Map.class, properties.get("limit")).get("minimum"));
            }
            case "mc_list_classes", "mc_find_hierarchy" ->
                    assertEquals(BigDecimal.ONE, assertInstanceOf(Map.class, properties.get("limit")).get("minimum"));
            case "mc_get_method" -> {
            }
            default -> throw new AssertionError(expectation.name());
        }
        if (expectation.requiredProperties().isEmpty()) {
            assertFalse(schema.containsKey("required"), expectation.name() + " required properties");
        }
        else {
            assertEquals(expectation.requiredProperties(), schema.get("required"), expectation.name() + " required properties");
        }
    }

    private static Path sourcePath(String fileName) {
        return Path.of("src", "main", "java", "dev", "mcdevmcp", "tools", "statictool", fileName);
    }

    private static String source(String fileName) {
        try {
            return Files.readString(sourcePath(fileName));
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private record Expectation(String name, Class<?> argumentType, String toolClassName, Map<String, String> propertyTypes, List<String> requiredProperties, Map<String, Object> arguments) {
    }
}

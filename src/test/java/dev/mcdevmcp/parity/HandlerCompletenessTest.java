package dev.mcdevmcp.parity;

import dev.mcdevmcp.bridge.BridgeTestHarness;
import dev.mcdevmcp.mcp.tool.*;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.tools.runtime.RuntimeToolModule;
import dev.mcdevmcp.tools.statictool.StaticToolModule;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class HandlerCompletenessTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();

    @TempDir
    Path temporaryDirectory;

    private static void assertSupportedGeneric(String fieldName, boolean rejected) throws ReflectiveOperationException {
        Type type = fieldName.equals("rawCollection") ? new SubstitutedParameterizedType(null, GenericInput.class, new Type[]{Collection.class}) : GenericInputs.class.getDeclaredField(fieldName).getGenericType();
        if (rejected) {
            assertThrowsAssertion(() -> assertSupportedInputType(type, "synthetic." + fieldName, new HashSet<>()));
        }
        else {
            assertSupportedInputType(type, "synthetic." + fieldName, new HashSet<>());
        }
    }

    private static void assertThrowsAssertion(Runnable assertion) {
        try {
            assertion.run();
        } catch (AssertionError expected) {
            return;
        }
        throw new AssertionError("Expected synthetic forbidden input type to be rejected");
    }

    private static void assertGeneratedRecordRoot(String name, Class<?> rootType, Map<String, Object> schema) {
        assertEquals("object", schema.get("type"), () -> "record input root must generate an object schema: " + name);
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"), () -> "record input root must be closed: " + name);
        assertTrue(schema.containsKey("properties"), () -> "record input root must declare properties: " + name);
        assertEquals(rootType.getRecordComponents().length, ((Map<?, ?>) schema.get("properties")).size(), () -> "generated properties must cover every record component: " + name);
    }

    private static void assertSupportedInputComponents(Type recordType, Set<Class<?>> activeRecords, String toolName) {
        Class<?> rawRecordType = rawClass(recordType);
        assertNotNull(rawRecordType, () -> "input record type must be inspectable: " + toolName + " -> " + recordType.getTypeName());
        assertTrue(rawRecordType.isRecord(), () -> "input nested type must be a record: " + toolName + " -> " + recordType.getTypeName());
        if (!activeRecords.add(rawRecordType)) {
            return;
        }
        try {
            Map<TypeVariable<?>, Type> substitutions = typeSubstitutions(recordType, rawRecordType);
            for (var component : rawRecordType.getRecordComponents()) {
                assertSupportedInputType(substitute(component.getGenericType(), substitutions), toolName + "." + component.getName(), activeRecords);
            }
        } finally {
            activeRecords.remove(rawRecordType);
        }
    }

    private static void assertSupportedInputType(Type type, String location, Set<Class<?>> activeRecords) {
        if (type instanceof GenericArrayType arrayType) {
            assertSupportedInputType(arrayType.getGenericComponentType(), location + "[]", activeRecords);
            return;
        }
        Class<?> rawType = rawClass(type);
        assertNotNull(rawType, () -> "input component type must be inspectable: " + location + " -> " + type.getTypeName());
        assertNotSame(Object.class, rawType, () -> "raw Object input component: " + location);
        assertNotSame(BigDecimal.class, rawType, () -> "BigDecimal input component: " + location);
        assertNotSame(BigInteger.class, rawType, () -> "BigInteger input component: " + location);
        assertNotSame(Number.class, rawType, () -> "general Number input component: " + location);
        assertFalse(Map.class.isAssignableFrom(rawType), () -> "Map input component: " + location);

        if (type instanceof ParameterizedType parameterized) {
            Type owner = parameterized.getOwnerType();
            if (owner != null) {
                assertSupportedInputType(owner, location + ".owner", activeRecords);
            }
            for (Type argument : parameterized.getActualTypeArguments()) {
                assertSupportedInputType(argument, location + ".argument", activeRecords);
            }
            if (Collection.class.isAssignableFrom(rawType)) {
                assertEquals(1, parameterized.getActualTypeArguments().length, () -> "collection input component must have one element type: " + location);
                return;
            }
            if (rawType.isRecord()) {
                assertSupportedInputComponents(type, activeRecords, location);
            }
            else {
                throw new AssertionError("Unsupported input component type: " + type.getTypeName());
            }
            return;
        }

        if (rawType.isRecord()) {
            assertSupportedInputComponents(type, activeRecords, location);
            return;
        }

        if (type instanceof Class<?> classType) {
            assertFalse(Collection.class.isAssignableFrom(classType), () -> "raw Collection input component: " + location);
            if (classType.isArray()) {
                assertSupportedInputType(classType.getComponentType(), location + "[]", activeRecords);
            }
            return;
        }

        throw new AssertionError("Unsupported input component type: " + type.getTypeName());
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> classType) {
            return classType;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> rawType) {
            return rawType;
        }
        return null;
    }

    private static Map<TypeVariable<?>, Type> typeSubstitutions(Type recordType, Class<?> rawRecordType) {
        if (!(recordType instanceof ParameterizedType parameterized)) {
            return Map.of();
        }
        TypeVariable<?>[] variables = rawRecordType.getTypeParameters();
        Type[] arguments = parameterized.getActualTypeArguments();
        if (variables.length != arguments.length) {
            throw new AssertionError("Generic record type argument count mismatch: " + recordType.getTypeName());
        }
        Map<TypeVariable<?>, Type> substitutions = new HashMap<>();
        for (int index = 0; index < variables.length; index++) {
            substitutions.put(variables[index], substitute(arguments[index], substitutions));
        }
        return substitutions;
    }

    private static Type substitute(Type type, Map<TypeVariable<?>, Type> substitutions) {
        return substitute(type, substitutions, new HashSet<>());
    }

    private static Type substitute(Type type, Map<TypeVariable<?>, Type> substitutions, Set<TypeVariable<?>> activeVariables) {
        if (type instanceof TypeVariable<?> variable) {
            if (!activeVariables.add(variable)) {
                return variable;
            }
            Type replacement = substitutions.get(variable);
            Type resolved = replacement == null ? type : substitute(replacement, substitutions, activeVariables);
            activeVariables.remove(variable);
            return resolved;
        }
        if (type instanceof ParameterizedType parameterized) {
            Type owner = parameterized.getOwnerType() == null ? null : substitute(parameterized.getOwnerType(), substitutions, activeVariables);
            Type[] arguments = parameterized.getActualTypeArguments();
            Type[] resolved = new Type[arguments.length];
            for (int index = 0; index < arguments.length; index++) {
                resolved[index] = substitute(arguments[index], substitutions, activeVariables);
            }
            return new SubstitutedParameterizedType(owner, parameterized.getRawType(), resolved);
        }
        if (type instanceof GenericArrayType arrayType) {
            Type component = substitute(arrayType.getGenericComponentType(), substitutions, activeVariables);
            return new SubstitutedGenericArrayType(component);
        }
        return type;
    }

    @Test
    void everyMetadataEntryHasExactlyOneBoundHandlerIncludingEnvironmentGatedTools() {
        var environment = new AppEnvironment(Map.of("LOCALAPPDATA", temporaryDirectory.toString(), "XDG_CACHE_HOME", temporaryDirectory.toString(), "MCDEV_SESSION_LOG_DIR", temporaryDirectory.resolve("session-logs").toString(), "MCDEV_RUN_COMMAND", "true"));
        var handlers = new LinkedHashMap<String, ToolBinding<?>>();

        StaticToolModule.handlers(PlatformPaths.forEnvironment("Linux", environment.values(), temporaryDirectory)).forEach((name, binding) -> assertNull(handlers.put(name, binding), () -> "duplicate static handler: " + name));
        try (var bridge = new BridgeTestHarness(MAPPER, environment, (_, _) -> {
            throw new AssertionError("The bridge is never called");
        })) {
            RuntimeToolModule.handlers(bridge.session(), MAPPER, environment).forEach((name, binding) -> assertNull(handlers.put(name, binding), () -> "duplicate runtime handler: " + name));
        }

        ToolMetadata[] metadata = ToolCatalog.loadMetadata(MAPPER);
        Set<String> metadataNames = Arrays.stream(metadata).map(ToolMetadata::name).collect(Collectors.toUnmodifiableSet());
        assertEquals(metadata.length, metadataNames.size(), "tool metadata must not contain duplicate names");
        assertEquals(metadataNames, handlers.keySet());
        var declarations = ToolDeclarations.all();

        var catalog = ToolCatalog.load(environment, handlers, MAPPER);
        var definitions = new LinkedHashMap<String, ToolDefinition>();
        for (var definition : catalog.enabledDefinitions()) {
            assertNull(definitions.put(definition.name(), definition), () -> "duplicate enabled definition: " + definition.name());
        }
        assertEquals(metadataNames, definitions.keySet(), "enabled definitions must exhaustively cover metadata");
        assertEquals(metadataNames, handlers.keySet(), "production bindings must exhaustively cover metadata");

        for (var entry : handlers.entrySet()) {
            String name = entry.getKey();
            ToolBinding<?> binding = entry.getValue();
            ToolDeclaration<?> declaration = declarations.stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElseThrow();
            assertSame(declaration.input(), binding.input(), () -> "production binding does not use its declaration input: " + name);
            var input = binding.input();
            Class<?> rootType = input.type().rawClass();
            assertNotNull(rootType, () -> "tool input must use a reifiable root record: " + name);
            assertTrue(rootType.isRecord(), () -> "tool input root must be a record: " + name + " -> " + rootType.getTypeName());
            assertGeneratedRecordRoot(name, rootType, input.schema().value());
            assertSupportedInputComponents(rootType, new HashSet<>(), name);

            var definition = definitions.get(name);
            assertNotNull(definition, () -> "missing enabled definition: " + name);
            assertSame(binding, catalog.binding(name), () -> "catalog replaced the production binding: " + name);
            assertEquals(input.schema().value(), definition.inputSchema(), () -> "definition schema is not the generated ToolInput schema: " + name);
        }
    }

    @Test
    void genericRecordInputComponentsAreCheckedAfterTypeVariableSubstitution() throws ReflectiveOperationException {
        assertSupportedGeneric("permitted", false);
        assertSupportedGeneric("nestedPermitted", false);
        assertSupportedGeneric("list", false);
        assertSupportedGeneric("collection", false);
        assertSupportedGeneric("object", true);
        assertSupportedGeneric("bigDecimal", true);
        assertSupportedGeneric("bigInteger", true);
        assertSupportedGeneric("number", true);
        assertSupportedGeneric("map", true);
        assertSupportedGeneric("rawCollection", true);
        assertSupportedGeneric("nestedMap", true);
        assertSupportedGeneric("listOfMaps", true);
        assertSupportedGeneric("arrayBox", false);
        assertSupportedGeneric("arrayObject", true);
        assertSupportedGeneric("arrayMap", true);
        assertSupportedGeneric("ownerMap", true);
        assertSupportedGeneric("stableRecursive", false);
        assertSupportedGeneric("expandingRecursive", false);
        assertSupportedGeneric("expandingForbidden", true);
    }

    private record SubstitutedParameterizedType(Type owner, Type rawType, Type[] arguments) implements ParameterizedType {
        private SubstitutedParameterizedType {
            arguments = arguments.clone();
        }

        @Override
        public Type @NonNull [] getActualTypeArguments() {
            return arguments.clone();
        }

        @Override
        public Type getOwnerType() {
            return owner;
        }

        @Override
        @NonNull
        public Type getRawType() {
            return rawType;
        }

        @Override
        public String getTypeName() {
            return rawType.getTypeName() + "<" + Arrays.stream(arguments).map(Type::getTypeName).collect(Collectors.joining(",")) + ">";
        }
    }

    private record SubstitutedGenericArrayType(Type componentType) implements GenericArrayType {
        @Override
        @NonNull
        public Type getGenericComponentType() {
            return componentType;
        }

        @Override
        public String getTypeName() {
            return componentType.getTypeName() + "[]";
        }
    }

    private record GenericInput<T>(T value) {
    }

    private record GenericNested<T>(GenericInput<T> value) {
    }

    private record GenericList<T>(List<T> values) {
    }

    private record ArrayBox<T>(T[] values) {
    }

    private record GenericStable(GenericStable next) {
    }

    private record GenericExpanding<T>(GenericExpanding<List<T>> next) {
    }

    private static final class GenericOwner<T> {
        private abstract class Inner<U> implements Function<T, U> {
        }
    }

    private record GenericOwnerCarrier<T>(GenericOwner<T>.Inner<String> value) {
    }

    /**
     * One component per input shape under test; only their declared types matter.
     */
    private record GenericInputs(GenericInput<String> permitted, GenericNested<String> nestedPermitted, GenericList<String> list, GenericList<Collection<String>> collection, GenericInput<Object> object, GenericInput<BigDecimal> bigDecimal, GenericInput<BigInteger> bigInteger, GenericInput<Number> number, GenericInput<Map<String, String>> map, GenericNested<Map<String, String>> nestedMap, GenericList<Map<String, String>> listOfMaps, ArrayBox<String> arrayBox, ArrayBox<Object> arrayObject, ArrayBox<Map<String, String>> arrayMap, GenericOwnerCarrier<Map<String, String>> ownerMap, GenericStable stableRecursive, GenericExpanding<String> expandingRecursive, GenericExpanding<Map<String, String>> expandingForbidden) {
    }
}
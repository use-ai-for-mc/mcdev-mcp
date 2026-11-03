package dev.mcdevmcp.mcp.tool.api;

import com.fasterxml.jackson.annotation.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecordInputSchemaFactoryTest {
    @Test
    void generatesADeeplyImmutableSchemaForAnnotatedRecordComponents() {
        JsonObjectSchema schema = RecordInputSchemaFactory.standard().generate(JsonType.of(SchemaInput.class));

        assertEquals(List.of("type", "properties", "required", "additionalProperties"), List.copyOf(schema.value().keySet()));
        assertEquals(Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string", "description", "Search text", "default", "all"), "includeDetails", Map.of("type", "boolean", "default", true), "threshold", Map.of("type", "number", "minimum", new BigDecimal("0.25"), "maximum", new BigDecimal("4.50"), "default", new BigDecimal("1.50")), "mode", Map.of("type", "string", "enum", List.of("FAST", "THOROUGH"), "default", "THOROUGH"), "limit", Map.of("type", "integer", "default", new BigInteger("42")), "optionalFilter", Map.of("type", "string")), "required", List.of("query"), "additionalProperties", false), schema.value());

        Object propertiesValue = schema.value().get("properties");
        if (!(propertiesValue instanceof Map<?, ?> properties)) {
            throw new AssertionError("properties must be an object");
        }
        assertEquals(List.of("query", "includeDetails", "threshold", "mode", "limit", "optionalFilter"), List.copyOf(properties.keySet()));
        assertThrows(UnsupportedOperationException.class, () -> schema.value().put("additionalProperties", false));
        assertThrows(UnsupportedOperationException.class, properties::clear);
        Object modeValue = properties.get("mode");
        if (!(modeValue instanceof Map<?, ?> mode)) {
            throw new AssertionError("mode must be an object");
        }
        Object enumValue = mode.get("enum");
        if (!(enumValue instanceof List<?> enumValues)) {
            throw new AssertionError("enum must be an array");
        }
        assertThrows(UnsupportedOperationException.class, () -> enumValues.add(null));
    }

    /**
     * A record with a raw collection component is something these sources may not contain, so the
     * one the factory has to reject is compiled for the occasion.
     */
    @Test
    void rejectsARawCollectionComponent(@TempDir Path directory) throws Exception {
        Path source = Files.writeString(directory.resolve("RawListComponentInput.java"), "public record RawListComponentInput(java.util.List values) {}");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", directory.toString(), source.toString()));

        try (var loader = new URLClassLoader(new URL[]{directory.toUri().toURL()})) {
            Class<?> input = loader.loadClass("RawListComponentInput");
            var failure = assertThrows(IllegalArgumentException.class, () -> RecordInputSchemaFactory.standard().generate(JsonType.of(input)));
            assertEquals("Raw collection input components are unsupported: java.util.List", failure.getMessage());
        }
    }

    @Test
    void rejectsAmbiguousAndUnsupportedInputTypesAndMetadata() {
        InputSchemaFactory factory = RecordInputSchemaFactory.standard();

        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(String.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(ObjectComponentInput.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(MapComponentInput.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(WildcardListComponentInput.class)));
        Map<String, Object> nestedListSchema = new LinkedHashMap<>();
        nestedListSchema.put("type", "object");
        nestedListSchema.put("properties", Map.of("values", Map.of("type", "array", "items", Map.of("type", "array", "items", Map.of("type", "string")))));
        nestedListSchema.put("additionalProperties", false);
        assertEquals(nestedListSchema, factory.generate(JsonType.of(NestedListComponentInput.class)).value());
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(DuplicatePropertyInput.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(InvalidMinimumInput.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(ReversedBoundsInput.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(NonNumericBoundsInput.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(JsonValueWithoutDelegatingCreatorInput.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(InvalidDelegatingCreatorInput.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(DuplicateJsonPropertyEnumInput.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(DuplicateJsonValueEnumInput.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(EquivalentDecimalJsonValueEnumInput.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(SignedZeroJsonValueEnumInput.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(VisibleUnionInput.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(DefaultedUnionInput.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(OptionalTypeIdUnionInput.class)));
        assertThrows(IllegalArgumentException.class, () -> factory.generate(JsonType.of(DelegatingSubtypeUnionInput.class)));
    }

    private enum DuplicateJsonPropertyEnum {
        @JsonProperty("same") FIRST, @JsonProperty("same") SECOND
    }

    private enum DuplicateJsonValueEnum {
        FIRST, SECOND;

        @JsonValue
        String wireValue() {
            return "same";
        }
    }

    private enum EquivalentDecimalJsonValueEnum {
        FIRST(new BigDecimal("1.0")), SECOND(new BigDecimal("1.00"));

        private final BigDecimal wireValue;

        EquivalentDecimalJsonValueEnum(BigDecimal wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        BigDecimal wireValue() {
            return wireValue;
        }
    }

    private enum SignedZeroJsonValueEnum {
        NEGATIVE_ZERO(-0.0D), POSITIVE_ZERO(0.0D);

        private final double wireValue;

        SignedZeroJsonValueEnum(double wireValue) {
            this.wireValue = wireValue;
        }

        @JsonValue
        double wireValue() {
            return wireValue;
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind", visible = true)
    @JsonSubTypes(@JsonSubTypes.Type(value = VisibleUnionValue.class, name = "value"))
    private sealed interface VisibleUnion permits VisibleUnionValue {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind", defaultImpl = DefaultedUnionValue.class)
    @JsonSubTypes(@JsonSubTypes.Type(value = DefaultedUnionValue.class, name = "value"))
    private sealed interface DefaultedUnion permits DefaultedUnionValue {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind", requireTypeIdForSubtypes = OptBoolean.FALSE)
    @JsonSubTypes(@JsonSubTypes.Type(value = OptionalTypeIdUnionValue.class, name = "value"))
    private sealed interface OptionalTypeIdUnion permits OptionalTypeIdUnionValue {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
    @JsonSubTypes(@JsonSubTypes.Type(value = DelegatingSubtypeUnionValue.class, name = "value"))
    private sealed interface DelegatingSubtypeUnion permits DelegatingSubtypeUnionValue {
    }

    private record ObjectComponentInput(Object value) {
    }

    private record MapComponentInput(Map<String, String> values) {
    }

    private record WildcardListComponentInput(List<?> values) {
    }

    private record NestedListComponentInput(List<List<String>> values) {
    }

    private record DuplicatePropertyInput(@JsonProperty("same") String first, @JsonProperty("same") String second) {
    }

    private record InvalidMinimumInput(@InputProperty(minimum = "not-a-number") BigDecimal value) {
    }

    private record ReversedBoundsInput(@InputProperty(minimum = "5", maximum = "4") BigDecimal value) {
    }

    private record NonNumericBoundsInput(@InputProperty(minimum = "1") String value) {
    }

    private record JsonValueWithoutDelegatingCreatorInput(JsonValueWithoutDelegatingCreator value) {
    }

    private record JsonValueWithoutDelegatingCreator(String value) {
        @JsonValue
        String wireValue() {
            return value;
        }
    }

    private record InvalidDelegatingCreatorInput(InvalidDelegatingCreator value) {
    }

    private record InvalidDelegatingCreator(String first, String second) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        InvalidDelegatingCreator {
        }
    }

    private record DuplicateJsonPropertyEnumInput(DuplicateJsonPropertyEnum value) {
    }

    private record DuplicateJsonValueEnumInput(DuplicateJsonValueEnum value) {
    }

    private record EquivalentDecimalJsonValueEnumInput(EquivalentDecimalJsonValueEnum value) {
    }

    private record SignedZeroJsonValueEnumInput(SignedZeroJsonValueEnum value) {
    }

    private record VisibleUnionInput(VisibleUnion value) {
    }

    private record VisibleUnionValue(String value) implements VisibleUnion {
    }

    private record DefaultedUnionInput(DefaultedUnion value) {
    }

    private record DefaultedUnionValue(String value) implements DefaultedUnion {
    }

    private record OptionalTypeIdUnionInput(OptionalTypeIdUnion value) {
    }

    private record OptionalTypeIdUnionValue(String value) implements OptionalTypeIdUnion {
    }

    private record DelegatingSubtypeUnionInput(DelegatingSubtypeUnion value) {
    }

    private record DelegatingSubtypeUnionValue(String value) implements DelegatingSubtypeUnion {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        DelegatingSubtypeUnionValue {
        }
    }
}
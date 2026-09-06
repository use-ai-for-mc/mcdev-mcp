package dev.mcdevmcp.mcp.tool.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolInputTest {
    @Test
    void decodesTheCompleteArgumentMapIntoItsRecordType() {
        ToolInput<SchemaInput> input = ToolInput.of(SchemaInput.class, RecordInputSchemaFactory.standard());

        SchemaInput result = input.decode(McpJsonDefaults.getMapper(), Map.of("query", "nearby blocks", "includeDetails", true, "threshold", new BigDecimal("2.75"), "mode", "FAST", "limit", 9L, "optionalFilter", "stone"));

        assertEquals(new SchemaInput("nearby blocks", true, new BigDecimal("2.75"), InputMode.FAST, 9L, "stone"), result);
    }

    @Test
    void treatsSchemaDefaultsAsMetadataWithoutInjectingThemDuringDecode() {
        ToolInput<DefaultMetadataInput> input = ToolInput.of(DefaultMetadataInput.class, RecordInputSchemaFactory.standard());
        CountingMapper mapper = new CountingMapper();

        DefaultMetadataInput result = input.decode(mapper, Map.of());

        assertNull(result.value());
        assertEquals(1, mapper.convertValueCalls);
    }

    @Test
    void generatesScalarSchemasThatMatchDelegatingRecordAndEnumDecoding() {
        ToolInput<ScalarInput> input = ToolInput.of(ScalarInput.class, RecordInputSchemaFactory.standard());

        ScalarInput result = input.decode(McpJsonDefaults.getMapper(), Map.of("version", "1.21.1", "mode", "deep"));

        assertEquals(new ScalarInput(new WireVersion("1.21.1"), WireMode.THOROUGH), result);
        assertEquals(Map.of("type", "object", "properties", Map.of("version", Map.of("type", "string"), "mode", Map.of("type", "string", "enum", java.util.List.of("quick", "deep"))), "required", java.util.List.of("version", "mode"), "additionalProperties", false), input.schema().value());
    }

    @Test
    void rejectsUnknownPropertiesBeforeMapperConversionAtEveryObjectBoundary() {
        ToolInput<SchemaInput> rootInput = ToolInput.of(SchemaInput.class, RecordInputSchemaFactory.standard());
        CountingMapper rootMapper = new CountingMapper();
        IllegalArgumentException rootException = assertThrows(IllegalArgumentException.class, () -> rootInput.decode(rootMapper, Map.of("query", "blocks", "unknown", true)));
        assertEquals("'unknown' is not a permitted property", rootException.getMessage());
        assertEquals(0, rootMapper.convertValueCalls);

        ToolInput<NestedInput> nestedInput = ToolInput.of(NestedInput.class, RecordInputSchemaFactory.standard());
        CountingMapper nestedMapper = new CountingMapper();
        IllegalArgumentException nestedException = assertThrows(IllegalArgumentException.class, () -> nestedInput.decode(nestedMapper, Map.of("payload", Map.of("value", "blocks", "unknown", true))));
        assertEquals("'payload.unknown' is not a permitted property", nestedException.getMessage());
        assertEquals(0, nestedMapper.convertValueCalls);

        ToolInput<UnionInput> unionInput = ToolInput.of(UnionInput.class, RecordInputSchemaFactory.standard());
        CountingMapper unionMapper = new CountingMapper();
        IllegalArgumentException unionException = assertThrows(IllegalArgumentException.class, () -> unionInput.decode(unionMapper, Map.of("interval", Map.of("kind", "text", "value", "frame", "unknown", true))));
        assertEquals("'interval.unknown' is not a permitted property", unionException.getMessage());
        assertEquals(0, unionMapper.convertValueCalls);
    }

    @Test
    void decodesNumericSecondsDirectlyIntoDuration() {
        ToolInput<DurationInput> input = ToolInput.of(DurationInput.class, RecordInputSchemaFactory.standard());

        DurationInput result = input.decode(McpJsonDefaults.getMapper(), Map.of("timeoutSeconds", new BigDecimal("1.25")));

        assertEquals(new DurationInput(Duration.ofMillis(1250)), result);
        assertEquals(Map.of("type", "object", "properties", Map.of("timeoutSeconds", Map.of("type", "number")), "required", java.util.List.of("timeoutSeconds"), "additionalProperties", false), input.schema().value());
    }

    @Test
    void rejectsOutOfSchemaScalarTypesBeforeTheMapperCanCoerceThem() {
        ToolInput<SchemaInput> input = ToolInput.of(SchemaInput.class, RecordInputSchemaFactory.standard());
        ToolInput<DurationInput> durationInput = ToolInput.of(DurationInput.class, RecordInputSchemaFactory.standard());

        assertThrows(IllegalArgumentException.class, () -> input.decode(McpJsonDefaults.getMapper(), Map.of("query", "blocks", "threshold", "2.75")));
        assertThrows(IllegalArgumentException.class, () -> input.decode(McpJsonDefaults.getMapper(), Map.of("query", "blocks", "includeDetails", "true")));
        assertThrows(IllegalArgumentException.class, () -> input.decode(McpJsonDefaults.getMapper(), Map.of("query", "blocks", "limit", "9")));
        assertThrows(IllegalArgumentException.class, () -> durationInput.decode(McpJsonDefaults.getMapper(), Map.of("timeoutSeconds", "1.25")));
    }

    @Test
    void generatesAndDecodesJacksonDiscriminatedSealedUnions() {
        ToolInput<UnionInput> input = ToolInput.of(UnionInput.class, RecordInputSchemaFactory.standard());

        UnionInput result = input.decode(McpJsonDefaults.getMapper(), Map.of("interval", Map.of("kind", "numeric", "value", new BigDecimal("2.5"))));
        UnionInput aliasResult = input.decode(McpJsonDefaults.getMapper(), Map.of("interval", Map.of("kind", "number", "value", new BigDecimal("3.5"))));

        assertEquals(new UnionInput(new TestUnion.Numeric(new BigDecimal("2.5"))), result);
        assertEquals(new UnionInput(new TestUnion.Numeric(new BigDecimal("3.5"))), aliasResult);
        assertEquals(Map.of("type", "object", "properties", Map.of("interval", Map.of("oneOf", java.util.List.of(Map.of("type", "object", "properties", Map.of("kind", Map.of("type", "string", "const", "numeric"), "value", Map.of("type", "number")), "required", java.util.List.of("kind", "value"), "additionalProperties", false), Map.of("type", "object", "properties", Map.of("kind", Map.of("type", "string", "const", "number"), "value", Map.of("type", "number")), "required", java.util.List.of("kind", "value"), "additionalProperties", false), Map.of("type", "object", "properties", Map.of("kind", Map.of("type", "string", "const", "text"), "value", Map.of("type", "string")), "required", java.util.List.of("kind", "value"), "additionalProperties", false)))), "required", java.util.List.of("interval"), "additionalProperties", false), input.schema().value());
        assertThrows(IllegalArgumentException.class, () -> input.decode(McpJsonDefaults.getMapper(), Map.of("interval", Map.of("kind", "unknown", "value", "frame"))));
    }

    @Test
    void treatsEnumsWithConstantSpecificBodiesAsEnumsRatherThanSealedUnions() {
        ToolInput<BodyEnumInput> input = ToolInput.of(BodyEnumInput.class, RecordInputSchemaFactory.standard());

        BodyEnumInput result = input.decode(McpJsonDefaults.getMapper(), Map.of("state", "active"));

        assertEquals(new BodyEnumInput(BodyEnum.ACTIVE), result);
        assertTrue(result.state().active());
        assertEquals(Map.of("type", "object", "properties", Map.of("state", Map.of("type", "string", "enum", java.util.List.of("active"))), "required", java.util.List.of("state"), "additionalProperties", false), input.schema().value());
    }

    @Test
    void enforcesNumericBoundsWithExactBigDecimalComparisons() {
        ToolInput<BoundedInput> input = ToolInput.of(BoundedInput.class, RecordInputSchemaFactory.standard());
        BigDecimal signedZero = new BigDecimal("-0.0");
        BigDecimal hugeBoundary = new BigDecimal("1E+1000");

        assertEquals(new BoundedInput(signedZero), input.decode(McpJsonDefaults.getMapper(), Map.of("value", signedZero)));
        assertEquals(new BoundedInput(hugeBoundary), input.decode(McpJsonDefaults.getMapper(), Map.of("value", hugeBoundary)));
        assertThrows(IllegalArgumentException.class, () -> input.decode(McpJsonDefaults.getMapper(), Map.of("value", new BigDecimal("-0.0000000000000000000000000001"))));
        assertThrows(IllegalArgumentException.class, () -> input.decode(McpJsonDefaults.getMapper(), Map.of("value", new BigDecimal("1.0000000000000000000000000001E+1000"))));
    }

    @Test
    void exposesOnlyPrivateConstructors() {
        assertTrue(Arrays.stream(ToolInput.class.getDeclaredConstructors()).allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
    }

    @Test
    void sanitizesUnexpectedMapperFailures() {
        ToolInput<UnexpectedFailureInput> input = ToolInput.of(UnexpectedFailureInput.class, RecordInputSchemaFactory.standard());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> input.decode(McpJsonDefaults.getMapper(), Map.of("value", "secret")));

        assertEquals("Unable to deserialize tool input", exception.getMessage());
        assertFalse(exception.getMessage().contains(UnexpectedFailureInput.class.getName()));
        assertFalse(exception.getMessage().contains("Jackson"));
        assertFalse(exception.getMessage().contains("com.fasterxml"));
    }

    @Test
    void preservesOnlyMarkedSemanticMapperFailures() {
        ToolInput<MarkedFailureInput> input = ToolInput.of(MarkedFailureInput.class, RecordInputSchemaFactory.standard());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> input.decode(McpJsonDefaults.getMapper(), Map.of("value", "bad")));

        assertEquals("The value is not accepted", exception.getMessage());
        assertFalse(exception.getMessage().contains(MarkedFailureInput.class.getName()));
        assertFalse(exception.getMessage().contains("Jackson"));
        assertFalse(exception.getMessage().contains("com.fasterxml"));
    }

    @Test
    void sanitizesNullAndBlankMarkedFailureMessages() {
        ToolInput<InvalidMarkedFailureInput> input = ToolInput.of(InvalidMarkedFailureInput.class, RecordInputSchemaFactory.standard());

        for (String value : java.util.List.of("null", "blank")) {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> input.decode(McpJsonDefaults.getMapper(), Map.of("value", value)));
            assertEquals("Unable to deserialize tool input", exception.getMessage());
            assertFalse(exception.getMessage().contains("InvalidMarkedFailureInput"));
            assertFalse(exception.getMessage().contains("Jackson"));
            assertFalse(exception.getMessage().contains("com.fasterxml"));
            assertFalse(exception.getMessage().contains("java.lang"));
        }

    }

    private record UnexpectedFailureInput(@InputProperty(required = true) UnexpectedFailure value) {
    }

    private record NestedInput(@InputProperty(required = true) NestedValue payload) {
    }

    private record NestedValue(@InputProperty(required = true) String value) {
    }

    private record DefaultMetadataInput(@InputProperty(defaultValue = "42") Integer value) {
    }

    private record UnexpectedFailure(String value) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        private static UnexpectedFailure fromJson(String value) {
            throw new IllegalStateException("secret implementation detail: " + value);
        }

        @JsonValue
        String wireValue() {
            return value;
        }
    }

    private record MarkedFailureInput(@InputProperty(required = true) MarkedFailure value) {
    }

    private record MarkedFailure(String value) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        private static MarkedFailure fromJson(String value) {
            if (value.isEmpty()) {
                throw new ToolInputValidationException("The value is not accepted");
            }
            throw new ToolInputValidationException("The value is not accepted");
        }

        @JsonValue
        String wireValue() {
            return value;
        }
    }

    private record InvalidMarkedFailureInput(@InputProperty(required = true) InvalidMarkedFailure value) {
    }

    private record InvalidMarkedFailure(String value) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        private static InvalidMarkedFailure fromJson(String value) {
            if ("null".equals(value)) {
                throw new ToolInputValidationException(null);
            }
            throw new ToolInputValidationException("   ");
        }

        @JsonValue
        String wireValue() {
            return value;
        }
    }

    private static final class CountingMapper implements McpJsonMapper {
        private final McpJsonMapper delegate = McpJsonDefaults.getMapper();
        private int convertValueCalls;

        @Override
        public <T> T readValue(String content, Class<T> type) throws IOException {
            return delegate.readValue(content, type);
        }

        @Override
        public <T> T readValue(byte[] content, Class<T> type) throws IOException {
            return delegate.readValue(content, type);
        }

        @Override
        public <T> T readValue(String content, TypeRef<T> type) throws IOException {
            return delegate.readValue(content, type);
        }

        @Override
        public <T> T readValue(byte[] content, TypeRef<T> type) throws IOException {
            return delegate.readValue(content, type);
        }

        @Override
        public <T> T convertValue(Object value, Class<T> type) {
            convertValueCalls++;
            return delegate.convertValue(value, type);
        }

        @Override
        public <T> T convertValue(Object value, TypeRef<T> type) {
            convertValueCalls++;
            return delegate.convertValue(value, type);
        }

        @Override
        public String writeValueAsString(Object value) throws IOException {
            return delegate.writeValueAsString(value);
        }

        @Override
        public byte[] writeValueAsBytes(Object value) throws IOException {
            return delegate.writeValueAsBytes(value);
        }
    }
}
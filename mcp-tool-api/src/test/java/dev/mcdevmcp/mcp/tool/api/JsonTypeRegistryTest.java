package dev.mcdevmcp.mcp.tool.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class JsonTypeRegistryTest {
    private static final JsonValueSchema STRING_SCHEMA = JsonValueSchema.of(Map.of("type", "string"));

    @Test
    void standardContainsOnlyMapperProvenDirectionalTypes() {
        JsonTypeRegistry registry = JsonTypeRegistry.standard();

        assertEquals("jdk.duration-seconds.v1", registry.find(Duration.class).orElseThrow().id());
        assertEquals("jdk.uuid.v1", registry.find(UUID.class).orElseThrow().id());
        assertEquals("jdk.instant.v1", registry.find(Instant.class).orElseThrow().id());
        assertEquals("jdk.local-date.v1", registry.find(LocalDate.class).orElseThrow().id());
        assertFalse(registry.find(java.time.OffsetDateTime.class).isPresent());
        assertFalse(registry.find(java.time.LocalTime.class).isPresent());
        assertFalse(registry.find(java.net.URI.class).isPresent());
        assertFalse(registry.find(java.nio.file.Path.class).isPresent());

        JsonLogicalType<?> duration = registry.find("jdk.duration-seconds.v1").orElseThrow();
        assertEquals(Map.of("type", "number"), duration.inputSchema().orElseThrow().value());
        assertEquals(Map.of("type", "string", "format", "duration"), duration.outputSchema().orElseThrow().value());
        assertEquals(Map.of("type", "string", "format", "uuid"), registry.find(UUID.class).orElseThrow().inputSchema().orElseThrow().value());
        assertEquals(Map.of("type", "string", "format", "uuid"), registry.find(UUID.class).orElseThrow().outputSchema().orElseThrow().value());
        assertEquals(Map.of("type", "string", "format", "date-time"), registry.find(Instant.class).orElseThrow().inputSchema().orElseThrow().value());
        assertEquals(Map.of("type", "string", "format", "date-time"), registry.find(Instant.class).orElseThrow().outputSchema().orElseThrow().value());
        assertEquals(Map.of("type", "string", "format", "date"), registry.find(LocalDate.class).orElseThrow().inputSchema().orElseThrow().value());
        assertEquals(Map.of("type", "string", "format", "date"), registry.find(LocalDate.class).orElseThrow().outputSchema().orElseThrow().value());
    }

    @Test
    void acceptsIndependentEqualTypeRefsButKeepsParameterizationsDistinct() {
        TypeRef<List<String>> first = new TypeRef<>() {
        };
        TypeRef<List<String>> second = new TypeRef<>() {
        };
        TypeRef<List<Integer>> integerList = new TypeRef<>() {
        };
        JsonLogicalType<List<String>> entry = JsonLogicalType.of("list-string", first, STRING_SCHEMA, STRING_SCHEMA);
        JsonLogicalType<List<Integer>> integerEntry = JsonLogicalType.of("list-integer", integerList, JsonValueSchema.of(Map.of("type", "array", "items", Map.of("type", "integer"))), STRING_SCHEMA);
        JsonTypeRegistry registry = JsonTypeRegistry.of(List.of(entry, integerEntry));

        assertEquals(entry, registry.find(second).orElseThrow());
        assertEquals(integerEntry, registry.find(integerList).orElseThrow());
        assertFalse(registry.find(new TypeRef<List<Long>>() {
        }).isPresent());
    }

    @Test
    void acceptsStaticMemberTypesAndValidatesNonStaticOwners() {
        JsonValueSchema objectSchema = JsonValueSchema.of(Map.of("type", "object"));
        TypeRef<Map.Entry<String, String>> first = new TypeRef<>() {
        };
        TypeRef<Map.Entry<String, String>> second = new TypeRef<>() {
        };
        JsonLogicalType<Map.Entry<String, String>> staticEntry = JsonLogicalType.of("map-entry", first, objectSchema, objectSchema);
        JsonTypeRegistry registry = assertDoesNotThrow(() -> JsonTypeRegistry.of(List.of(staticEntry)));
        assertEquals(staticEntry, registry.find(second).orElseThrow());

        TypeRef<GenericOuter<String>.Inner<Integer>> nonStatic = new TypeRef<>() {
        };
        assertDoesNotThrow(() -> JsonTypeRegistry.of(List.of(JsonLogicalType.of("non-static", nonStatic, objectSchema, objectSchema))));
        TypeRef<GenericOuter<String>.InnerNonGeneric> closedOwnerForNonGenericMember = new TypeRef<>() {
        };
        assertDoesNotThrow(() -> JsonTypeRegistry.of(List.of(JsonLogicalType.of("non-static-closed-owner", closedOwnerForNonGenericMember, objectSchema, objectSchema))));
        assertThrows(IllegalArgumentException.class, () -> JsonTypeRegistry.of(List.of(JsonLogicalType.of("non-static-raw-class", GenericOuter.InnerNonGeneric.class, objectSchema, objectSchema))));
        assertDoesNotThrow(() -> JsonTypeRegistry.of(List.of(JsonLogicalType.of("non-generic-inner-class", PlainOuter.PlainInner.class, objectSchema, objectSchema))));

        TypeRef<List<?>> wildcard = new TypeRef<>() {
        };
        TypeRef<Map<String, String>> parameterizedMap = new TypeRef<>() {
        };
        assertRejected("static-missing-owner", malformedTypeRef(Map.Entry.class, null, String.class, String.class), objectSchema);
        assertRejected("static-wrong-owner", malformedTypeRef(Map.Entry.class, String.class, String.class, String.class), objectSchema);
        assertRejected("static-wildcard-owner", malformedTypeRef(Map.Entry.class, wildcard.getType(), String.class, String.class), objectSchema);
        assertRejected("static-parameterized-owner", malformedTypeRef(Map.Entry.class, parameterizedMap.getType(), String.class, String.class), objectSchema);
        assertRejected("non-static-missing-owner", malformedTypeRef(GenericOuter.Inner.class, null, String.class), objectSchema);
        assertRejected("non-static-wrong-owner", malformedTypeRef(GenericOuter.Inner.class, String.class, String.class), objectSchema);
        assertRejected("non-static-raw-owner", malformedTypeRef(GenericOuter.Inner.class, GenericOuter.class, String.class), objectSchema);
        TypeRef<GenericOuter<?>> wildcardOuter = new TypeRef<>() {
        };
        assertRejected("non-static-wildcard-owner", malformedTypeRef(GenericOuter.Inner.class, wildcardOuter.getType(), String.class), objectSchema);
        assertRejected("non-static-noop", malformedTypeRef(PlainOuter.PlainInner.class, PlainOuter.class), objectSchema);
        assertRejected("top-level-wrong-owner", malformedTypeRef(List.class, String.class, String.class), objectSchema);
        assertRejected("top-level-non-generic", malformedTypeRef(String.class, null), objectSchema);
        assertRejected("static-non-generic", malformedTypeRef(GenericOuter.StaticNonGeneric.class, GenericOuter.class), objectSchema);
        assertRejected("null-arguments", malformedTypeRef(List.class, null, (Type[]) null), objectSchema);
        assertRejected("primitive-argument", malformedTypeRef(List.class, null, int.class), objectSchema);
        assertRejected("void-argument", malformedTypeRef(List.class, null, void.class), objectSchema);
        assertRejected("structural-cycle", cyclicTypeRef(), objectSchema);
        assertRejected("adversarial-equals-cycle", adversarialCycleTypeRef(), objectSchema);
    }

    @Test
    void rejectsDuplicateIdsTypesAndUnsupportedTypeShapes() {
        JsonLogicalType<String> first = JsonLogicalType.of("same", String.class, STRING_SCHEMA, STRING_SCHEMA);
        JsonLogicalType<Integer> duplicateId = JsonLogicalType.of("same", Integer.class, JsonValueSchema.of(Map.of("type", "integer")), STRING_SCHEMA);
        JsonLogicalType<String> duplicateType = JsonLogicalType.of("other", String.class, STRING_SCHEMA, STRING_SCHEMA);
        assertThrows(IllegalArgumentException.class, () -> JsonTypeRegistry.of(List.of(first, duplicateId)));
        assertThrows(IllegalArgumentException.class, () -> JsonTypeRegistry.of(List.of(first, duplicateType)));

        @SuppressWarnings({"rawtypes", "unchecked"}) JsonLogicalType<?> raw = JsonLogicalType.of("raw", (Class) List.class, JsonValueSchema.of(Map.of("type", "array", "items", Map.of("type", "string"))), STRING_SCHEMA);
        assertThrows(IllegalArgumentException.class, () -> JsonTypeRegistry.of(List.of(raw)));
        assertThrows(IllegalArgumentException.class, () -> JsonTypeRegistry.of(List.of(JsonLogicalType.of("wildcard", new TypeRef<List<?>>() {
        }, STRING_SCHEMA, STRING_SCHEMA))));
        assertThrows(IllegalArgumentException.class, () -> JsonTypeRegistry.of(List.of(genericArrayEntry())));
        assertThrows(IllegalArgumentException.class, () -> JsonTypeRegistry.of(List.of(typeVariableEntry())));
    }

    @Test
    void preservesDirectionalityAndRejectsInvalidFormats() {
        TypeRef<String> stringType = new TypeRef<>() {
        };
        JsonLogicalType<String> inputOnly = JsonLogicalType.inputOnly("input", String.class, STRING_SCHEMA);
        JsonLogicalType<String> outputOnly = JsonLogicalType.outputOnly("output", String.class, STRING_SCHEMA);
        JsonLogicalType<String> referencedInputOnly = JsonLogicalType.inputOnly("referenced-input", stringType, STRING_SCHEMA);
        JsonLogicalType<String> referencedOutputOnly = JsonLogicalType.outputOnly("referenced-output", stringType, STRING_SCHEMA);
        JsonLogicalType<String> referencedBidirectional = JsonLogicalType.bidirectional("referenced-bidirectional", stringType, STRING_SCHEMA);
        assertTrue(inputOnly.inputSchema().isPresent());
        assertTrue(inputOnly.outputSchema().isEmpty());
        assertTrue(outputOnly.inputSchema().isEmpty());
        assertTrue(outputOnly.outputSchema().isPresent());
        assertTrue(referencedInputOnly.outputSchema().isEmpty());
        assertTrue(referencedOutputOnly.inputSchema().isEmpty());
        assertEquals(referencedBidirectional.inputSchema(), referencedBidirectional.outputSchema());

        assertThrows(IllegalArgumentException.class, () -> JsonValueSchema.of(Map.of("type", "number", "format", "date-time")));
        assertThrows(IllegalArgumentException.class, () -> JsonValueSchema.of(Map.of("type", "string", "format", " ")));
        JsonValueSchema unknown = JsonValueSchema.of(Map.of("type", "number", "format", "seconds-since-start"));
        assertEquals("seconds-since-start", unknown.value().get("format"));
    }

    @Test
    void validatesKnownFormatsAcrossOneOfBranches() {
        assertThrows(IllegalArgumentException.class, () -> JsonValueSchema.of(Map.of("oneOf", List.of(Map.of("type", "number")), "format", "uuid")));
        assertThrows(IllegalArgumentException.class, () -> JsonValueSchema.of(Map.of("oneOf", List.of(Map.of("oneOf", List.of(Map.of("type", "number")))), "format", "uuid")));
        assertThrows(IllegalArgumentException.class, () -> JsonValueSchema.of(Map.of("oneOf", List.of(Map.of("oneOf", List.of(Map.of("type", "number")), "format", "uuid")))));

        assertDoesNotThrow(() -> JsonValueSchema.of(Map.of("oneOf", List.of(Map.of("type", "string")), "format", "uuid")));
        assertDoesNotThrow(() -> JsonValueSchema.of(Map.of("oneOf", List.of(Map.of("oneOf", List.of(Map.of("type", "string")))), "format", "uuid")));
        assertDoesNotThrow(() -> JsonValueSchema.of(Map.of("oneOf", List.of(Map.of("type", "number")), "format", "seconds-since-start")));
    }

    @Test
    void freezesSchemasBeforeCallersCanMutateThem() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("type", "string");
        List<Object> alternatives = new ArrayList<>();
        alternatives.add(nested);
        Map<String, Object> source = new HashMap<>();
        source.put("oneOf", alternatives);
        JsonValueSchema schema = JsonValueSchema.of(source);
        nested.put("type", "number");
        alternatives.clear();
        source.clear();

        assertEquals(Map.of("oneOf", List.of(Map.of("type", "string"))), schema.value());
        assertTrue(schema.semanticallyEquals(Map.of("oneOf", List.of(Map.of("type", "string")))));
        assertThrows(UnsupportedOperationException.class, () -> schema.value().put("format", "custom"));
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) schema.value().get("oneOf")).clear());
    }

    @Test
    void resolvesRegisteredScalarsInsideExactCollections() {
        JsonValueSchema tokenSchema = JsonValueSchema.of(Map.of("type", "string", "format", "token"));
        JsonTypeRegistry registry = JsonTypeRegistry.of(List.of(JsonLogicalType.of("test.token.v1", Token.class, tokenSchema, tokenSchema)));
        JsonObjectSchema schema = RecordInputSchemaFactory.of(registry).generate(JsonType.of(TokenListInput.class));

        assertEquals(Map.of("type", "object", "properties", Map.of("tokens", Map.of("type", "array", "items", Map.of("type", "string", "format", "token"))), "additionalProperties", false), schema.value());
        TokenListInput decoded = ToolInput.of(TokenListInput.class, RecordInputSchemaFactory.of(registry)).decode(McpJsonDefaults.getMapper(), Map.of("tokens", List.of("one", "two")));
        assertEquals(new TokenListInput(List.of(new Token("one"), new Token("two"))), decoded);
    }

    @Test
    void decodesAllStandardInputTypesThroughOneCompleteMapperConversion() {
        ToolInput<StandardInput> input = ToolInput.of(StandardInput.class, RecordInputSchemaFactory.standard());
        StandardInput decoded = input.decode(McpJsonDefaults.getMapper(), Map.of("duration", new BigDecimal("1.25"), "uuid", "123e4567-e89b-12d3-a456-426614174000", "instant", "2026-09-03T05:02:03.456Z", "date", "2026-09-03"));

        assertEquals(Duration.ofMillis(1250), decoded.duration());
        assertEquals(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), decoded.uuid());
        assertEquals(Instant.parse("2026-09-03T05:02:03.456Z"), decoded.instant());
        assertEquals(LocalDate.of(2026, 9, 3), decoded.date());
        assertEquals(Map.of("type", "object", "properties", Map.of("duration", Map.of("type", "number"), "uuid", Map.of("type", "string", "format", "uuid"), "instant", Map.of("type", "string", "format", "date-time"), "date", Map.of("type", "string", "format", "date")), "additionalProperties", false), input.schema().value());
    }

    @Test
    void freezesMapperNativeOutputSpellingsAndEqualityForEveryStandardEntry() throws IOException {
        var mapper = McpJsonDefaults.getMapper();
        Duration duration = Duration.ofMillis(1250);
        UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        Instant instant = Instant.parse("2026-09-03T05:02:03.456Z");
        LocalDate date = LocalDate.of(2026, 9, 3);

        assertEquals("\"PT1.25S\"", mapper.writeValueAsString(duration));
        assertEquals("\"123e4567-e89b-12d3-a456-426614174000\"", mapper.writeValueAsString(uuid));
        assertEquals("\"2026-09-03T05:02:03.456Z\"", mapper.writeValueAsString(instant));
        assertEquals("\"2026-09-03\"", mapper.writeValueAsString(date));
        assertEquals(duration, mapper.convertValue("PT1.25S", Duration.class));
        assertEquals(uuid, mapper.convertValue(uuid.toString(), UUID.class));
        assertEquals(instant, mapper.convertValue(instant.toString(), Instant.class));
        assertEquals(date, mapper.convertValue(date.toString(), LocalDate.class));
    }

    private static JsonLogicalType<?> genericArrayEntry() {
        return JsonLogicalType.of("generic-array", new TypeRef<List<String>[]>() {
        }, STRING_SCHEMA, STRING_SCHEMA);
    }

    private static TypeRef<Object> malformedTypeRef(Class<?> rawType, Type ownerType, Type... arguments) {
        ParameterizedType parameterizedType = new TestParameterizedType(rawType, ownerType, arguments);
        return typeRef(parameterizedType);
    }

    private static TypeRef<Object> cyclicTypeRef() {
        return typeRef(new CyclicParameterizedType());
    }

    private static TypeRef<Object> adversarialCycleTypeRef() {
        EqualParameterizedType first = new EqualParameterizedType();
        EqualParameterizedType second = new EqualParameterizedType();
        first.setArgument(second);
        second.setArgument(first);
        return typeRef(first);
    }

    private static TypeRef<Object> typeRef(Type type) {
        return new TypeRef<>() {
            @Override
            public Type getType() {
                return type;
            }
        };
    }

    private static <T> void assertRejected(String id, TypeRef<T> type, JsonValueSchema schema) {
        assertThrows(IllegalArgumentException.class, () -> JsonTypeRegistry.of(List.of(JsonLogicalType.of(id, type, schema, schema))));
    }

    private static <T> JsonLogicalType<T> typeVariableEntry() {
        return JsonLogicalType.of("type-variable", new TypeRef<>() {
        }, STRING_SCHEMA, STRING_SCHEMA);
    }

    private record Token(String value) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        private static Token fromJson(String value) {
            return new Token(value);
        }

        @JsonValue
        private String toJson() {
            return value;
        }
    }

    private record TokenListInput(List<Token> tokens) {
    }

    private record StandardInput(Duration duration, UUID uuid, Instant instant, LocalDate date) {
    }

    @SuppressWarnings("NullableProblems")
    private record TestParameterizedType(Class<?> rawType, Type ownerType, Type[] arguments) implements ParameterizedType {
        private TestParameterizedType {
            arguments = arguments == null ? null : arguments.clone();
        }

        @Override
        public Type[] getActualTypeArguments() {
            return arguments == null ? null : arguments.clone();
        }

        @Override
        public Type getRawType() {
            return rawType;
        }

        @Override
        public Type getOwnerType() {
            return ownerType;
        }
    }

    @SuppressWarnings("NullableProblems")
    private static final class CyclicParameterizedType implements ParameterizedType {
        @Override
        public Type[] getActualTypeArguments() {
            return new Type[]{this};
        }

        @Override
        public Type getRawType() {
            return List.class;
        }

        @Override
        public Type getOwnerType() {
            return null;
        }
    }

    @SuppressWarnings("NullableProblems")
    private static final class EqualParameterizedType implements ParameterizedType {
        private Type argument;

        private void setArgument(Type argument) {
            this.argument = argument;
        }

        @Override
        public Type[] getActualTypeArguments() {
            return new Type[]{argument};
        }

        @Override
        public Type getRawType() {
            return List.class;
        }

        @Override
        public Type getOwnerType() {
            return null;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Type;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }

    @SuppressWarnings({"InnerClassMayBeStatic", "unused"})
    private static class GenericOuter<T> {
        private static class StaticNonGeneric {
        }

        @SuppressWarnings({"InnerClassMayBeStatic", "unused"})
        private class InnerNonGeneric {
        }

        private class Inner<U> {
        }
    }

    @SuppressWarnings({"InnerClassMayBeStatic", "unused"})
    private static class PlainOuter {
        private class PlainInner {
        }
    }
}
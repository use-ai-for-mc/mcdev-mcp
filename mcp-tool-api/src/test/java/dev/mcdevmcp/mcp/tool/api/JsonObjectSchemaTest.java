package dev.mcdevmcp.mcp.tool.api;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JsonObjectSchemaTest {
    @Test
    void rejectsMutableValuesThatAreNotJsonTreeNodes() {
        Map<String, Object> setSchema = new LinkedHashMap<>();
        setSchema.put("type", "object");
        setSchema.put("enum", Set.of("unexpected"));

        Map<String, Object> arraySchema = new LinkedHashMap<>();
        arraySchema.put("type", "object");
        arraySchema.put("examples", new String[]{"unexpected"});

        assertThrows(IllegalArgumentException.class, () -> JsonObjectSchema.of(setSchema));
        assertThrows(IllegalArgumentException.class, () -> JsonObjectSchema.of(arraySchema));
    }

    @Test
    void rejectsUnsupportedCompositionFormsBeforeToolDecoding() {
        assertThrows(IllegalArgumentException.class, () -> JsonObjectSchema.of(Map.of("type", "object", "anyOf", List.of(Map.of("type", "object")))));
        assertThrows(IllegalArgumentException.class, () -> JsonObjectSchema.of(Map.of("type", "object", "properties", Map.of("value", Map.of("$ref", "#/$defs/value")))));
        assertThrows(IllegalArgumentException.class, () -> JsonObjectSchema.of(Map.of("type", "object", "properties", Map.of("value", Map.of("type", List.of("string", "number"))))));
        assertThrows(IllegalArgumentException.class, () -> JsonObjectSchema.of(Map.of("type", "object", "properties", Map.of("value", Map.of("oneOf", List.of(Map.of("type", "string")), "required", List.of("ignored"))))));
        assertThrows(IllegalArgumentException.class, () -> JsonObjectSchema.of(Map.of("type", "object", "properties", Map.of("value", Map.of("type", "number", "minimum", "zero")))));
        assertThrows(IllegalArgumentException.class, () -> JsonObjectSchema.of(Map.of("type", "object", "properties", Map.of("value", Map.of("type", "number", "minimum", 2, "maximum", 1)))));
        assertThrows(IllegalArgumentException.class, () -> JsonObjectSchema.of(Map.of("type", "object", "properties", Map.of("value", Map.of("type", "string", "pattern", "ignored")))));
        assertThrows(IllegalArgumentException.class, () -> JsonObjectSchema.of(Map.of("type", "object", "additionalProperties", "false")));
        assertThrows(IllegalArgumentException.class, () -> JsonObjectSchema.of(Map.of("type", "object", "properties", Map.of("value", Map.of("type", "number", "enum", List.of())))));
        assertThrows(IllegalArgumentException.class, () -> JsonObjectSchema.of(Map.of("type", "object", "properties", Map.of("value", Map.of("type", "number", "enum", List.of(1, new java.math.BigDecimal("1.0")))))));
        assertThrows(IllegalArgumentException.class, () -> JsonObjectSchema.of(Map.of("type", "object", "description", 1)));
    }

    @Test
    void rejectsPresentNullKeywordsAndNonStringNestedKeys() {
        for (String keyword : List.of("properties", "required", "enum", "additionalProperties")) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put(keyword, null);
            assertThrows(IllegalArgumentException.class, () -> JsonObjectSchema.of(schema), keyword);
        }

        Map<Object, Object> nested = new LinkedHashMap<>();
        nested.put(1, Map.of("type", "string"));
        assertThrows(IllegalArgumentException.class, () -> JsonObjectSchema.of(Map.of("type", "object", "properties", nested)));
    }

    @Test
    void supportsJsonNullAsAnExplicitOneOfBranch() {
        JsonObjectSchema schema = JsonObjectSchema.of(Map.of("type", "object", "properties", Map.of("entityId", Map.of("oneOf", List.of(Map.of("type", "integer"), Map.of("type", "null")))), "required", List.of("entityId"), "additionalProperties", true));

        Map<String, Object> noTarget = new LinkedHashMap<>();
        noTarget.put("entityId", null);
        assertDoesNotThrow(() -> schema.validateInputTypes(noTarget));
        assertDoesNotThrow(() -> schema.validateInputTypes(Map.of("entityId", 12)));
        assertThrows(IllegalArgumentException.class, () -> schema.validateInputTypes(Map.of()));
    }

    @Test
    void honorsAdditionalPropertiesForRootAndNestedObjects() {
        JsonObjectSchema permissive = JsonObjectSchema.of(Map.of("type", "object", "properties", Map.of("known", Map.of("type", "string"))));
        assertDoesNotThrow(() -> permissive.validateInputTypes(Map.of("known", "value", "unknown", true)));

        JsonObjectSchema explicitlyPermissive = JsonObjectSchema.of(Map.of("type", "object", "additionalProperties", true, "properties", Map.of("known", Map.of("type", "string"))));
        assertDoesNotThrow(() -> explicitlyPermissive.validateInputTypes(Map.of("known", "value", "unknown", true)));

        JsonObjectSchema closed = JsonObjectSchema.of(Map.of("type", "object", "additionalProperties", false, "properties", Map.of("known", Map.of("type", "string"))));
        IllegalArgumentException rootException = assertThrows(IllegalArgumentException.class, () -> closed.validateInputTypes(Map.of("known", "value", "unknown", true)));
        org.junit.jupiter.api.Assertions.assertEquals("'unknown' is not a permitted property", rootException.getMessage());

        JsonObjectSchema nested = JsonObjectSchema.of(Map.of("type", "object", "additionalProperties", false, "properties", Map.of("payload", Map.of("type", "object", "additionalProperties", false, "properties", Map.of("known", Map.of("type", "string"))))));
        IllegalArgumentException nestedException = assertThrows(IllegalArgumentException.class, () -> nested.validateInputTypes(Map.of("payload", Map.of("known", "value", "unknown", true))));
        org.junit.jupiter.api.Assertions.assertEquals("'payload.unknown' is not a permitted property", nestedException.getMessage());
    }

    @Test
    void rejectsExplicitNullIntegerCandidatesWithTheSameCleanTypeErrorAsWrongTypes() {
        JsonObjectSchema schema = JsonObjectSchema.of(Map.of("type", "object", "additionalProperties", false, "properties", Map.of("value", Map.of("type", "integer"))));

        Map<String, Object> nullCandidate = new LinkedHashMap<>();
        nullCandidate.put("value", null);
        IllegalArgumentException nullException = assertThrows(IllegalArgumentException.class, () -> schema.validateInputTypes(nullCandidate));
        assertEquals("'value' must be an integer", nullException.getMessage());

        IllegalArgumentException wrongTypeException = assertThrows(IllegalArgumentException.class, () -> schema.validateInputTypes(Map.of("value", "1")));
        assertEquals("'value' must be an integer", wrongTypeException.getMessage());

        IllegalArgumentException unknownPropertyException = assertThrows(IllegalArgumentException.class, () -> schema.validateInputTypes(Map.of("value", 1, "unknown", true)));
        assertEquals("'unknown' is not a permitted property", unknownPropertyException.getMessage());
    }

    @Test
    void reportsStructuredNestedArrayLocationsWithoutFilesystemPathSemantics() {
        JsonObjectSchema schema = JsonObjectSchema.of(Map.of("type", "object", "properties", Map.of("values", Map.of("type", "array", "items", Map.of("type", "integer")))));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> schema.validateInputTypes(Map.of("values", List.of(1, "two"))));

        assertEquals("'values[1]' must be an integer", exception.getMessage());
    }

    @Test
    void rejectsNonBooleanAdditionalPropertiesMetadata() {
        Map<String, Object> nullSchema = new LinkedHashMap<>();
        nullSchema.put("type", "object");
        nullSchema.put("additionalProperties", null);

        assertThrows(IllegalArgumentException.class, () -> JsonObjectSchema.of(nullSchema));
        assertThrows(IllegalArgumentException.class, () -> JsonObjectSchema.of(Map.of("type", "object", "additionalProperties", 1)));
    }

    @Test
    void validatesDeclaredPropertiesBeforeUnknownPropertiesRegardlessOfInputOrder() {
        Map<String, Object> frameBranch = Map.of("type", "object", "properties", Map.of("kind", Map.of("type", "string", "const", "frame")), "required", List.of("kind"), "additionalProperties", false);
        Map<String, Object> millisecondsBranch = Map.of("type", "object", "properties", Map.of("kind", Map.of("type", "string", "const", "milliseconds"), "value", Map.of("type", "number", "minimum", 1)), "required", List.of("kind", "value"), "additionalProperties", false);
        JsonObjectSchema schema = JsonObjectSchema.of(Map.of("type", "object", "properties", Map.of("interval", Map.of("oneOf", List.of(frameBranch, millisecondsBranch))), "additionalProperties", false));

        Map<String, Object> invalidBound = new LinkedHashMap<>();
        invalidBound.put("value", 0);
        invalidBound.put("kind", "milliseconds");
        IllegalArgumentException boundException = assertThrows(IllegalArgumentException.class, () -> schema.validateInputTypes(Map.of("interval", invalidBound)));
        assertEquals("'interval' must match exactly one permitted input shape", boundException.getMessage());
        assertFalse(boundException.getMessage().contains("interval.value' is not a permitted property"));

        Map<String, Object> unexpectedValue = new LinkedHashMap<>();
        unexpectedValue.put("kind", "frame");
        unexpectedValue.put("value", 50);
        IllegalArgumentException unknownException = assertThrows(IllegalArgumentException.class, () -> schema.validateInputTypes(Map.of("interval", unexpectedValue)));
        assertEquals("'interval.value' is not a permitted property", unknownException.getMessage());
    }

    @Test
    void comparesNestedConstNumbersByJsonNumericValue() {
        JsonObjectSchema schema = JsonObjectSchema.of(Map.of("type", "object", "properties", Map.of("payload", Map.of("type", "object", "const", Map.of("count", 1, "items", List.of(2, 3))))));

        assertDoesNotThrow(() -> schema.validateInputTypes(Map.of("payload", Map.of("count", new java.math.BigDecimal("1.0"), "items", List.of(2.0, new java.math.BigDecimal("3.00"))))));
        assertThrows(IllegalArgumentException.class, () -> schema.validateInputTypes(Map.of("payload", Map.of("count", 1, "items", List.of(2, 4)))));
    }
}
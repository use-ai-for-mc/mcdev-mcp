package dev.mcdevmcp.mcp.tool.api;

import java.io.Serial;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record JsonObjectSchema(Map<String, Object> value) {
    public JsonObjectSchema {
        value = JsonSchemaSupport.immutableObject(value);
        if (!"object".equals(value.get("type"))) {
            throw new IllegalArgumentException("JSON Schema root type must be object");
        }
        JsonSchemaSupport.validateSupportedSchema(value, "input");
    }

    public static JsonObjectSchema of(Map<String, Object> value) {
        return new JsonObjectSchema(value);
    }

    @SuppressWarnings("unused")
    public boolean semanticallyEquals(Map<String, Object> other) {
        return JsonSchemaSupport.jsonEquals(value, Objects.requireNonNull(other, "other"));
    }

    void validateInputTypes(Map<String, Object> input) {
        validateType(value, Objects.requireNonNull(input, "input"), JsonInputLocation.root());
    }

    private static void validateType(Map<String, Object> schema, Object candidate, JsonInputLocation inputLocation) {
        if (schema.containsKey("oneOf")) {
            validateOneOf(schema, candidate, inputLocation);
            return;
        }
        String type = (String) schema.get("type");
        switch (type) {
            case "object" -> validateObject(schema, candidate, inputLocation);
            case "array" -> validateArray(schema, candidate, inputLocation);
            case "string" -> {
                if (!(candidate instanceof String)) throw typeMismatch(inputLocation, "a string");
            }
            case "boolean" -> {
                if (!(candidate instanceof Boolean)) throw typeMismatch(inputLocation, "a boolean");
            }
            case "integer" -> {
                if (!JsonSchemaSupport.isJsonInteger(candidate)) throw typeMismatch(inputLocation, "an integer");
            }
            case "number" -> {
                if (!JsonSchemaSupport.isJsonNumber(candidate)) throw typeMismatch(inputLocation, "a finite number");
            }
            case "null" -> {
                if (candidate != null) throw typeMismatch(inputLocation, "null");
            }
            default -> throw new IllegalStateException("Unsupported generated JSON Schema type: " + type);
        }
        validateLiteralConstraints(schema, candidate, inputLocation);
    }

    private static void validateObject(Map<String, Object> schema, Object candidate, JsonInputLocation inputLocation) {
        if (!(candidate instanceof Map<?, ?> object)) {
            throw typeMismatch(inputLocation, "an object");
        }
        Object requiredProperties = schema.get("required");
        if (requiredProperties instanceof List<?> required) {
            for (Object entry : required) {
                String name = (String) entry;
                if (!object.containsKey(name)) {
                    throw new IllegalArgumentException("'" + inputLocation.property(name) + "' is required");
                }
            }
        }
        Object declaredProperties = schema.get("properties");
        Map<?, ?> properties = declaredProperties instanceof Map<?, ?> declared ? declared : Map.of();
        boolean allowAdditionalProperties = !Boolean.FALSE.equals(schema.get("additionalProperties"));
        for (Map.Entry<?, ?> entry : object.entrySet()) {
            if (entry.getKey() instanceof String name && properties.get(name) instanceof Map<?, ?> property) {
                validateType(stringObjectMap(property), entry.getValue(), propertyLocation(inputLocation, name));
            }
        }
        for (Map.Entry<?, ?> entry : object.entrySet()) {
            if (!(entry.getKey() instanceof String name)) {
                throw new IllegalArgumentException(inputLocation + " contains a non-string property name");
            }
            if (!allowAdditionalProperties && !(properties.get(name) instanceof Map<?, ?>)) {
                throw new UnknownPropertyException(propertyLocation(inputLocation, name));
            }
        }
    }

    private static void validateOneOf(Map<String, Object> schema, Object candidate, JsonInputLocation inputLocation) {
        Object oneOf = schema.get("oneOf");
        if (!(oneOf instanceof List<?> alternatives)) {
            throw new IllegalStateException("Generated JSON Schema oneOf must be an array");
        }
        int matches = 0;
        UnknownPropertyException unknownProperty = null;
        for (Object alternativeValue : alternatives) {
            if (!(alternativeValue instanceof Map<?, ?> alternative)) {
                throw new IllegalStateException("Generated JSON Schema oneOf alternatives must be objects");
            }
            try {
                validateType(stringObjectMap(alternative), candidate, inputLocation);
                matches++;
            } catch (UnknownPropertyException exception) {
                if (unknownProperty == null) {
                    unknownProperty = exception;
                }
            } catch (IllegalArgumentException ignored) {
                // A oneOf value is valid only when exactly one complete alternative accepts it.
            }
        }
        if (matches != 1) {
            if (unknownProperty != null) {
                throw unknownProperty;
            }
            throw new IllegalArgumentException("'" + inputLocation + "' must match exactly one permitted input shape");
        }
    }

    private static void validateLiteralConstraints(Map<String, Object> schema, Object candidate, JsonInputLocation inputLocation) {
        if (schema.containsKey("const") && !JsonSchemaSupport.jsonEquals(schema.get("const"), candidate)) {
            throw new IllegalArgumentException("'" + inputLocation + "' has an unsupported discriminator value");
        }
        Object enumValues = schema.get("enum");
        if (enumValues instanceof List<?> values && values.stream().noneMatch(value -> JsonSchemaSupport.jsonEquals(value, candidate))) {
            throw new IllegalArgumentException("'" + inputLocation + "' is not one of the permitted values");
        }
        if (candidate instanceof Number number) {
            BigDecimal candidateValue = decimal(number);
            if (schema.get("minimum") instanceof Number minimum && candidateValue.compareTo(decimal(minimum)) < 0) {
                throw new IllegalArgumentException("'" + inputLocation + "' must not be below " + minimum);
            }
            if (schema.get("maximum") instanceof Number maximum && candidateValue.compareTo(decimal(maximum)) > 0) {
                throw new IllegalArgumentException("'" + inputLocation + "' must not exceed " + maximum);
            }
        }
    }

    private static BigDecimal decimal(Number number) {
        return new BigDecimal(number.toString());
    }

    private static void validateArray(Map<String, Object> schema, Object candidate, JsonInputLocation inputLocation) {
        if (!(candidate instanceof List<?> elements)) {
            throw typeMismatch(inputLocation, "an array");
        }
        Object itemSchema = schema.get("items");
        if (!(itemSchema instanceof Map<?, ?> item)) {
            return;
        }
        Map<String, Object> typedItem = stringObjectMap(item);
        for (int index = 0; index < elements.size(); index++) {
            validateType(typedItem, elements.get(index), inputLocation.element(index));
        }
    }

    private static Map<String, Object> stringObjectMap(Map<?, ?> map) {
        @SuppressWarnings("unchecked") Map<String, Object> typed = (Map<String, Object>) map;
        return typed;
    }

    private static IllegalArgumentException typeMismatch(JsonInputLocation inputLocation, String expected) {
        return new IllegalArgumentException("'" + inputLocation + "' must be " + expected);
    }

    private static JsonInputLocation propertyLocation(JsonInputLocation inputLocation, String name) {
        return inputLocation.property(name);
    }

    private static final class UnknownPropertyException extends IllegalArgumentException {
        @Serial
        private static final long serialVersionUID = 1L;

        private UnknownPropertyException(JsonInputLocation inputLocation) {
            super("'" + inputLocation + "' is not a permitted property");
        }
    }

}

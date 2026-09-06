package dev.mcdevmcp.mcp.tool.api;

import java.math.BigDecimal;
import java.util.*;

/**
 * Shared copier and supported-fragment validator for the public schema wrappers.
 */
final class JsonSchemaSupport {
    private static final Set<String> ONE_OF_KEYWORDS = Set.of("oneOf", "title", "description", "default", "format");
    private static final Set<String> OBJECT_KEYWORDS = Set.of("type", "properties", "required", "additionalProperties", "const", "enum", "title", "description", "default", "format");
    private static final Set<String> ARRAY_KEYWORDS = Set.of("type", "items", "const", "enum", "title", "description", "default", "format");
    private static final Set<String> SCALAR_KEYWORDS = Set.of("type", "const", "enum", "title", "description", "default", "format");
    private static final Set<String> NUMERIC_KEYWORDS = Set.of("type", "const", "enum", "minimum", "maximum", "title", "description", "default", "format");
    private static final Set<String> STRING_FORMATS = Set.of("date", "time", "duration", "date-time", "email", "hostname", "ipv4", "ipv6", "uri", "uri-reference", "iri", "iri-reference", "uuid", "regex", "json-pointer", "relative-json-pointer");

    private JsonSchemaSupport() {
    }

    static Map<String, Object> immutableObject(Map<String, Object> value) {
        Objects.requireNonNull(value, "value");
        Map<String, Object> copy = new LinkedHashMap<>();
        value.forEach((key, entryValue) -> copy.put(Objects.requireNonNull(key, "schema key"), immutableValue(entryValue)));
        return Collections.unmodifiableMap(copy);
    }

    static Map<String, Object> mutableObject(Map<?, ?> value) {
        Map<String, Object> copy = new LinkedHashMap<>();
        value.forEach((key, entryValue) -> {
            if (!(key instanceof String text)) {
                throw new IllegalArgumentException("Schema object keys must be strings");
            }
            copy.put(text, mutableValue(entryValue));
        });
        return copy;
    }

    private static Object immutableValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || isJsonNumber(value)) {
            return value;
        }
        if (value instanceof Map<?, ?> object) {
            Map<String, Object> copy = new LinkedHashMap<>();
            object.forEach((key, entryValue) -> {
                if (!(key instanceof String text)) {
                    throw new IllegalArgumentException("Schema object keys must be strings");
                }
                copy.put(text, immutableValue(entryValue));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> array) {
            List<Object> copy = new ArrayList<>(array.size());
            array.forEach(entryValue -> copy.add(immutableValue(entryValue)));
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("Schema values must be JSON tree nodes, got: " + value.getClass().getTypeName());
    }

    private static Object mutableValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || isJsonNumber(value)) {
            return value;
        }
        if (value instanceof Map<?, ?> object) {
            return mutableObject(object);
        }
        if (value instanceof List<?> array) {
            List<Object> copy = new ArrayList<>(array.size());
            array.forEach(entryValue -> copy.add(mutableValue(entryValue)));
            return copy;
        }
        throw new IllegalArgumentException("Schema values must be JSON tree nodes, got: " + value.getClass().getTypeName());
    }

    static void validateSupportedSchema(Map<String, Object> schema, String schemaLocation) {
        for (String annotation : List.of("title", "description")) {
            if (schema.containsKey(annotation) && !(schema.get(annotation) instanceof String)) {
                throw new IllegalArgumentException("Input schema " + annotation + " must be a string at " + schemaLocation);
            }
        }
        if (schema.containsKey("format") && (!(schema.get("format") instanceof String format) || format.isBlank())) {
            throw new IllegalArgumentException("Input schema format must be a nonblank string at " + schemaLocation);
        }
        if (schema.containsKey("oneOf")) {
            rejectUnknownKeywords(schema, ONE_OF_KEYWORDS, schemaLocation);
            Object alternatives = schema.get("oneOf");
            if (!(alternatives instanceof List<?> branches) || branches.isEmpty()) {
                throw new IllegalArgumentException("Input oneOf must contain schema alternatives at " + schemaLocation);
            }
            for (int index = 0; index < branches.size(); index++) {
                Object branch = branches.get(index);
                if (!(branch instanceof Map<?, ?> branchSchema)) {
                    throw new IllegalArgumentException("Input oneOf alternative must be an object at " + schemaLocation + '[' + index + ']');
                }
                validateSupportedSchema(stringObjectMap(branchSchema), schemaLocation + '[' + index + ']');
            }
            validateKnownStringFormat(schema, schemaLocation);
            return;
        }

        Object rawType = schema.get("type");
        if (!(rawType instanceof String type) || !Set.of("object", "array", "string", "boolean", "integer", "number", "null").contains(type)) {
            throw new IllegalArgumentException("Unsupported input schema type at " + schemaLocation + ": " + rawType);
        }
        validateKnownStringFormat(schema, schemaLocation);
        Set<String> allowedKeywords = switch (type) {
            case "object" -> OBJECT_KEYWORDS;
            case "array" -> ARRAY_KEYWORDS;
            case "integer", "number" -> NUMERIC_KEYWORDS;
            default -> SCALAR_KEYWORDS;
        };
        rejectUnknownKeywords(schema, allowedKeywords, schemaLocation);
        if (type.equals("object")) {
            Object additionalProperties = schema.get("additionalProperties");
            if (schema.containsKey("additionalProperties") && !(additionalProperties instanceof Boolean)) {
                throw new IllegalArgumentException("Input schema additionalProperties must be a boolean at " + schemaLocation);
            }
            Object rawProperties = schema.get("properties");
            if (schema.containsKey("properties") && !(rawProperties instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Input schema properties must be an object at " + schemaLocation);
            }
            if (rawProperties instanceof Map<?, ?> properties) {
                for (Map.Entry<?, ?> entry : properties.entrySet()) {
                    if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof Map<?, ?> propertySchema)) {
                        throw new IllegalArgumentException("Input schema property must be a named schema at " + schemaLocation);
                    }
                    validateSupportedSchema(stringObjectMap(propertySchema), schemaLocation + '.' + name);
                }
            }
            Object required = schema.get("required");
            if (schema.containsKey("required") && (!(required instanceof List<?> names) || names.stream().anyMatch(name -> !(name instanceof String)))) {
                throw new IllegalArgumentException("Input schema required must be a string array at " + schemaLocation);
            }
            if (required instanceof List<?> names) {
                Set<Object> uniqueNames = new HashSet<>(names);
                if (uniqueNames.size() != names.size()) {
                    throw new IllegalArgumentException("Input schema required contains duplicates at " + schemaLocation);
                }
                if (rawProperties instanceof Map<?, ?> properties && names.stream().anyMatch(name -> !properties.containsKey(name))) {
                    throw new IllegalArgumentException("Input schema required names an undeclared property at " + schemaLocation);
                }
                if (!(rawProperties instanceof Map<?, ?>) && !names.isEmpty()) {
                    throw new IllegalArgumentException("Input schema required needs declared properties at " + schemaLocation);
                }
            }
        }
        if (type.equals("array")) {
            Object items = schema.get("items");
            if (!(items instanceof Map<?, ?> itemSchema)) {
                throw new IllegalArgumentException("Input array schema requires an item schema at " + schemaLocation);
            }
            validateSupportedSchema(stringObjectMap(itemSchema), schemaLocation + "[]");
        }
        Object enumValues = schema.get("enum");
        if (schema.containsKey("enum") && !(enumValues instanceof List<?>)) {
            throw new IllegalArgumentException("Input schema enum must be an array at " + schemaLocation);
        }
        if (enumValues instanceof List<?> values) {
            if (values.isEmpty() || containsJsonDuplicates(values)) {
                throw new IllegalArgumentException("Input schema enum must contain unique values at " + schemaLocation);
            }
            if (values.stream().anyMatch(value -> hasMismatchedType(type, value))) {
                throw new IllegalArgumentException("Input schema enum contains a value of the wrong type at " + schemaLocation);
            }
        }
        if (schema.containsKey("const") && hasMismatchedType(type, schema.get("const"))) {
            throw new IllegalArgumentException("Input schema const has the wrong type at " + schemaLocation);
        }
        validateNumericSchema(schema, type, schemaLocation);
    }

    private static void validateKnownStringFormat(Map<String, Object> schema, String schemaLocation) {
        Object rawFormat = schema.get("format");
        if (!(rawFormat instanceof String format) || !STRING_FORMATS.contains(format) || isStringCompatible(schema)) {
            return;
        }
        throw new IllegalArgumentException("Input schema format requires a string type at " + schemaLocation);
    }

    private static boolean isStringCompatible(Map<String, Object> schema) {
        if (schema.get("type") instanceof String type) {
            return type.equals("string");
        }
        Object alternatives = schema.get("oneOf");
        if (!(alternatives instanceof List<?> branches) || branches.isEmpty()) {
            return false;
        }
        return branches.stream().allMatch(branch -> branch instanceof Map<?, ?> branchSchema && isStringCompatible(stringObjectMap(branchSchema)));
    }

    private static boolean containsJsonDuplicates(List<?> values) {
        for (int left = 0; left < values.size(); left++) {
            for (int right = left + 1; right < values.size(); right++) {
                if (jsonEquals(values.get(left), values.get(right))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void rejectUnknownKeywords(Map<String, Object> schema, Set<String> allowed, String schemaLocation) {
        for (String keyword : schema.keySet()) {
            if (!allowed.contains(keyword)) {
                throw new IllegalArgumentException("Unsupported input schema keyword at " + schemaLocation + ": " + keyword);
            }
        }
    }

    private static boolean hasMismatchedType(String type, Object value) {
        return !switch (type) {
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "integer" -> isJsonInteger(value);
            case "number" -> isJsonNumber(value);
            case "null" -> value == null;
            default -> false;
        };
    }

    private static void validateNumericSchema(Map<String, Object> schema, String type, String schemaLocation) {
        if (!type.equals("integer") && !type.equals("number")) {
            return;
        }
        Object minimum = schema.get("minimum");
        Object maximum = schema.get("maximum");
        if (schema.containsKey("minimum") && !(minimum instanceof Number) || schema.containsKey("maximum") && !(maximum instanceof Number)) {
            throw new IllegalArgumentException("Input schema bounds must be finite numbers at " + schemaLocation);
        }
        if (minimum instanceof Number lower && maximum instanceof Number upper && decimal(lower).compareTo(decimal(upper)) > 0) {
            throw new IllegalArgumentException("Input schema maximum must not be below minimum at " + schemaLocation);
        }
    }

    private static Map<String, Object> stringObjectMap(Map<?, ?> map) {
        @SuppressWarnings("unchecked") Map<String, Object> typed = (Map<String, Object>) map;
        return typed;
    }

    private static BigDecimal decimal(Number number) {
        return new BigDecimal(number.toString());
    }

    static boolean isJsonNumber(Object value) {
        return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof java.math.BigInteger || value instanceof java.math.BigDecimal || value instanceof Float fFloatingPoint && Float.isFinite(fFloatingPoint) || value instanceof Double dFloatingPoint && Double.isFinite(dFloatingPoint);
    }

    static boolean jsonEquals(Object left, Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            return decimal(leftNumber).compareTo(decimal(rightNumber)) == 0;
        }
        if (left instanceof Map<?, ?> leftObject && right instanceof Map<?, ?> rightObject) {
            if (!leftObject.keySet().equals(rightObject.keySet())) {
                return false;
            }
            return leftObject.entrySet().stream().allMatch(entry -> jsonEquals(entry.getValue(), rightObject.get(entry.getKey())));
        }
        if (left instanceof List<?> leftArray && right instanceof List<?> rightArray) {
            if (leftArray.size() != rightArray.size()) {
                return false;
            }
            for (int index = 0; index < leftArray.size(); index++) {
                if (!jsonEquals(leftArray.get(index), rightArray.get(index))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(left, right);
    }

    static boolean isJsonInteger(Object value) {
        if (value == null) {
            return false;
        }
        return switch (value) {
            case Byte _, Short _, Integer _, Long _, java.math.BigInteger _ -> true;
            case java.math.BigDecimal decimal -> decimal.stripTrailingZeros().scale() <= 0;
            case Float floatingPoint -> Float.isFinite(floatingPoint) && floatingPoint == Math.rint(floatingPoint);
            case Double floatingPoint -> Double.isFinite(floatingPoint) && floatingPoint == Math.rint(floatingPoint);
            default -> false;
        };
    }
}

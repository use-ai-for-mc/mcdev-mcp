package dev.mcdevmcp.mcp.tool.api;

import com.fasterxml.jackson.annotation.*;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

public final class RecordInputSchemaFactory implements InputSchemaFactory {
    private static final RecordInputSchemaFactory STANDARD = new RecordInputSchemaFactory(JsonTypeRegistry.standard());
    private final JsonTypeRegistry registry;

    private RecordInputSchemaFactory(JsonTypeRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public static RecordInputSchemaFactory standard() {
        return STANDARD;
    }

    public static RecordInputSchemaFactory of(JsonTypeRegistry registry) {
        return new RecordInputSchemaFactory(registry);
    }

    @Override
    public JsonObjectSchema generate(JsonType<?> type) {
        Class<?> recordType = Objects.requireNonNull(type, "type").rawClass();
        if (recordType == null || !recordType.isRecord()) {
            throw new IllegalArgumentException("Tool input roots must be records");
        }
        return JsonObjectSchema.of(objectSchema(recordType, new HashSet<>()));
    }

    private Map<String, Object> schemaFor(Type type, Set<Class<?>> activeRecords) {
        if (type instanceof WildcardType || type instanceof TypeVariable<?>) {
            throw new IllegalArgumentException("Wildcard and type-variable input components are unsupported: " + type.getTypeName());
        }
        JsonLogicalType<?> logicalType = registry.find(type).orElse(null);
        if (logicalType != null) {
            return logicalType.inputSchema().map(schema -> JsonSchemaSupport.mutableObject(schema.value())).orElseThrow(() -> new IllegalArgumentException("Registered input type has no input schema: " + type.getTypeName()));
        }
        if (type instanceof ParameterizedType parameterizedType) {
            return collectionSchema(parameterizedType, activeRecords);
        }
        if (!(type instanceof Class<?> componentType)) {
            throw new IllegalArgumentException("Unsupported input component type: " + type.getTypeName());
        }
        if (componentType == Object.class || Map.class.isAssignableFrom(componentType)) {
            throw new IllegalArgumentException("Unsupported input component type: " + componentType.getTypeName());
        }
        if (Collection.class.isAssignableFrom(componentType)) {
            throw new IllegalArgumentException("Raw collection input components are unsupported: " + componentType.getTypeName());
        }
        if (componentType.isArray()) {
            return arraySchema(schemaFor(componentType.getComponentType(), activeRecords));
        }
        if (componentType == String.class) {
            return schemaWithType("string");
        }
        if (componentType == boolean.class || componentType == Boolean.class) {
            return schemaWithType("boolean");
        }
        if (isIntegral(componentType)) {
            return schemaWithType("integer");
        }
        if (isDecimal(componentType)) {
            return schemaWithType("number");
        }
        if (componentType.isEnum()) return enumSchema(componentType, activeRecords);
        if (componentType.isSealed()) {
            return sealedUnionSchema(componentType, activeRecords);
        }
        if (componentType.isRecord()) {
            return recordSchema(componentType, activeRecords);
        }
        throw new IllegalArgumentException("Unsupported input component type: " + componentType.getTypeName());
    }

    private Map<String, Object> collectionSchema(ParameterizedType type, Set<Class<?>> activeRecords) {
        if (!(type.getRawType() instanceof Class<?> rawType) || !Collection.class.isAssignableFrom(rawType)) {
            throw new IllegalArgumentException("Unsupported parameterized input component type: " + type.getTypeName());
        }
        Type[] arguments = type.getActualTypeArguments();
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Unsupported parameterized input component type: " + type.getTypeName());
        }
        return arraySchema(schemaFor(arguments[0], activeRecords));
    }

    private static Map<String, Object> arraySchema(Map<String, Object> items) {
        Map<String, Object> schema = schemaWithType("array");
        schema.put("items", items);
        return schema;
    }

    private Map<String, Object> objectSchema(Class<?> recordType, Set<Class<?>> activeRecords) {
        if (!activeRecords.add(recordType)) {
            throw new IllegalArgumentException("Recursive input records are unsupported: " + recordType.getTypeName());
        }
        try {
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            for (RecordComponent component : recordType.getRecordComponents()) {
                String propertyName = propertyName(component);
                if (properties.containsKey(propertyName)) {
                    throw new IllegalArgumentException("Duplicate input property name: " + propertyName);
                }
                Map<String, Object> propertySchema = schemaFor(component.getGenericType(), activeRecords);
                applyMetadata(component.getAnnotation(InputProperty.class), propertySchema);
                properties.put(propertyName, propertySchema);
                if (component.isAnnotationPresent(InputProperty.class) && component.getAnnotation(InputProperty.class).required()) {
                    required.add(propertyName);
                }
            }
            Map<String, Object> schema = schemaWithType("object");
            schema.put("properties", properties);
            if (!required.isEmpty()) {
                schema.put("required", required);
            }
            schema.put("additionalProperties", false);
            return schema;
        } finally {
            activeRecords.remove(recordType);
        }
    }

    private Map<String, Object> recordSchema(Class<?> recordType, Set<Class<?>> activeRecords) {
        Type delegatingType = delegatingCreatorType(recordType);
        JsonValueAccessor jsonValue = jsonValueAccessor(recordType);
        if (delegatingType == null) {
            if (jsonValue != null) {
                throw new IllegalArgumentException("JsonValue records require one delegating JsonCreator: " + recordType.getTypeName());
            }
            return objectSchema(recordType, activeRecords);
        }
        Map<String, Object> schema = schemaFor(delegatingType, activeRecords);
        if (jsonValue != null && !schema.get("type").equals(schemaFor(jsonValue.type(), activeRecords).get("type"))) {
            throw new IllegalArgumentException("JsonValue type must match the delegating JsonCreator input: " + recordType.getTypeName());
        }
        return schema;
    }

    private Map<String, Object> sealedUnionSchema(Class<?> unionType, Set<Class<?>> activeRecords) {
        JsonTypeInfo typeInfo = unionType.getAnnotation(JsonTypeInfo.class);
        JsonSubTypes subTypes = validatedUnionSubTypes(unionType, typeInfo);
        if (!activeRecords.add(unionType)) {
            throw new IllegalArgumentException("Recursive input union is unsupported: " + unionType.getTypeName());
        }
        try {
            Set<Class<?>> permitted = Set.of(unionType.getPermittedSubclasses());
            Set<Class<?>> declaredTypes = new HashSet<>();
            Set<String> declaredNames = new HashSet<>();
            List<Map<String, Object>> variants = new ArrayList<>();
            for (JsonSubTypes.Type declared : subTypes.value()) {
                Class<?> subtype = declared.value();
                if (!permitted.contains(subtype) || !subtype.isRecord()) {
                    throw new IllegalArgumentException("Input union subtype must be a permitted record: " + subtype.getTypeName());
                }
                if (delegatingCreatorType(subtype) != null || jsonValueAccessor(subtype) != null) {
                    throw new IllegalArgumentException("Input union subtypes cannot use delegating JsonCreator or JsonValue: " + subtype.getTypeName());
                }
                if (declared.name().isBlank()) {
                    throw new IllegalArgumentException("Input union subtype requires an explicit Jackson name: " + subtype.getTypeName());
                }
                if (!declaredTypes.add(subtype)) {
                    throw new IllegalArgumentException("Duplicate input union subtype: " + subtype.getTypeName());
                }
                List<String> names = new ArrayList<>();
                names.add(declared.name());
                names.addAll(List.of(declared.names()));
                for (String name : names) {
                    if (name.isBlank() || !declaredNames.add(name)) {
                        throw new IllegalArgumentException("Duplicate or blank input union subtype name: " + name);
                    }
                    variants.add(discriminatedVariant(subtype, typeInfo.property(), name, activeRecords));
                }
            }
            if (!declaredTypes.equals(permitted)) {
                throw new IllegalArgumentException("Every permitted input union subtype must have Jackson metadata: " + unionType.getTypeName());
            }
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("oneOf", variants);
            return schema;
        } finally {
            activeRecords.remove(unionType);
        }
    }

    private static JsonSubTypes validatedUnionSubTypes(Class<?> unionType, JsonTypeInfo typeInfo) {
        JsonSubTypes subTypes = unionType.getAnnotation(JsonSubTypes.class);
        if (typeInfo == null || subTypes == null || typeInfo.use() != JsonTypeInfo.Id.NAME || typeInfo.include() != JsonTypeInfo.As.PROPERTY || typeInfo.property().isBlank() || typeInfo.visible() || typeInfo.defaultImpl() != JsonTypeInfo.class || typeInfo.requireTypeIdForSubtypes() != com.fasterxml.jackson.annotation.OptBoolean.DEFAULT) {
            throw new IllegalArgumentException("Sealed input unions require Jackson NAME/PROPERTY type metadata: " + unionType.getTypeName());
        }
        return subTypes;
    }

    private Map<String, Object> discriminatedVariant(Class<?> subtype, String discriminator, String name, Set<Class<?>> activeRecords) {
        Map<String, Object> variant = objectSchema(subtype, activeRecords);
        @SuppressWarnings("unchecked") Map<String, Object> generatedProperties = (Map<String, Object>) variant.get("properties");
        if (generatedProperties.containsKey(discriminator)) {
            throw new IllegalArgumentException("Input union discriminator collides with a record component: " + discriminator);
        }
        Map<String, Object> discriminatorSchema = schemaWithType("string");
        discriminatorSchema.put("const", name);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(discriminator, discriminatorSchema);
        properties.putAll(generatedProperties);
        variant.put("properties", properties);

        List<String> required = new ArrayList<>();
        required.add(discriminator);
        Object generatedRequired = variant.get("required");
        if (generatedRequired instanceof List<?> names) {
            names.forEach(requiredName -> required.add((String) requiredName));
        }
        variant.put("required", required);
        return variant;
    }

    private Map<String, Object> enumSchema(Class<?> enumType, Set<Class<?>> activeRecords) {
        Type delegatingType = delegatingCreatorType(enumType);
        JsonValueAccessor jsonValue = jsonValueAccessor(enumType);
        if (delegatingType != null && jsonValue == null) {
            throw new IllegalArgumentException("Enums with JsonCreator require JsonValue schema metadata: " + enumType.getTypeName());
        }
        Map<String, Object> schema = jsonValue == null ? schemaWithType("string") : schemaFor(jsonValue.type(), activeRecords);
        List<Object> values = new ArrayList<>();
        Set<Object> wireValues = new HashSet<>();
        for (Object constant : enumType.getEnumConstants()) {
            Object wireValue = jsonValue == null ? enumPropertyName((Enum<?>) constant) : jsonValue.read(constant);
            if (!wireValues.add(jsonDataModelKey(wireValue))) {
                throw new IllegalArgumentException("Duplicate effective enum wire value: " + wireValue + " for " + enumType.getTypeName());
            }
            values.add(wireValue);
        }
        schema.put("enum", values);
        return schema;
    }

    private static Object jsonDataModelKey(Object value) {
        if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros();
        if (value instanceof BigInteger integer) return new BigDecimal(integer);
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof Float floatingPoint && Float.isFinite(floatingPoint)) {
            return new BigDecimal(Float.toString(floatingPoint)).stripTrailingZeros();
        }
        if (value instanceof Double floatingPoint && Double.isFinite(floatingPoint)) {
            return BigDecimal.valueOf(floatingPoint).stripTrailingZeros();
        }
        return value;
    }

    private static String enumPropertyName(Enum<?> constant) {
        try {
            JsonProperty annotation = constant.getDeclaringClass().getField(constant.name()).getAnnotation(JsonProperty.class);
            return annotation == null || annotation.value().isEmpty() ? constant.name() : annotation.value();
        } catch (NoSuchFieldException exception) {
            throw new IllegalStateException("Enum constant field was not found: " + constant.name(), exception);
        }
    }

    private static Type delegatingCreatorType(Class<?> type) {
        List<Type> creatorTypes = new ArrayList<>();
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            JsonCreator annotation = constructor.getAnnotation(JsonCreator.class);
            if (annotation != null && annotation.mode() == JsonCreator.Mode.DELEGATING) {
                creatorTypes.add(creatorParameterType(type, annotation, constructor.getParameterCount(), constructor.getGenericParameterTypes()));
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            JsonCreator annotation = method.getAnnotation(JsonCreator.class);
            if (annotation != null && annotation.mode() == JsonCreator.Mode.DELEGATING) {
                if (!Modifier.isStatic(method.getModifiers()) || !type.isAssignableFrom(method.getReturnType())) {
                    throw new IllegalArgumentException("JsonCreator factory must be static and return " + type.getTypeName());
                }
                creatorTypes.add(creatorParameterType(type, annotation, method.getParameterCount(), method.getGenericParameterTypes()));
            }
        }
        if (creatorTypes.size() > 1) {
            throw new IllegalArgumentException("Only one JsonCreator is supported for input type: " + type.getTypeName());
        }
        return creatorTypes.isEmpty() ? null : creatorTypes.getFirst();
    }

    private static Type creatorParameterType(Class<?> type, JsonCreator annotation, int parameterCount, Type[] parameterTypes) {
        if (annotation.mode() != JsonCreator.Mode.DELEGATING || parameterCount != 1) {
            throw new IllegalArgumentException("JsonCreator must be a one-parameter delegating creator: " + type.getTypeName());
        }
        return parameterTypes[0];
    }

    private static JsonValueAccessor jsonValueAccessor(Class<?> type) {
        List<JsonValueAccessor> accessors = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            JsonValue annotation = method.getAnnotation(JsonValue.class);
            if (annotation != null && annotation.value()) {
                if (Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0 || method.getReturnType() == void.class) {
                    throw new IllegalArgumentException("JsonValue method must be a non-static zero-argument value: " + type.getTypeName());
                }
                accessors.add(JsonValueAccessor.forMethod(method));
            }
        }
        for (Field field : type.getDeclaredFields()) {
            JsonValue annotation = field.getAnnotation(JsonValue.class);
            if (annotation != null && annotation.value()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    throw new IllegalArgumentException("JsonValue field must be an instance value: " + type.getTypeName());
                }
                accessors.add(JsonValueAccessor.forField(field));
            }
        }
        if (accessors.size() > 1) {
            throw new IllegalArgumentException("Only one JsonValue accessor is supported for input type: " + type.getTypeName());
        }
        return accessors.isEmpty() ? null : accessors.getFirst();
    }

    private static String propertyName(RecordComponent component) {
        JsonProperty annotation = component.getAnnotation(JsonProperty.class);
        if (annotation == null) {
            annotation = component.getAccessor().getAnnotation(JsonProperty.class);
        }
        return annotation == null || annotation.value().isEmpty() ? component.getName() : annotation.value();
    }

    private static void applyMetadata(InputProperty metadata, Map<String, Object> schema) {
        if (metadata == null) {
            return;
        }
        if (!metadata.description().isEmpty()) {
            schema.put("description", metadata.description());
        }
        if ((!metadata.minimum().isEmpty() || !metadata.maximum().isEmpty()) && !"integer".equals(schema.get("type")) && !"number".equals(schema.get("type"))) {
            throw new IllegalArgumentException("Bounds are only supported for numeric input properties");
        }
        if (!metadata.minimum().isEmpty()) {
            schema.put("minimum", parseDecimal(metadata.minimum(), "minimum"));
        }
        if (!metadata.maximum().isEmpty()) {
            schema.put("maximum", parseDecimal(metadata.maximum(), "maximum"));
        }
        if (schema.containsKey("minimum") && schema.containsKey("maximum") && ((BigDecimal) schema.get("maximum")).compareTo((BigDecimal) schema.get("minimum")) < 0) {
            throw new IllegalArgumentException("Input property maximum must not be below minimum");
        }
        if (!metadata.defaultValue().isEmpty()) {
            schema.put("default", parseDefault(metadata.defaultValue(), schema));
        }
    }

    private static BigDecimal parseDecimal(String value, String metadataName) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid " + metadataName + " decimal: " + value, exception);
        }
    }

    private static Object parseDefault(String value, Map<String, Object> schema) {
        String type = (String) schema.get("type");
        return switch (type) {
            case "string" -> value;
            case "boolean" -> parseBoolean(value);
            case "integer" -> parseInteger(value);
            case "number" -> parseDecimal(value, "default");
            default -> throw new IllegalArgumentException("Defaults are unsupported for input property type: " + type);
        };
    }

    private static boolean parseBoolean(String value) {
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException("Invalid boolean default: " + value);
        }
        return Boolean.parseBoolean(value);
    }

    private static BigInteger parseInteger(String value) {
        try {
            return new BigDecimal(value).toBigIntegerExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException("Invalid integer default: " + value, exception);
        }
    }

    private static boolean isIntegral(Class<?> type) {
        return type == byte.class || type == Byte.class || type == short.class || type == Short.class || type == int.class || type == Integer.class || type == long.class || type == Long.class || type == BigInteger.class;
    }

    private static boolean isDecimal(Class<?> type) {
        return type == float.class || type == Float.class || type == double.class || type == Double.class || type == BigDecimal.class;
    }

    private static Map<String, Object> schemaWithType(String type) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        return schema;
    }

    private record JsonValueAccessor(Type type, Method method, Field field) {
        static JsonValueAccessor forMethod(Method method) {
            return new JsonValueAccessor(method.getGenericReturnType(), method, null);
        }

        static JsonValueAccessor forField(Field field) {
            return new JsonValueAccessor(field.getGenericType(), null, field);
        }

        Object read(Object target) {
            try {
                if (method != null) {
                    if (!method.trySetAccessible()) {
                        throw new IllegalArgumentException("JsonValue method is inaccessible: " + method);
                    }
                    return method.invoke(target);
                }
                if (!field.trySetAccessible()) {
                    throw new IllegalArgumentException("JsonValue field is inaccessible: " + field);
                }
                return field.get(target);
            } catch (IllegalAccessException | InvocationTargetException exception) {
                throw new IllegalArgumentException("Cannot read JsonValue metadata", exception);
            }
        }
    }
}
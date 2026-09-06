package dev.mcdevmcp.mcp.tool.api;

import io.modelcontextprotocol.json.TypeRef;

import java.lang.reflect.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Immutable metadata registry keyed by complete structural Java {@link Type} values.
 */
public final class JsonTypeRegistry {
    private static final JsonTypeRegistry STANDARD = of(List.of(JsonLogicalType.of("jdk.duration-seconds.v1", Duration.class, JsonValueSchema.of(Map.of("type", "number")), JsonValueSchema.of(Map.of("type", "string", "format", "duration"))), JsonLogicalType.bidirectional("jdk.uuid.v1", UUID.class, JsonValueSchema.of(Map.of("type", "string", "format", "uuid"))), JsonLogicalType.bidirectional("jdk.instant.v1", Instant.class, JsonValueSchema.of(Map.of("type", "string", "format", "date-time"))), JsonLogicalType.bidirectional("jdk.local-date.v1", LocalDate.class, JsonValueSchema.of(Map.of("type", "string", "format", "date")))));

    private final Map<Type, JsonLogicalType<?>> byType;
    private final Map<String, JsonLogicalType<?>> byId;

    private JsonTypeRegistry(Map<Type, JsonLogicalType<?>> byType, Map<String, JsonLogicalType<?>> byId) {
        this.byType = Collections.unmodifiableMap(new LinkedHashMap<>(byType));
        this.byId = Collections.unmodifiableMap(new LinkedHashMap<>(byId));
    }

    public static JsonTypeRegistry of(Collection<? extends JsonLogicalType<?>> entries) {
        Objects.requireNonNull(entries, "entries");
        Map<Type, JsonLogicalType<?>> byType = new LinkedHashMap<>();
        Map<String, JsonLogicalType<?>> byId = new LinkedHashMap<>();
        for (JsonLogicalType<?> entry : entries) {
            JsonLogicalType<?> requiredEntry = Objects.requireNonNull(entry, "entry");
            Type targetType = requiredEntry.targetType().javaType();
            validateType(targetType, Collections.newSetFromMap(new IdentityHashMap<>()));
            if (byId.putIfAbsent(requiredEntry.id(), requiredEntry) != null) {
                throw new IllegalArgumentException("Duplicate logical JSON type ID: " + requiredEntry.id());
            }
            if (byType.putIfAbsent(targetType, requiredEntry) != null) {
                throw new IllegalArgumentException("Duplicate logical JSON target type: " + targetType.getTypeName());
            }
        }
        return new JsonTypeRegistry(byType, byId);
    }

    public static JsonTypeRegistry standard() {
        return STANDARD;
    }

    public Optional<JsonLogicalType<?>> find(Type exactType) {
        return Optional.ofNullable(byType.get(Objects.requireNonNull(exactType, "exactType")));
    }

    public Optional<JsonLogicalType<?>> find(TypeRef<?> exactType) {
        return find(Objects.requireNonNull(exactType, "exactType").getType());
    }

    public Optional<JsonLogicalType<?>> find(String logicalId) {
        return Optional.ofNullable(byId.get(Objects.requireNonNull(logicalId, "logicalId")));
    }

    private static void validateType(Type type, Set<Type> activeTypes) {
        Objects.requireNonNull(type, "type");
        switch (type) {
            case WildcardType _ ->
                    throw new IllegalArgumentException("Wildcard JSON target types are unsupported: " + type.getTypeName());
            case TypeVariable<?> _ ->
                    throw new IllegalArgumentException("Type-variable JSON target types are unsupported: " + type.getTypeName());
            case GenericArrayType _ ->
                    throw new IllegalArgumentException("Generic array JSON target types are unsupported: " + type.getTypeName());
            default -> {
            }
        }
        if (!activeTypes.add(type)) {
            throw new IllegalArgumentException("Cyclic JSON target type graph is unsupported: " + type.getTypeName());
        }
        try {
            if (type instanceof Class<?> classType) {
                if (classType.isArray()) {
                    validateType(classType.getComponentType(), activeTypes);
                }
                else if (classType.getTypeParameters().length != 0) {
                    throw new IllegalArgumentException("Raw generic JSON target types are unsupported: " + classType.getTypeName());
                }
                else if (capturesGenericNonStaticOwner(classType)) {
                    throw new IllegalArgumentException("Raw non-static member JSON target types are unsupported: " + classType.getTypeName());
                }
                return;
            }
            if (type instanceof ParameterizedType parameterizedType) {
                Type rawType = parameterizedType.getRawType();
                if (!(rawType instanceof Class<?> rawClass)) {
                    throw new IllegalArgumentException("Unsupported parameterized JSON target type: " + type.getTypeName());
                }
                Type ownerType = parameterizedType.getOwnerType();
                Object rawArguments = actualTypeArguments(parameterizedType);
                if (!(rawArguments instanceof Type[] arguments) || rawClass.getTypeParameters().length != arguments.length) {
                    throw new IllegalArgumentException("Malformed parameterized JSON target type: " + type.getTypeName());
                }
                if (rawClass.getTypeParameters().length == 0 && (ownerType == null || Modifier.isStatic(rawClass.getModifiers()) || !(ownerType instanceof ParameterizedType))) {
                    throw new IllegalArgumentException("Malformed parameterized JSON target type: " + type.getTypeName());
                }
                validateOwner(rawClass, ownerType, activeTypes, type);
                for (Type argument : arguments) {
                    if (argument == null) {
                        throw new IllegalArgumentException("Malformed parameterized JSON target type: " + type.getTypeName());
                    }
                    if (argument instanceof Class<?> argumentClass && argumentClass.isPrimitive()) {
                        throw new IllegalArgumentException("Primitive JSON target type arguments are unsupported: " + type.getTypeName());
                    }
                    validateType(argument, activeTypes);
                }
                return;
            }
            throw new IllegalArgumentException("Unsupported JSON target type: " + type.getTypeName());
        } finally {
            activeTypes.remove(type);
        }
    }

    private static void validateOwner(Class<?> rawClass, Type ownerType, Set<Type> activeTypes, Type targetType) {
        Class<?> declaringClass = rawClass.getDeclaringClass();
        if (declaringClass == null) {
            if (ownerType != null) {
                throw new IllegalArgumentException("Top-level parameterized JSON target must not have an owner: " + targetType.getTypeName());
            }
            return;
        }
        if (ownerType == null) {
            throw new IllegalArgumentException("Member parameterized JSON target must have an owner: " + targetType.getTypeName());
        }
        if (Modifier.isStatic(rawClass.getModifiers())) {
            if (!(ownerType instanceof Class<?> ownerClass) || ownerClass != declaringClass) {
                throw new IllegalArgumentException("Static member parameterized JSON target must use its raw declaring class as owner: " + targetType.getTypeName());
            }
            return;
        }
        validateType(ownerType, activeTypes);
        if (!declaringClass.equals(erasure(ownerType))) {
            throw new IllegalArgumentException("Member parameterized JSON target owner does not match its declaring class: " + targetType.getTypeName());
        }
    }

    private static Object actualTypeArguments(ParameterizedType parameterizedType) {
        return parameterizedType.getActualTypeArguments();
    }

    private static Class<?> erasure(Type type) {
        if (type instanceof Class<?> classType) {
            return classType;
        }
        if (type instanceof ParameterizedType parameterizedType && parameterizedType.getRawType() instanceof Class<?> rawClass) {
            return rawClass;
        }
        throw new IllegalArgumentException("Unsupported JSON owner type: " + type.getTypeName());
    }

    private static boolean capturesGenericNonStaticOwner(Class<?> classType) {
        Class<?> declaringClass = classType.getDeclaringClass();
        if (declaringClass == null || Modifier.isStatic(classType.getModifiers())) {
            return false;
        }
        return declaringClass.getTypeParameters().length != 0 || capturesGenericNonStaticOwner(declaringClass);
    }
}
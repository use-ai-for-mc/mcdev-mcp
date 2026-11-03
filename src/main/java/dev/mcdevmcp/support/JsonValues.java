package dev.mcdevmcp.support;

import java.util.*;

public final class JsonValues {
    private JsonValues() {
    }

    public static Object freeze(Object value) {
        return switch (value) {
            case null -> null;
            case String text -> text;
            case Boolean flag -> flag;
            case Double number when !Double.isFinite(number) ->
                    throw new IllegalArgumentException("JSON numbers must be finite");
            case Float number when !Float.isFinite(number) ->
                    throw new IllegalArgumentException("JSON numbers must be finite");
            case Number number -> number;
            case Map<?, ?> map -> {
                var frozen = new LinkedHashMap<String, Object>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("JSON object keys must be strings");
                    }
                    frozen.put(key, freeze(entry.getValue()));
                }
                yield Collections.unmodifiableMap(frozen);
            }
            case List<?> list -> {
                var frozen = new ArrayList<>(list.size());
                for (Object item : list) {
                    frozen.add(freeze(item));
                }
                yield Collections.unmodifiableList(frozen);
            }
            default -> throw new IllegalArgumentException("Unsupported JSON value type: " + value.getClass().getName());
        };
    }

    public static Map<String, Object> freezeMap(Map<String, ?> values) {
        Objects.requireNonNull(values, "JSON object");
        return asMap(freeze(values));
    }

    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put((String) entry.getKey(), entry.getValue());
            }
            return Collections.unmodifiableMap(result);
        }
        throw new IllegalStateException("Frozen JSON object is not a map");
    }

}
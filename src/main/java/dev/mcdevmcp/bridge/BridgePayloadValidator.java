package dev.mcdevmcp.bridge;

import dev.mcdevmcp.support.JsonValues;

import java.util.Map;
import java.util.Objects;

public final class BridgePayloadValidator {
    public static final int MAXIMUM_PNG_BASE64_CHARACTERS = 7 * 1024 * 1024;
    private static final int MAXIMUM_DISPLAY_CHARACTERS = 512;

    public static Map<String, Object> requireOpenObject(BridgeResponse response) {
        return requireOpenObject("unknown", response);
    }

    public static Map<String, Object> requireOpenObject(String endpoint, BridgeResponse response) {
        Object result = requireResultValue(endpoint, response);
        if (!(result instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("DebugBridge " + endpoint + " response result must be an object, got " + safeDisplay(result));
        }
        @SuppressWarnings("unchecked") Map<String, Object> object = (Map<String, Object>) JsonValues.freeze(result);
        return object;
    }

    public static String requireString(Object value, String name) {
        return requireString("unknown", name, value);
    }

    public static String requireString(String endpoint, String name, Object value) {
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException("DebugBridge " + safeDisplay(endpoint) + " response " + safeDisplay(name) + " must be a string, got " + safeDisplay(value));
    }

    public static boolean requireBoolean(Object value, String name) {
        return requireBoolean("unknown", name, value);
    }

    public static boolean requireBoolean(String endpoint, String name, Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw new IllegalArgumentException("DebugBridge " + safeDisplay(endpoint) + " response " + safeDisplay(name) + " must be a boolean, got " + safeDisplay(value));
    }

    public static long requireIntegralNumber(Object value, String name) {
        return requireIntegralNumber("unknown", name, value);
    }

    public static long requireIntegralNumber(String endpoint, String name, Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        throw new IllegalArgumentException("DebugBridge " + safeDisplay(endpoint) + " response " + safeDisplay(name) + " must be an integer, got " + safeDisplay(value));
    }

    public static String requirePngBase64(String value) {
        return requirePngBase64("unknown", value);
    }

    public static String requirePngBase64(String endpoint, String value) {
        String text = requireString(endpoint, "PNG", value);
        if (text.length() > MAXIMUM_PNG_BASE64_CHARACTERS) {
            throw new IllegalArgumentException("DebugBridge " + safeDisplay(endpoint) + " PNG text exceeds " + MAXIMUM_PNG_BASE64_CHARACTERS + " characters");
        }
        return text;
    }

    public static String safeDisplay(Object value) {
        String display = String.valueOf(value);
        return display.length() <= MAXIMUM_DISPLAY_CHARACTERS ? display : display.substring(0, MAXIMUM_DISPLAY_CHARACTERS) + "...";
    }

    private static Object requireResultValue(String endpoint, BridgeResponse response) {
        Object result = requirePresentResult(endpoint, response);
        if (result == null) {
            throw new IllegalArgumentException("DebugBridge " + safeDisplay(endpoint) + " response is missing result");
        }
        return result;
    }

    public static Object requirePresentResult(String endpoint, BridgeResponse response) {
        Objects.requireNonNull(response, "response");
        String label = safeDisplay(endpoint);
        if (!response.success()) {
            throw new IllegalArgumentException("DebugBridge " + label + " failed: " + safeDisplay(response.error()));
        }
        if (!response.resultPresent()) {
            throw new IllegalArgumentException("DebugBridge " + label + " response is missing result");
        }
        return response.result();
    }

    /**
     * Requires a successful response with a non-null result value.
     */
    public static Object requireResult(String endpoint, BridgeResponse response) {
        return requireResultValue(endpoint, response);
    }

}
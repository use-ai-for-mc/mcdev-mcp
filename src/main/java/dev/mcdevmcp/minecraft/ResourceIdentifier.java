package dev.mcdevmcp.minecraft;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A validated Minecraft resource identifier with the vanilla namespace default.
 */
public record ResourceIdentifier(String value) {
    private static final String DEFAULT_NAMESPACE = "minecraft";

    public ResourceIdentifier {
        value = canonicalValue(value);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ResourceIdentifier fromJson(String value) {
        return new ResourceIdentifier(value);
    }

    @JsonValue
    public String wireValue() {
        return value;
    }

    private static String canonicalValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Resource identifier must not be null");
        }
        int separator = -1;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == ':') {
                if (separator >= 0) {
                    throw invalid(value);
                }
                separator = index;
            }
        }
        String namespace = separator < 0 ? DEFAULT_NAMESPACE : value.substring(0, separator);
        String resourcePath = value.substring(separator + 1);
        if (namespace.isEmpty() || resourcePath.isEmpty() || !validNamespace(namespace) || !validPath(resourcePath)) {
            throw invalid(value);
        }
        return namespace + ':' + resourcePath;
    }

    private static boolean validNamespace(String namespace) {
        if (namespace.equals("..")) {
            return false;
        }
        for (int index = 0; index < namespace.length(); index++) {
            char character = namespace.charAt(index);
            if (invalidNamespaceCharacter(character)) {
                return false;
            }
        }
        return true;
    }

    private static boolean validPath(String resourcePath) {
        for (int index = 0; index < resourcePath.length(); index++) {
            char character = resourcePath.charAt(index);
            if (invalidPathCharacter(character)) {
                return false;
            }
        }
        return true;
    }

    private static boolean invalidNamespaceCharacter(char character) {
        return switch (character) {
            case '_', '.', '-' -> false;
            default -> !(character >= 'a' && character <= 'z' || character >= '0' && character <= '9');
        };
    }

    private static boolean invalidPathCharacter(char character) {
        return switch (character) {
            case '/', '_', '.', '-' -> false;
            default -> !(character >= 'a' && character <= 'z' || character >= '0' && character <= '9');
        };
    }

    private static IllegalArgumentException invalid(String value) {
        return new IllegalArgumentException("Invalid resource identifier: " + value);
    }
}

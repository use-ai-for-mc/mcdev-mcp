package dev.mcdevmcp.app;

import java.util.Objects;

/**
 * Validates supported, path-safe Minecraft release identifiers.
 */
public final class MinecraftVersionValidator {
    private MinecraftVersionValidator() {
    }

    public static boolean isSupported(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.contains("..")) {
            return false;
        }
        int firstEnd = digitsEnd(value, 0);
        if (firstEnd == 0 || firstEnd == value.length() || value.charAt(firstEnd) != '.') {
            return false;
        }
        int major = number(value, 0, firstEnd);
        int minorEnd = digitsEnd(value, firstEnd + 1);
        if (minorEnd == firstEnd + 1) {
            return false;
        }
        int minor = number(value, firstEnd + 1, minorEnd);
        if (major < 0 || minor < 0) {
            return false;
        }
        if (major == 1) {
            return minor >= 14 && numericTail(value, minorEnd);
        }
        return major >= 26 && modernTail(value, minorEnd);
    }

    public static String requireSupported(String value) {
        Objects.requireNonNull(value, "value");
        if (!isSupported(value)) {
            throw new IllegalArgumentException("""
                                               Error: Version %s is not supported.
                                               Supported versions:
                                                 - 1.14 and later (official Mojang mappings required)
                                                 - 26.x and later (26.1, 26.1-snapshot-10, etc.)""".formatted(value));
        }
        return value;
    }

    private static int digitsEnd(String value, int start) {
        int index = start;
        while (index < value.length() && Character.isDigit(value.charAt(index))) {
            index++;
        }
        return index;
    }

    private static int number(String value, int start, int end) {
        try {
            return Integer.parseInt(value.substring(start, end));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static boolean numericTail(String value, int start) {
        if (start == value.length()) {
            return true;
        }
        if (value.charAt(start) != '.') {
            return false;
        }
        int patchEnd = digitsEnd(value, start + 1);
        return patchEnd == value.length() && patchEnd > start + 1;
    }

    private static boolean modernTail(String value, int start) {
        int index = start;
        while (index < value.length() && value.charAt(index) == '.') {
            int componentStart = index + 1;
            int componentEnd = digitsEnd(value, componentStart);
            if (componentEnd == componentStart) {
                return false;
            }
            if (number(value, componentStart, componentEnd) < 0) {
                return false;
            }
            index = componentEnd;
        }
        if (index == value.length()) {
            return true;
        }
        if (value.charAt(index) != '-') {
            return false;
        }
        return qualifierTail(value, index + 1);
    }

    private static boolean qualifierTail(String value, int start) {
        boolean hasSegmentCharacter = false;
        for (int index = start; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                hasSegmentCharacter = true;
            }
            else if (character == '.' || character == '-') {
                if (!hasSegmentCharacter) {
                    return false;
                }
                hasSegmentCharacter = false;
            }
            else {
                return false;
            }
        }
        return hasSegmentCharacter;
    }
}
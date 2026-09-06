package dev.mcdevmcp.storage.model;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

final class PortablePathComponent {
    private static final Set<String> WINDOWS_DEVICE_NAMES = Set.of("CON", "PRN", "AUX", "NUL", "CLOCK$", "CONIN$", "CONOUT$");

    private PortablePathComponent() {
    }

    static void requireValid(String value, String errorMessage) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || value.endsWith(".") || value.endsWith(" ") || value.chars().anyMatch(character -> Character.isISOControl(character) || isWindowsReservedCharacter(character)) || hasWindowsDeviceBasename(value)) {
            throw new IllegalArgumentException(errorMessage + value);
        }
        try {
            Path path = Path.of(value);
            if (path.getRoot() != null || path.isAbsolute() || path.getNameCount() != 1) {
                throw new IllegalArgumentException(errorMessage + value);
            }
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException(errorMessage + value, exception);
        }
    }

    private static boolean hasWindowsDeviceBasename(String value) {
        int extensionSeparator = value.indexOf('.');
        String basename = trimTrailingWindowsDotsAndSpaces(value.substring(0, extensionSeparator < 0 ? value.length() : extensionSeparator)).toUpperCase(Locale.ROOT);
        return WINDOWS_DEVICE_NAMES.contains(basename) || isNumberedWindowsDeviceName(basename);
    }

    private static boolean isNumberedWindowsDeviceName(String basename) {
        if (basename.length() != 4 || !basename.startsWith("COM") && !basename.startsWith("LPT")) {
            return false;
        }
        return switch (basename.charAt(3)) {
            case '1', '2', '3', '4', '5', '6', '7', '8', '9', '¹', '²', '³' -> true;
            default -> false;
        };
    }

    private static String trimTrailingWindowsDotsAndSpaces(String value) {
        int length = value.length();
        while (length > 0 && (value.charAt(length - 1) == '.' || value.charAt(length - 1) == ' ')) {
            length--;
        }
        return value.substring(0, length);
    }

    private static boolean isWindowsReservedCharacter(int character) {
        return switch (character) {
            case '<', '>', ':', '"', '/', '\\', '|', '?', '*' -> true;
            default -> false;
        };
    }
}
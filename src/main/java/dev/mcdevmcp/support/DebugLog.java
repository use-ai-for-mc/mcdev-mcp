package dev.mcdevmcp.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class DebugLog {
    private DebugLog() {
    }

    public static void write(AppEnvironment environment, String message) {
        try {
            String value = environment.value("MCDEV_MCP_DEBUG_LOG").orElse("");
            if (value.isEmpty() || value.equals("off")) {
                return;
            }
            Path path = value.equals("on") ? Path.of("/tmp/mcdev-debug.log") : Path.of(value);
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, message + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            // Debug logging must never affect the protocol stream.
        }
    }
}
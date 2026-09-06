package dev.mcdevmcp.bridge;

import dev.mcdevmcp.storage.model.MinecraftVersion;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record SessionInfo(int port, MinecraftVersion version, BridgeMappingStatus mappingStatus, boolean obfuscated, long refs, Optional<Path> gameDir, Optional<Path> logsDir, Optional<Path> latestLog, Optional<Boolean> latestLogExists, Optional<Path> debugLog, Optional<Boolean> debugLogExists, Optional<Boolean> sessionControlEnabled) {
    public SessionInfo {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Bridge port must be in range: " + port);
        }
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(mappingStatus, "mappingStatus");
        if (refs < 0) {
            throw new IllegalArgumentException("Bridge reference count must not be negative: " + refs);
        }
        gameDir = Objects.requireNonNull(gameDir, "gameDir").map(path -> normalizePath("gameDir", path));
        logsDir = Objects.requireNonNull(logsDir, "logsDir").map(path -> normalizePath("logsDir", path));
        latestLog = Objects.requireNonNull(latestLog, "latestLog").map(path -> normalizePath("latestLog", path));
        Objects.requireNonNull(latestLogExists, "latestLogExists");
        debugLog = Objects.requireNonNull(debugLog, "debugLog").map(path -> normalizePath("debugLog", path));
        Objects.requireNonNull(debugLogExists, "debugLogExists");
        Objects.requireNonNull(sessionControlEnabled, "sessionControlEnabled");
    }

    private static Path normalizePath(String field, Path path) {
        if (!isAbsolutePath(path)) {
            throw new IllegalArgumentException("DebugBridge status " + field + " must be absolute: " + BridgePayloadValidator.safeDisplay(path));
        }
        return path.normalize();
    }

    private static boolean isAbsolutePath(Path path) {
        return path.isAbsolute();
    }
}

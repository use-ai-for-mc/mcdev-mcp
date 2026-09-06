package dev.mcdevmcp.bridge;

import java.util.Objects;

public record BridgeStatusWire(String version, String mappingStatus, Boolean obfuscated, Long refs, String gameDir, String logsDir, String latestLog, Boolean latestLogExists, String debugLog, Boolean debugLogExists, Boolean sessionControlEnabled, Integer webUiPort) {
    public BridgeStatusWire {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(mappingStatus, "mappingStatus");
        Objects.requireNonNull(obfuscated, "obfuscated");
        Objects.requireNonNull(refs, "refs");
    }
}

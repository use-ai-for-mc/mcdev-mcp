package dev.mcdevmcp.bridge;

import java.util.Objects;

public value record BridgeEndpoint(String wireName) {
    public BridgeEndpoint {
        wireName = Objects.requireNonNull(wireName, "wireName").strip();
        if (wireName.isEmpty()) {
            throw new IllegalArgumentException("Bridge endpoint name must not be blank");
        }
    }
}
package dev.mcdevmcp.bridge;

import java.util.Objects;

public record BridgeWireRequest(String id, String type, BridgePayload payload) {
    public BridgeWireRequest {
        id = Objects.requireNonNull(id, "id");
        type = Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Bridge request ID must not be blank");
        }
        if (type.isBlank()) {
            throw new IllegalArgumentException("Bridge request type must not be blank");
        }
    }
}

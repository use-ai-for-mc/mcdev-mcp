package dev.mcdevmcp.bridge;

import dev.mcdevmcp.support.JsonValues;

import java.util.Objects;

public record BridgeResponse(String id, boolean success, boolean resultPresent, Object result, String output, String error) {
    public BridgeResponse {
        id = Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Bridge response ID must not be blank");
        }
        result = JsonValues.freeze(result);
    }
}
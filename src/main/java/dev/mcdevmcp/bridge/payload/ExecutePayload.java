package dev.mcdevmcp.bridge.payload;

import dev.mcdevmcp.bridge.BridgePayload;

import java.util.Objects;

public value record ExecutePayload(String code, long timeoutMs) implements BridgePayload {
    public ExecutePayload {
        code = Objects.requireNonNull(code, "code");
    }
}

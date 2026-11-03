package dev.mcdevmcp.bridge.payload;

import dev.mcdevmcp.bridge.BridgePayload;

import java.util.Objects;

public value record EntityItemTexturePayload(int entityId, String slot) implements BridgePayload {
    public EntityItemTexturePayload {
        slot = Objects.requireNonNull(slot, "slot");
    }
}

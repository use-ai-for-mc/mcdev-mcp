package dev.mcdevmcp.bridge.payload;

import dev.mcdevmcp.bridge.BridgePayload;

import java.util.Objects;

public value record ItemTextureByIdPayload(String itemId) implements BridgePayload {
    public ItemTextureByIdPayload {
        itemId = Objects.requireNonNull(itemId, "itemId");
    }
}

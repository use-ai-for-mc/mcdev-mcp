package dev.mcdevmcp.bridge.payload;

import dev.mcdevmcp.bridge.BridgePayload;

public record EntityDetailsPayload(int entityId) implements BridgePayload {
}

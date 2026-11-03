package dev.mcdevmcp.bridge.payload;

import dev.mcdevmcp.bridge.BridgePayload;

public value record SetEntityGlowPayload(int entityId, boolean glow) implements BridgePayload {
}

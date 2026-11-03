package dev.mcdevmcp.bridge.payload;

import dev.mcdevmcp.bridge.BridgePayload;

public value record SetBlockGlowPayload(int x, int y, int z, boolean glow) implements BridgePayload {
}

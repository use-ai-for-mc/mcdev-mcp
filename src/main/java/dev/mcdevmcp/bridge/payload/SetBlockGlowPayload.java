package dev.mcdevmcp.bridge.payload;

import dev.mcdevmcp.bridge.BridgePayload;

public record SetBlockGlowPayload(int x, int y, int z, boolean glow) implements BridgePayload {
}

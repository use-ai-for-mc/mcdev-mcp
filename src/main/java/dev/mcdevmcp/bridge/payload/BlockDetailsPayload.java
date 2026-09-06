package dev.mcdevmcp.bridge.payload;

import dev.mcdevmcp.bridge.BridgePayload;

public record BlockDetailsPayload(int x, int y, int z) implements BridgePayload {
}

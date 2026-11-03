package dev.mcdevmcp.bridge.payload;

import dev.mcdevmcp.bridge.BridgePayload;

public value record BlockDetailsPayload(int x, int y, int z) implements BridgePayload {
}

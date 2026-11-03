package dev.mcdevmcp.bridge.payload;

import dev.mcdevmcp.bridge.BridgePayload;

public value record ScreenInspectPayload(boolean includeIcons) implements BridgePayload {
}

package dev.mcdevmcp.bridge.payload;

import dev.mcdevmcp.bridge.BridgePayload;

public record ScreenInspectPayload(boolean includeIcons) implements BridgePayload {
}

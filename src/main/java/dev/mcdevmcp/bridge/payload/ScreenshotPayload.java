package dev.mcdevmcp.bridge.payload;

import dev.mcdevmcp.bridge.BridgePayload;

public value record ScreenshotPayload(int downscale, double quality) implements BridgePayload {
}

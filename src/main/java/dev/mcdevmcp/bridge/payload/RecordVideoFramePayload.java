package dev.mcdevmcp.bridge.payload;

import dev.mcdevmcp.bridge.BridgePayload;

import java.util.Objects;

public value record RecordVideoFramePayload(int frames, String interval, String output, int gridCols, int downscale, double quality) implements BridgePayload {
    public RecordVideoFramePayload {
        interval = Objects.requireNonNull(interval, "interval");
        output = Objects.requireNonNull(output, "output");
    }
}

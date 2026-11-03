package dev.mcdevmcp.bridge.payload;

import dev.mcdevmcp.bridge.BridgePayload;

import java.util.Objects;

public value record RecordVideoTimedPayload(int frames, double interval, String output, int gridCols, int downscale, double quality) implements BridgePayload {
    public RecordVideoTimedPayload {
        output = Objects.requireNonNull(output, "output");
    }
}

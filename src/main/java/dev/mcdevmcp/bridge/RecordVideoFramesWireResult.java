package dev.mcdevmcp.bridge;

import java.util.List;
import java.util.Objects;

/**
 * Provider representation of individual video frames.
 */
public record RecordVideoFramesWireResult(List<String> paths, int frameWidth, int frameHeight, String mimeType, int frameCount, long captureMs, double intervalMs, long sizeBytes, int dropped) implements RecordVideoWireResult {
    public RecordVideoFramesWireResult {
        paths = List.copyOf(Objects.requireNonNull(paths, "paths"));
        Objects.requireNonNull(mimeType, "mimeType");
    }
}
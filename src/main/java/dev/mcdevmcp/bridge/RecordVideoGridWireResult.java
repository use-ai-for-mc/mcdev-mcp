package dev.mcdevmcp.bridge;

import java.util.Objects;

/**
 * Provider representation of a grid video recording.
 */
public record RecordVideoGridWireResult(String path, int width, int height, long sizeBytes, String mimeType, int frameCount, int frameWidth, int frameHeight, int gridCols, int gridRows, long captureMs, double intervalMs, int dropped) implements RecordVideoWireResult {
    public RecordVideoGridWireResult {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(mimeType, "mimeType");
    }
}
package dev.mcdevmcp.bridge;

import java.util.Objects;

/**
 * The provider representation of a screenshot result.
 */
public record ScreenshotWireResult(String path, int width, int height, long sizeBytes, String mimeType) {
    public ScreenshotWireResult {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(mimeType, "mimeType");
    }
}
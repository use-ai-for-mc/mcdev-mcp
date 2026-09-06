package dev.mcdevmcp.tools.runtime;

import java.nio.file.Path;
import java.util.Objects;

record ScreenshotResult(Path path, int width, int height, long sizeBytes, String mimeType) {
    public ScreenshotResult {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(mimeType, "mimeType");
    }
}

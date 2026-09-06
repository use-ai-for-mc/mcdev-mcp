package dev.mcdevmcp.tools.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

record RecordVideoGridResult(Path path, int width, int height, long sizeBytes, String mimeType, int frameCount, int frameWidth, int frameHeight, int gridCols, int gridRows, Duration captureDuration, Duration intervalDuration, int dropped) implements RecordVideoResult {
    public RecordVideoGridResult {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(mimeType, "mimeType");
        Objects.requireNonNull(captureDuration, "captureDuration");
        Objects.requireNonNull(intervalDuration, "intervalDuration");
    }

}

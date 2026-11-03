package dev.mcdevmcp.tools.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

value record RecordVideoFramesResult(List<Path> paths, int frameWidth, int frameHeight, String mimeType, int frameCount, Duration captureDuration, Duration intervalDuration, long sizeBytes, int dropped) implements RecordVideoResult {
    public RecordVideoFramesResult {
        paths = List.copyOf(Objects.requireNonNull(paths, "paths"));
        Objects.requireNonNull(mimeType, "mimeType");
        Objects.requireNonNull(captureDuration, "captureDuration");
        Objects.requireNonNull(intervalDuration, "intervalDuration");
    }

}

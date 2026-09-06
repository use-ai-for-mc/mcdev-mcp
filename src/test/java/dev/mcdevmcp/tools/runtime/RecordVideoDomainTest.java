package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.RecordVideoFramesWireResult;
import dev.mcdevmcp.bridge.RecordVideoGridWireResult;
import dev.mcdevmcp.bridge.ScreenshotWireResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class RecordVideoDomainTest {
    @Test
    void projectsCaptureMillisecondsExactlyAndRoundsIntervalsToNanoseconds() {
        Path nativePath = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().resolve("grid.jpg");
        var grid = new RecordVideoGridWireResult(nativePath.toString(), 640, 360, 2_560L, "image/jpeg", 4, 320, 180, 2, 2, 200L, 17.2, 0);
        RecordVideoResult projected = MediaToolSupport.project(grid);

        RecordVideoGridResult result = assertInstanceOf(RecordVideoGridResult.class, projected);
        assertEquals(nativePath, result.path());
        assertEquals(Duration.ofMillis(200), result.captureDuration());
        assertEquals(Duration.ofNanos(17_200_000L), result.intervalDuration());
    }

    @Test
    void preservesSubMillisecondAndTieRoundingWithinTheSafeBound() {
        assertEquals(Duration.ZERO, MediaToolSupport.intervalDuration(0));
        assertEquals(Duration.ofNanos(1), MediaToolSupport.intervalDuration(0.0000005));
        assertEquals(Duration.ofNanos(2), MediaToolSupport.intervalDuration(0.0000015));
        assertEquals(Duration.ofNanos(Math.round(MediaToolSupport.MAX_INTERVAL_MILLIS * 1_000_000d)), MediaToolSupport.intervalDuration(MediaToolSupport.MAX_INTERVAL_MILLIS));
        assertThrows(IllegalArgumentException.class, () -> MediaToolSupport.intervalDuration(-0.1));
        assertThrows(IllegalArgumentException.class, () -> MediaToolSupport.intervalDuration(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> MediaToolSupport.intervalDuration(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> MediaToolSupport.intervalDuration(MediaToolSupport.MAX_INTERVAL_MILLIS + 0.1));
    }

    @Test
    void projectsFramePathsWithoutReencodingTheWireValues() {
        Path directory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        var framePaths = List.of(directory.resolve("frame-1.jpg"), directory.resolve("frame-2.jpg"));
        var frames = new RecordVideoFramesWireResult(framePaths.stream().map(Path::toString).toList(), 320, 180, "image/jpeg", 2, 34L, 17.2, 1_024L, 1);
        RecordVideoFramesResult result = assertInstanceOf(RecordVideoFramesResult.class, MediaToolSupport.project(frames));

        assertEquals(framePaths, result.paths());
        assertEquals(Duration.ofMillis(34), result.captureDuration());
        assertEquals(Duration.ofNanos(17_200_000L), result.intervalDuration());
    }

    @Test
    void rejectsRelativeMediaPathsInsteadOfTreatingThemAsLocalAbsoluteFiles() {
        assertThrows(IllegalArgumentException.class, () -> MediaToolSupport.project(new ScreenshotWireResult("relative/shot.jpg", 1, 1, 1, "image/jpeg")));
        assertThrows(IllegalArgumentException.class, () -> MediaToolSupport.project(new RecordVideoGridWireResult("relative/grid.jpg", 1, 1, 1, "image/jpeg", 1, 1, 1, 1, 1, 1, 1, 0)));
        assertThrows(IllegalArgumentException.class, () -> MediaToolSupport.project(new RecordVideoFramesWireResult(List.of("relative/frame.jpg"), 1, 1, "image/jpeg", 1, 1, 1, 1, 0)));
    }
}

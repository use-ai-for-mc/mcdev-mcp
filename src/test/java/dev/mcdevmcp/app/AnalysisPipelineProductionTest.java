package dev.mcdevmcp.app;

import dev.mcdevmcp.analysis.index.IndexRequest;
import dev.mcdevmcp.storage.PlatformPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AnalysisPipelineProductionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void productionCompositionHonorsConfiguredAndDefaultIndexParallelism() {
        PlatformPaths paths = new PlatformPaths(temporaryDirectory.resolve("cache"));

        AnalysisPipeline configured = AnalysisPipeline.production(paths, Map.of(IndexRequest.THREADS_ENVIRONMENT_VARIABLE, "1"));
        AnalysisPipeline defaults = AnalysisPipeline.production(paths, Map.of());

        assertEquals(1, configured.parallelism());
        assertEquals(Runtime.getRuntime().availableProcessors(), defaults.parallelism());
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> AnalysisPipeline.production(paths, Map.of(IndexRequest.THREADS_ENVIRONMENT_VARIABLE, "invalid")));
        assertTrue(failure.getMessage().contains(IndexRequest.THREADS_ENVIRONMENT_VARIABLE));
        assertTrue(failure.getMessage().contains("'invalid'"));
    }
}

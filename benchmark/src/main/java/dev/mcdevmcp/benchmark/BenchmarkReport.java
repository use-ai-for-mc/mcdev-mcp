package dev.mcdevmcp.benchmark;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Persisted same-runner benchmark evidence.
 */
public record BenchmarkReport(int schemaVersion, String runId, String machineId, Instant createdAt, String sourceRootSha256, String remappedJarSha256, String serverJarSha256, BenchmarkRuntimeMetadata runtime, BenchmarkResult result, BenchmarkMedians medians, List<BenchmarkMeasurement> measurements, CorpusClasspathEvidence classpath) {
    public BenchmarkReport {
        if (schemaVersion != 2) {
            throw new IllegalArgumentException("Unsupported benchmark report schema " + schemaVersion);
        }
        Objects.requireNonNull(classpath, "classpath");
        runId = requireText(runId, "runId");
        machineId = requireText(machineId, "machineId");
        Objects.requireNonNull(createdAt, "createdAt");
        sourceRootSha256 = requireText(sourceRootSha256, "sourceRootSha256");
        remappedJarSha256 = requireText(remappedJarSha256, "remappedJarSha256");
        serverJarSha256 = requireText(serverJarSha256, "serverJarSha256");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(medians, "medians");
        measurements = List.copyOf(measurements);
        if (measurements.size() != 5) {
            throw new IllegalArgumentException("Benchmark report must contain five raw measurements");
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must be present");
        }
        return value;
    }
}
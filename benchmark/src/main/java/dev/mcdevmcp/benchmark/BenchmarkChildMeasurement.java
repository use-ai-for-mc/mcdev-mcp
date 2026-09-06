package dev.mcdevmcp.benchmark;

import java.util.Objects;

/**
 * Typed child-JVM result used by the parent benchmark process.
 */
public record BenchmarkChildMeasurement(BenchmarkPhase phase, long units, long elapsedNanos, long peakRssBytes, long gcCollections, long gcTimeMillis, BenchmarkWorkCounts counts, BenchmarkRuntimeMetadata runtime, int schemaVersion, String classpathIdentity, String classpathManifestSha256) {
    public BenchmarkChildMeasurement {
        if (schemaVersion != 2) {
            throw new IllegalArgumentException("Unsupported benchmark child schema " + schemaVersion);
        }
        classpathIdentity = CorpusExpectation.requireSha256(classpathIdentity, "classpathIdentity");
        classpathManifestSha256 = CorpusExpectation.requireSha256(classpathManifestSha256, "classpathManifestSha256");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(counts, "counts");
        Objects.requireNonNull(runtime, "runtime");
        if (units <= 0 || elapsedNanos <= 0 || peakRssBytes <= 0 || gcCollections < 0 || gcTimeMillis < 0) {
            throw new IllegalArgumentException("Benchmark child metrics are invalid");
        }
        if (units != counts.units()) {
            throw new IllegalArgumentException("Benchmark units do not match the work counts");
        }
    }

    public double throughputPerSecond() {
        return units / (elapsedNanos / 1_000_000_000.0d);
    }
}
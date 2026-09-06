package dev.mcdevmcp.benchmark;

/**
 * Measurements from one JVM over one immutable corpus.
 */
public record BenchmarkResult(int javaFeature, String vendor, String vmFlags, double indexClassesPerSecond, double callEdgesPerSecond, long indexPeakRssBytes, long callgraphPeakRssBytes) {
}

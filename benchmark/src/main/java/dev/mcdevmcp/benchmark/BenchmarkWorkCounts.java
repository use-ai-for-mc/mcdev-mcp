package dev.mcdevmcp.benchmark;

/**
 * Stable logical work counts emitted by one analysis phase.
 */
public record BenchmarkWorkCounts(long units, int indexPackages, int indexTypes, int indexFields, int indexMethods, int indexParameters, int callgraphClasses, int callgraphMethods, long callgraphEdges) {
    public BenchmarkWorkCounts {
        if (units < 0 || indexPackages < 0 || indexTypes < 0 || indexFields < 0 || indexMethods < 0 || indexParameters < 0 || callgraphClasses < 0 || callgraphMethods < 0 || callgraphEdges < 0) {
            throw new IllegalArgumentException("Benchmark work counts must not be negative");
        }
    }
}

package dev.mcdevmcp.benchmark;

import java.util.Objects;

/**
 * Raw measurements from one index/callgraph pair.
 */
public record BenchmarkMeasurement(long indexNanos, long callgraphNanos, double indexClassesPerSecond, double callEdgesPerSecond, long indexPeakRssBytes, long callgraphPeakRssBytes, long indexGcCollections, long indexGcTimeMillis, long callgraphGcCollections, long callgraphGcTimeMillis, BenchmarkWorkCounts indexCounts, BenchmarkWorkCounts callgraphCounts) {
    public BenchmarkMeasurement {
        Objects.requireNonNull(indexCounts, "indexCounts");
        Objects.requireNonNull(callgraphCounts, "callgraphCounts");
    }

    public static BenchmarkMeasurement of(BenchmarkChildMeasurement index, BenchmarkChildMeasurement callgraph) {
        if (index.phase() != BenchmarkPhase.INDEX || callgraph.phase() != BenchmarkPhase.CALLGRAPH) {
            throw new IllegalArgumentException("Benchmark child measurements use the wrong phases");
        }
        return new BenchmarkMeasurement(index.elapsedNanos(), callgraph.elapsedNanos(), index.throughputPerSecond(), callgraph.throughputPerSecond(), index.peakRssBytes(), callgraph.peakRssBytes(), index.gcCollections(), index.gcTimeMillis(), callgraph.gcCollections(), callgraph.gcTimeMillis(), index.counts(), callgraph.counts());
    }
}

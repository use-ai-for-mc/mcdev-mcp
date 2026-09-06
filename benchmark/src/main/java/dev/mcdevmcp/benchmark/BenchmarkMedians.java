package dev.mcdevmcp.benchmark;

import java.util.List;
import java.util.Objects;

/**
 * Per-metric medians over the five raw measurement pairs.
 */
public record BenchmarkMedians(long indexNanos, long callgraphNanos, double indexClassesPerSecond, double callEdgesPerSecond, long indexPeakRssBytes, long callgraphPeakRssBytes, long indexGcCollections, long indexGcTimeMillis, long callgraphGcCollections, long callgraphGcTimeMillis, BenchmarkWorkCounts indexCounts, BenchmarkWorkCounts callgraphCounts) {
    private static final int REQUIRED_MEASUREMENTS = 5;

    public BenchmarkMedians {
        Objects.requireNonNull(indexCounts, "indexCounts");
        Objects.requireNonNull(callgraphCounts, "callgraphCounts");
    }

    public static BenchmarkMedians of(List<BenchmarkMeasurement> values) {
        if (values.size() != REQUIRED_MEASUREMENTS) {
            throw new IllegalArgumentException("Expected exactly " + REQUIRED_MEASUREMENTS + " benchmark measurements");
        }
        List<BenchmarkMeasurement> measurements = List.copyOf(values);
        return new BenchmarkMedians(medianLong(measurements.stream().map(BenchmarkMeasurement::indexNanos).toList()), medianLong(measurements.stream().map(BenchmarkMeasurement::callgraphNanos).toList()), medianDouble(measurements.stream().map(BenchmarkMeasurement::indexClassesPerSecond).toList()), medianDouble(measurements.stream().map(BenchmarkMeasurement::callEdgesPerSecond).toList()), medianLong(measurements.stream().map(BenchmarkMeasurement::indexPeakRssBytes).toList()), medianLong(measurements.stream().map(BenchmarkMeasurement::callgraphPeakRssBytes).toList()), medianLong(measurements.stream().map(BenchmarkMeasurement::indexGcCollections).toList()), medianLong(measurements.stream().map(BenchmarkMeasurement::indexGcTimeMillis).toList()), medianLong(measurements.stream().map(BenchmarkMeasurement::callgraphGcCollections).toList()), medianLong(measurements.stream().map(BenchmarkMeasurement::callgraphGcTimeMillis).toList()), requireIdenticalCounts(measurements.stream().map(BenchmarkMeasurement::indexCounts).toList(), "index"), requireIdenticalCounts(measurements.stream().map(BenchmarkMeasurement::callgraphCounts).toList(), "callgraph"));
    }

    private static BenchmarkWorkCounts requireIdenticalCounts(List<BenchmarkWorkCounts> counts, String phase) {
        BenchmarkWorkCounts expected = counts.getFirst();
        if (counts.stream().anyMatch(value -> !expected.equals(value))) {
            throw new IllegalArgumentException("Benchmark " + phase + " work counts changed between measured runs");
        }
        return expected;
    }

    private static double medianDouble(List<Double> values) {
        return values.stream().sorted().toList().get(REQUIRED_MEASUREMENTS / 2);
    }

    private static long medianLong(List<Long> values) {
        return values.stream().sorted().toList().get(REQUIRED_MEASUREMENTS / 2);
    }
}

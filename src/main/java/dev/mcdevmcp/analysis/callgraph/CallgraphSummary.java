package dev.mcdevmcp.analysis.callgraph;

import java.time.Duration;
import java.util.Objects;

public record CallgraphSummary(int classes, int methods, long edges, Duration elapsed) {
    public CallgraphSummary {
        if (classes < 0 || methods < 0 || edges < 0) {
            throw new IllegalArgumentException("Callgraph counts must not be negative");
        }
        Objects.requireNonNull(elapsed, "elapsed");
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("elapsed must not be negative");
        }
    }
}
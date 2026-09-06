package dev.mcdevmcp.analysis.index;

import java.time.Duration;
import java.util.Objects;

public record IndexSummary(int packages, int types, int fields, int methods, int parameters, Duration elapsed, IndexBuildEvidence evidence) {
    public IndexSummary(int packages, int types, int fields, int methods, int parameters, Duration elapsed) {
        this(packages, types, fields, methods, parameters, elapsed, new IndexBuildEvidence(java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of()));
    }

    public IndexSummary {
        if (packages < 0 || types < 0 || fields < 0 || methods < 0 || parameters < 0) {
            throw new IllegalArgumentException("Index counts must not be negative");
        }
        Objects.requireNonNull(elapsed, "elapsed");
        Objects.requireNonNull(evidence, "evidence");
    }
}
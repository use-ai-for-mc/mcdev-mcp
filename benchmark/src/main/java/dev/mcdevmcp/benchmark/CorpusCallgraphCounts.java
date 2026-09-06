package dev.mcdevmcp.benchmark;

/**
 * Typed logical counts from the immutable callgraph bundle.
 */
public record CorpusCallgraphCounts(long classes, long methods, long edges) {
    public CorpusCallgraphCounts {
        if (classes < 0 || methods < 0 || edges < 0) {
            throw new IllegalArgumentException("Callgraph counts must not be negative");
        }
    }
}

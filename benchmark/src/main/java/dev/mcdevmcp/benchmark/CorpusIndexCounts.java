package dev.mcdevmcp.benchmark;

/**
 * Typed logical row counts from the symbol index.
 */
public record CorpusIndexCounts(long packages, long types, long fields, long methods, long parameters) {
    public CorpusIndexCounts {
        if (packages < 0 || types < 0 || fields < 0 || methods < 0 || parameters < 0) {
            throw new IllegalArgumentException("Index counts must not be negative");
        }
    }
}

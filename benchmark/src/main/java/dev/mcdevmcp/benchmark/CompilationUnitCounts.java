package dev.mcdevmcp.benchmark;

/**
 * Complete Javac compilation-unit accounting for a corpus build.
 */
public record CompilationUnitCounts(long discovered, long parsed, long typed, long typeFree) {
    public CompilationUnitCounts {
        if (discovered < 0 || parsed < 0 || typed < 0 || typeFree < 0) {
            throw new IllegalArgumentException("Compilation-unit counts must not be negative");
        }
    }
}

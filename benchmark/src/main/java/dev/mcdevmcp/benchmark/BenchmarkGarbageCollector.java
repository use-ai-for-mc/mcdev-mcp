package dev.mcdevmcp.benchmark;

/**
 * Explicit collector profile used by all measurement child JVMs.
 */
public enum BenchmarkGarbageCollector {
    G1("-XX:+UseG1GC"), PARALLEL("-XX:+UseParallelGC");

    private final String jvmFlag;

    BenchmarkGarbageCollector(String jvmFlag) {
        this.jvmFlag = jvmFlag;
    }

    public String jvmFlag() {
        return jvmFlag;
    }
}

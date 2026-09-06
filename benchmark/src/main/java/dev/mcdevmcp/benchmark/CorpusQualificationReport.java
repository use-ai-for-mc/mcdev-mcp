package dev.mcdevmcp.benchmark;

import dev.mcdevmcp.storage.model.MinecraftVersion;

import java.util.List;
import java.util.Objects;

/**
 * Complete machine-readable result emitted before qualification mismatch failure.
 */
public record CorpusQualificationReport(int schemaVersion, boolean qualified, List<String> failures, MinecraftVersion minecraftVersion, int workers, String sourceLogicalHash, String remappedJarSha256, String symbolLogicalHash, String callgraphLogicalIdentity, String callgraphLogicalHash, CompilationUnitCounts compilationUnits, List<String> discoveredCompilationUnits, List<String> parsedCompilationUnits, List<String> typedCompilationUnits, List<String> typeFreeCompilationUnits, List<String> diagnostics, CorpusIndexCounts indexCounts, CorpusCallgraphCounts callgraphCounts, List<CorpusProbe> probes, List<ReviewedNodeDifference> appliedNodeDifferences, long peakLiveHeapBytes, long postGcLiveHeapBytes, long peakRssBytes, CorpusClasspathEvidence classpath, String osName, ProcessMemoryMetric memoryMetric) {
    public CorpusQualificationReport {
        if (schemaVersion != 2) {
            throw new IllegalArgumentException("Unsupported corpus qualification report schema " + schemaVersion);
        }
        Objects.requireNonNull(classpath, "classpath");
        failures = copy(failures, "failures");
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        Objects.requireNonNull(osName, "osName");
        Objects.requireNonNull(memoryMetric, "memoryMetric");
        if (workers < 1 || peakLiveHeapBytes < 0 || postGcLiveHeapBytes < 0 || peakRssBytes < 0) {
            throw new IllegalArgumentException("Invalid corpus qualification report metric");
        }
        Objects.requireNonNull(sourceLogicalHash, "sourceLogicalHash");
        Objects.requireNonNull(remappedJarSha256, "remappedJarSha256");
        Objects.requireNonNull(symbolLogicalHash, "symbolLogicalHash");
        Objects.requireNonNull(callgraphLogicalIdentity, "callgraphLogicalIdentity");
        Objects.requireNonNull(callgraphLogicalHash, "callgraphLogicalHash");
        Objects.requireNonNull(compilationUnits, "compilationUnits");
        discoveredCompilationUnits = copy(discoveredCompilationUnits, "discoveredCompilationUnits");
        parsedCompilationUnits = copy(parsedCompilationUnits, "parsedCompilationUnits");
        typedCompilationUnits = copy(typedCompilationUnits, "typedCompilationUnits");
        typeFreeCompilationUnits = copy(typeFreeCompilationUnits, "typeFreeCompilationUnits");
        diagnostics = copy(diagnostics, "diagnostics");
        Objects.requireNonNull(indexCounts, "indexCounts");
        Objects.requireNonNull(callgraphCounts, "callgraphCounts");
        probes = copy(probes, "probes");
        appliedNodeDifferences = copy(appliedNodeDifferences, "appliedNodeDifferences");
    }

    private static <T> List<T> copy(List<T> values, String name) {
        return List.copyOf(Objects.requireNonNull(values, name));
    }
}
package dev.mcdevmcp.benchmark;

import dev.mcdevmcp.storage.model.MinecraftVersion;

import java.util.List;
import java.util.Objects;

/**
 * Reviewed, immutable qualification contract for one complete Minecraft corpus.
 */
public record CorpusExpectation(int schemaVersion, MinecraftVersion minecraftVersion, String sourceLogicalHash, String remappedJarSha256, String nodeCallgraphSha256, NodeCallgraphIdentity nodeCallgraphIdentity, CompilationUnitCounts compilationUnits, CorpusIndexCounts indexCounts, CorpusCallgraphCounts callgraphCounts, String symbolLogicalHash, String callgraphLogicalIdentity, String callgraphLogicalHash, NodeOracleIdentity nodeOracleIdentity, List<String> diagnostics, List<CorpusProbe> probes, List<ReviewedNodeDifference> reviewedNodeDifferences, String classpathIdentity, String classpathManifestSha256) {
    public CorpusExpectation {
        if (schemaVersion != 2) {
            throw new IllegalArgumentException("Unsupported corpus expectation schema " + schemaVersion);
        }
        classpathIdentity = requireSha256(classpathIdentity, "classpathIdentity");
        classpathManifestSha256 = requireSha256(classpathManifestSha256, "classpathManifestSha256");
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        sourceLogicalHash = requireSha256(sourceLogicalHash, "sourceLogicalHash");
        remappedJarSha256 = requireSha256(remappedJarSha256, "remappedJarSha256");
        nodeCallgraphSha256 = requireSha256(nodeCallgraphSha256, "nodeCallgraphSha256");
        Objects.requireNonNull(nodeCallgraphIdentity, "nodeCallgraphIdentity");
        Objects.requireNonNull(compilationUnits, "compilationUnits");
        Objects.requireNonNull(indexCounts, "indexCounts");
        Objects.requireNonNull(callgraphCounts, "callgraphCounts");
        symbolLogicalHash = requireSha256(symbolLogicalHash, "symbolLogicalHash");
        callgraphLogicalIdentity = requireSha256(callgraphLogicalIdentity, "callgraphLogicalIdentity");
        callgraphLogicalHash = requireSha256(callgraphLogicalHash, "callgraphLogicalHash");
        Objects.requireNonNull(nodeOracleIdentity, "nodeOracleIdentity");
        diagnostics = stable(diagnostics, "diagnostics");
        probes = stable(probes, "probes");
        reviewedNodeDifferences = stable(reviewedNodeDifferences, "reviewedNodeDifferences");
    }

    static String requireSha256(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256");
        }
        return value;
    }

    private static <T> List<T> stable(List<T> values, String name) {
        return List.copyOf(Objects.requireNonNull(values, name));
    }
}
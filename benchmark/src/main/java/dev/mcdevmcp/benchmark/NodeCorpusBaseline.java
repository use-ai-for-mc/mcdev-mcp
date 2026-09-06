package dev.mcdevmcp.benchmark;

import dev.mcdevmcp.storage.model.MinecraftVersion;

import java.util.List;
import java.util.Objects;

/**
 * Frozen Node-oracle provenance, counts, and representative output signatures.
 */
public record NodeCorpusBaseline(int schemaVersion, MinecraftVersion minecraftVersion, String sourceLogicalHash, String remappedJarSha256, String nodeCallgraphSha256, NodeCallgraphIdentity nodeCallgraphIdentity, NodeOracleIdentity oracleIdentity, CorpusIndexCounts indexCounts, CorpusCallgraphCounts callgraphCounts, List<CorpusProbe> probes) {
    public NodeCorpusBaseline {
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        sourceLogicalHash = CorpusExpectation.requireSha256(sourceLogicalHash, "sourceLogicalHash");
        remappedJarSha256 = CorpusExpectation.requireSha256(remappedJarSha256, "remappedJarSha256");
        nodeCallgraphSha256 = CorpusExpectation.requireSha256(nodeCallgraphSha256, "nodeCallgraphSha256");
        Objects.requireNonNull(nodeCallgraphIdentity, "nodeCallgraphIdentity");
        Objects.requireNonNull(oracleIdentity, "oracleIdentity");
        Objects.requireNonNull(indexCounts, "indexCounts");
        Objects.requireNonNull(callgraphCounts, "callgraphCounts");
        probes = List.copyOf(Objects.requireNonNull(probes, "probes"));
    }
}

package dev.mcdevmcp.benchmark;

import java.util.List;
import java.util.Objects;

public record CorpusClasspathEvidence(CorpusClasspathKind kind, String identity, String manifestSha256, String metadataSha256, List<CorpusClasspathArtifact> artifacts) {
    public CorpusClasspathEvidence {
        Objects.requireNonNull(kind, "kind");
        identity = CorpusExpectation.requireSha256(identity, "classpath identity");
        manifestSha256 = CorpusExpectation.requireSha256(manifestSha256, "classpath manifest SHA-256");
        metadataSha256 = CorpusExpectation.requireSha256(metadataSha256, "metadata SHA-256");
        artifacts = List.copyOf(artifacts);
    }
}

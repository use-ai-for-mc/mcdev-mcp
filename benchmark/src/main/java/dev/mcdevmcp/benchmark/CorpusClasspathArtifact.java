package dev.mcdevmcp.benchmark;

public record CorpusClasspathArtifact(String relativePath, long size, String sha256) {
    public CorpusClasspathArtifact {
        CorpusClasspathManifest.portablePath(relativePath);
        if (size < 0) throw new IllegalArgumentException("Negative artifact size");
        sha256 = CorpusExpectation.requireSha256(sha256, "artifact SHA-256");
    }
}

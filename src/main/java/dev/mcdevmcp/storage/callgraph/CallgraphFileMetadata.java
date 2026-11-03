package dev.mcdevmcp.storage.callgraph;

import java.util.Objects;

public value record CallgraphFileMetadata(CallgraphArtifact artifact, long byteLength, long recordCount, String sha256) {
    public CallgraphFileMetadata {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(sha256, "sha256");
        if (byteLength < 0 || recordCount < 0) {
            throw new IllegalArgumentException("Invalid callgraph file metadata");
        }
    }
}
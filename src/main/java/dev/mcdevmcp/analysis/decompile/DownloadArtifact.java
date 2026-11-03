package dev.mcdevmcp.analysis.decompile;

import java.net.URI;
import java.util.Objects;

public value record DownloadArtifact(URI uri, String sha1, long byteLength, ArtifactKind kind) {
    public DownloadArtifact {
        Objects.requireNonNull(uri, "uri");
        sha1 = Objects.requireNonNull(sha1, "sha1");
        if (!sha1.matches("[0-9a-fA-F]{40}")) {
            throw new IllegalArgumentException("sha1 must be a 40-character hexadecimal value");
        }
        if (byteLength < 0) {
            throw new IllegalArgumentException("byteLength must not be negative");
        }
        Objects.requireNonNull(kind, "kind");
    }
}
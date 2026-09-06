package dev.mcdevmcp.analysis.decompile;

import java.net.URI;
import java.util.Objects;

public record DownloadWire(URI url, String sha1, long size) {
    @SuppressWarnings("unused")
    public DownloadWire {
        Objects.requireNonNull(url, "url");
        sha1 = Objects.requireNonNull(sha1, "sha1");
    }

    public DownloadArtifact toArtifact(ArtifactKind kind) {
        return new DownloadArtifact(url, sha1, size, kind);
    }
}
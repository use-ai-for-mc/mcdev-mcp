package dev.mcdevmcp.analysis.decompile;

import java.util.Objects;

/**
 * An unobfuscated client JAR resolved from a distinct official manifest entry.
 */
public record OfficialUnobfuscatedClient(VersionEntry manifestEntry, DownloadArtifact artifact) {
    public OfficialUnobfuscatedClient {
        Objects.requireNonNull(manifestEntry, "manifestEntry");
        Objects.requireNonNull(artifact, "artifact");
        if (!manifestEntry.id().endsWith("_unobfuscated")) {
            throw new IllegalArgumentException("manifestEntry must identify an unobfuscated version");
        }
        if (artifact.kind() != ArtifactKind.JAR) {
            throw new IllegalArgumentException("artifact must be a JAR");
        }
    }
}
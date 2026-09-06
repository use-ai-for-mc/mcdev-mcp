package dev.mcdevmcp.benchmark;

import java.net.URI;
import java.util.Objects;

public record CorpusClasspathMetadata(String globalManifestPath, String globalManifestSha256, String versionManifestPath, URI versionManifestUrl, String versionManifestSha1) {
    public CorpusClasspathMetadata {
        CorpusClasspathManifest.portablePath(globalManifestPath);
        CorpusClasspathManifest.portablePath(versionManifestPath);
        globalManifestSha256 = CorpusExpectation.requireSha256(globalManifestSha256, "global manifest SHA-256");
        Objects.requireNonNull(versionManifestUrl, "versionManifestUrl");
        if (!"https".equals(versionManifestUrl.getScheme()) || versionManifestUrl.getHost() == null || versionManifestUrl.getUserInfo() != null || versionManifestUrl.getFragment() != null) {
            throw new IllegalArgumentException("Metadata URL must use absolute HTTPS");
        }
        CorpusClasspathManifest.requireSha1(versionManifestSha1);
    }
}
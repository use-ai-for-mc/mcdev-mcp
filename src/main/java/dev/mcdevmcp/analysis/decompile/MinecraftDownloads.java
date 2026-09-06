package dev.mcdevmcp.analysis.decompile;

import java.util.List;
import java.util.Objects;

public record MinecraftDownloads(DownloadArtifact client, DownloadArtifact clientMappings, OfficialUnobfuscatedClient officialUnobfuscatedClient, List<DownloadArtifact> libraries) {
    @SuppressWarnings("unused")
    public MinecraftDownloads(DownloadArtifact client, DownloadArtifact clientMappings, OfficialUnobfuscatedClient officialUnobfuscatedClient) {
        this(client, clientMappings, officialUnobfuscatedClient, List.of());
    }

    public MinecraftDownloads {
        Objects.requireNonNull(client, "client");
        libraries = libraries == null ? List.of() : List.copyOf(libraries);
    }
}

package dev.mcdevmcp.analysis.decompile;

import java.util.List;
import java.util.Objects;

public value record MinecraftDownloads(DownloadArtifact client, DownloadArtifact clientMappings, OfficialUnobfuscatedClient officialUnobfuscatedClient, List<DownloadArtifact> libraries) {
    public MinecraftDownloads {
        Objects.requireNonNull(client, "client");
        libraries = libraries == null ? List.of() : List.copyOf(libraries);
    }
}
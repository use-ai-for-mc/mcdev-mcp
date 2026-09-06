package dev.mcdevmcp.storage.callgraph;

import java.util.List;
import java.util.Objects;

public record CallgraphManifest(String format, int schemaVersion, String minecraftVersion, String remappedJarSha256, int classCount, int methodCount, long edgeCount, List<CallgraphFileMetadata> files) {
    public CallgraphManifest {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        Objects.requireNonNull(remappedJarSha256, "remappedJarSha256");
        files = List.copyOf(Objects.requireNonNull(files, "files"));
        if (classCount < 0 || methodCount < 0 || edgeCount < 0) {
            throw new IllegalArgumentException("Callgraph manifest counts must not be negative");
        }
    }
}
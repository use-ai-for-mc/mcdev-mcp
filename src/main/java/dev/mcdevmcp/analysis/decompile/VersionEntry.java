package dev.mcdevmcp.analysis.decompile;

import java.net.URI;
import java.util.Objects;

public record VersionEntry(String id, URI url) {
    @SuppressWarnings("unused")
    public VersionEntry {
        id = Objects.requireNonNull(id, "id");
        Objects.requireNonNull(url, "url");
    }
}
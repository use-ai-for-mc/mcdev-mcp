package dev.mcdevmcp.analysis.decompile;

import java.net.URI;
import java.util.Objects;

public value record VersionEntry(String id, URI url) {
    public VersionEntry {
        id = Objects.requireNonNull(id, "id");
        Objects.requireNonNull(url, "url");
    }
}
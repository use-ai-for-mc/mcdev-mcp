package dev.mcdevmcp.analysis.decompile;

import java.util.List;
import java.util.Objects;

public value record GlobalManifest(List<VersionEntry> versions) {
    public GlobalManifest {
        versions = List.copyOf(Objects.requireNonNull(versions, "versions"));
    }
}
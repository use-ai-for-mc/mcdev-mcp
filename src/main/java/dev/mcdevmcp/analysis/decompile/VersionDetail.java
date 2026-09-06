package dev.mcdevmcp.analysis.decompile;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record VersionDetail(Map<String, DownloadWire> downloads, List<LibraryEntry> libraries) {
    @SuppressWarnings("unused")
    public VersionDetail(Map<String, DownloadWire> downloads) {
        this(downloads, List.of());
    }

    public VersionDetail {
        downloads = Map.copyOf(Objects.requireNonNull(downloads, "downloads"));
        libraries = libraries == null ? List.of() : List.copyOf(libraries);
    }
}

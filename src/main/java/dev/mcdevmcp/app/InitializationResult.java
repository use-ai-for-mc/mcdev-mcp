package dev.mcdevmcp.app;

import dev.mcdevmcp.analysis.index.IndexSummary;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public value record InitializationResult(PreparedSources sources, IndexSummary index, String sourceIdentity, Optional<Path> retainedMigration) {
    public InitializationResult {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(sourceIdentity, "sourceIdentity");
        Objects.requireNonNull(retainedMigration, "retainedMigration");
    }
}

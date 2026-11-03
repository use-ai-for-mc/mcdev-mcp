package dev.mcdevmcp.storage.migration;

import dev.mcdevmcp.storage.model.MinecraftVersion;

import java.util.Objects;
import java.util.UUID;

public value record SourcePublicationJournal(int schemaVersion, MinecraftVersion minecraftVersion, String cacheRoot, UUID transactionId, SourcePublicationPhase phase, SourcePublicationMode mode, SourceIndexSnapshot before, SourceIndexSnapshot candidate) {
    public SourcePublicationJournal {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported migration journal schema");
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        Objects.requireNonNull(cacheRoot, "cacheRoot");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(candidate, "candidate");
    }

    SourcePublicationJournal withPhase(SourcePublicationPhase next) {
        return new SourcePublicationJournal(schemaVersion, minecraftVersion, cacheRoot, transactionId, next, mode, before, candidate);
    }
}
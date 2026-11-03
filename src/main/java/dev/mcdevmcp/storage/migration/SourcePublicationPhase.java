package dev.mcdevmcp.storage.migration;

public enum SourcePublicationPhase {
    PREPARED, BACKING_UP, BACKED_UP, INSTALLING_SOURCE, INSTALLING_DATABASE, INSTALLING_STAMP, VALIDATING_PAIR, COMMITTED
}
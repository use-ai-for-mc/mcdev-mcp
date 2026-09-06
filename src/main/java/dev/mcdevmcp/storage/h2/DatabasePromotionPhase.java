package dev.mcdevmcp.storage.h2;

enum DatabasePromotionPhase {
    BACKING_UP_TARGET, PROMOTING_TEMPORARY, RESTORING_BACKUP
}

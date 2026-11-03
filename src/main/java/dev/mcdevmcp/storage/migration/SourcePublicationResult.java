package dev.mcdevmcp.storage.migration;

import java.nio.file.Path;

public value record SourcePublicationResult(Path retainedMigration, SourceTreeInventory sourceInventory) {
}

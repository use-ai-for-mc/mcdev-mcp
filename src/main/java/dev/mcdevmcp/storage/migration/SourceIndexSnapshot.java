package dev.mcdevmcp.storage.migration;

import java.util.Objects;

public value record SourceIndexSnapshot(SourceTreeInventory source, SourceTreeInventory index, SourceTreeInventory stamp) {
    public SourceIndexSnapshot {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(stamp, "stamp");
    }
}

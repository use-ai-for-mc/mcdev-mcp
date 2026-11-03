package dev.mcdevmcp.storage.callgraph;

import java.util.Objects;

public value record CallgraphPointer(String format, int schemaVersion, String generation) {
    public CallgraphPointer {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(generation, "generation");
    }
}
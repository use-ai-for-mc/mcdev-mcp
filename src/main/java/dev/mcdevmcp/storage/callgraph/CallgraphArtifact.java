package dev.mcdevmcp.storage.callgraph;

import dev.mcdevmcp.storage.bundle.BundleFiles;

import java.io.IOException;
import java.nio.file.Path;

public enum CallgraphArtifact {
    CALLERS_DATA("callers.jsonl"), CALLERS_INDEX("callers.index.jsonl"), CALLEES_DATA("callees.jsonl"), CALLEES_INDEX("callees.index.jsonl");

    private final String fileName;

    CallgraphArtifact(String fileName) {
        this.fileName = fileName;
    }

    String fileName() {
        return fileName;
    }

    Path resolve(Path generation) throws IOException {
        return BundleFiles.safeChild(generation, fileName);
    }
}

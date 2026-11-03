package dev.mcdevmcp.storage.symbols;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

value class SymbolIndexArtifacts {
    final Path target;
    final Path temporary;
    final Path backup;

    SymbolIndexArtifacts(Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        this.target = normalized;
        temporary = normalized.resolveSibling(normalized.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        backup = normalized.resolveSibling(normalized.getFileName() + ".bak");
    }

    void createTargetDirectory() throws IOException {
        Files.createDirectories(target.getParent());
    }

    void deleteTemporary() throws IOException {
        Files.deleteIfExists(temporary);
    }
}

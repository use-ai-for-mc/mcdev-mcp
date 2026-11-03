package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.storage.migration.CachePathBoundary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class PathWalker {
    private PathWalker() {
    }

    static boolean isDecompiled(Path source, CachePathBoundary boundary) throws IOException {
        try (var files = Files.walk(boundary.require(source))) {
            int count = 0;
            var iterator = files.iterator();
            while (iterator.hasNext()) {
                Path path = boundary.require(iterator.next());
                if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java") && ++count > 100) {
                    return true;
                }
            }
        }
        return false;
    }
}

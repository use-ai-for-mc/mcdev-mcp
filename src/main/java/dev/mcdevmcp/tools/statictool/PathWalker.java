package dev.mcdevmcp.tools.statictool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

final class PathWalker {
    private PathWalker() {
    }

    static void listDirectories(Path root, List<String> result) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var files = Files.list(root)) {
            files.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).forEach(path -> result.add(path.getFileName().toString()));
        } catch (IOException ignored) {
        }
    }

    static boolean isDecompiled(Path source) {
        try (var files = Files.walk(source)) {
            return files.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java")).limit(101).count() > 100;
        } catch (IOException ignored) {
            return false;
        }
    }
}

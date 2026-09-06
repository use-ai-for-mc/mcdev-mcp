package dev.mcdevmcp.storage.h2;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;

@FunctionalInterface
interface DatabaseFileOperations {
    void move(Path source, Path target, CopyOption... options) throws IOException;

    default void delete(Path path) throws IOException {
        Files.delete(path);
    }

    default boolean deleteIfExists(Path path) throws IOException {
        return Files.deleteIfExists(path);
    }

    default FileChannel open(Path path, OpenOption... options) throws IOException {
        return FileChannel.open(path, options);
    }
}

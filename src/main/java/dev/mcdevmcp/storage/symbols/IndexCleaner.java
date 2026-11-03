package dev.mcdevmcp.storage.symbols;

import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import reactor.util.annotation.NonNull;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;

public final class IndexCleaner {
    private static final List<String> SUPERSEDED_DATABASE_FILES = List.of("symbols.mv.db", "symbols.mv.db.bak", "symbols.mv.db.lock", "symbols.trace.db");
    private final PlatformPaths paths;
    private final IndexFileOperations files;

    public IndexCleaner(PlatformPaths paths) {
        this(paths, Files::move);
    }

    IndexCleaner(PlatformPaths paths, IndexFileOperations files) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.files = Objects.requireNonNull(files, "files");
    }

    private static void rejectSymbolicLink(Path path, String description) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Refusing to clean a symbolic link " + description + ": " + path);
        }
    }

    private void deleteContained(Path root, Path candidate) throws IOException {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IOException("Refusing to delete path outside index root: " + candidate);
        }
        files.delete(normalized);
    }

    private void deleteArtifacts(Path root, Path database, Path lockPath) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            @NonNull
            public FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attributes) throws IOException {
                Path normalized = file.toAbsolutePath().normalize();
                if (!normalized.equals(lockPath) && !normalized.equals(database)) {
                    deleteContained(root, normalized);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            @NonNull
            public FileVisitResult postVisitDirectory(@NonNull Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                if (!directory.equals(root)) {
                    deleteContained(root, directory);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private Path containedIndexRoot(MinecraftVersion version) throws IOException {
        Path root = paths.indexRoot(version).toAbsolutePath().normalize();
        if (!root.startsWith(paths.cacheRoot().toAbsolutePath().normalize())) {
            throw new IOException("Refusing to clean index outside cache root: " + root);
        }
        return root;
    }

    /**
     * Deletes the database that earlier 3.0.0 development builds wrote beside the index. That
     * format was never released, so nothing reads it once the index has been rebuilt.
     */
    public void deleteSupersededDatabase(MinecraftVersion version) throws IOException {
        Path root = containedIndexRoot(version);
        rejectSymbolicLink(root, "version index root");
        for (String name : SUPERSEDED_DATABASE_FILES) {
            Path file = root.resolve(name);
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                deleteContained(root, file);
            }
        }
    }

    public void cleanIndex(MinecraftVersion version) throws IOException {
        Path root = containedIndexRoot(version);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path database = paths.symbolIndex(version).toAbsolutePath().normalize();
        if (!database.startsWith(root)) {
            throw new IOException("Refusing to clean symbol database outside index root: " + database);
        }
        rejectSymbolicLink(root, "version index root");
        rejectSymbolicLink(database, "symbol database");
        Path lockPath = database.resolveSibling(database.getFileName() + ".lock");
        try (var databaseLock = IndexLock.write(database, AtomicSymbolIndex.WRITE_LOCK_TIMEOUT)) {
            if (!databaseLock.isHeld()) {
                throw new IOException("Failed to acquire exclusive database lock for index cleanup");
            }
            deleteArtifacts(root, database, lockPath);
            if (Files.exists(database, LinkOption.NOFOLLOW_LINKS)) {
                deleteContained(root, database);
            }
        }
    }
}

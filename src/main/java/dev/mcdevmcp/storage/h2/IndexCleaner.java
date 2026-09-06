package dev.mcdevmcp.storage.h2;

import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.model.MinecraftVersion;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

public final class IndexCleaner {
    private final PlatformPaths paths;
    private final DatabaseFileOperations files;

    public IndexCleaner(PlatformPaths paths) {
        this(paths, Files::move);
    }

    IndexCleaner(PlatformPaths paths, DatabaseFileOperations files) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.files = Objects.requireNonNull(files, "files");
    }

    private static void rejectH2Locks(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            Path lock = paths.filter(path -> path.getFileName().toString().endsWith(".lock.db")).findFirst().orElse(null);
            if (lock != null) {
                throw new IOException("Refusing to clean while an H2 lock companion exists: " + lock);
            }
        }
    }

    private static FileLock acquireDatabaseFileLock(FileChannel channel, Path database) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new IOException("Unable to acquire exclusive H2 database file lock for index cleanup: " + database);
            }
            return lock;
        } catch (OverlappingFileLockException exception) {
            throw new IOException("Unable to acquire exclusive H2 database file lock for index cleanup: " + database, exception);
        }
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

    private DatabaseFileHandle openDatabaseFile(Path database) throws IOException {
        try {
            return new DatabaseFileHandle(files.open(database, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), true);
        } catch (FileAlreadyExistsException exception) {
            rejectSymbolicLink(database, "symbol database");
            return new DatabaseFileHandle(files.open(database, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), false);
        }
    }

    private void deleteArtifacts(Path root, Path database, Path lockPath) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            @SuppressWarnings("NullableProblems")
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Path normalized = file.toAbsolutePath().normalize();
                if (normalized.getFileName().toString().endsWith(".lock.db")) {
                    throw new IOException("Refusing to clean while an H2 lock companion exists: " + normalized);
                }
                if (!normalized.equals(lockPath) && !normalized.equals(database)) {
                    deleteContained(root, normalized);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            @SuppressWarnings("NullableProblems")
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
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

    public void cleanIndex(MinecraftVersion version) throws IOException {
        Path root = paths.indexRoot(version).toAbsolutePath().normalize();
        if (!root.startsWith(paths.cacheRoot().toAbsolutePath().normalize())) {
            throw new IOException("Refusing to clean index outside cache root: " + root);
        }
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path database = paths.symbolDatabase(version).toAbsolutePath().normalize();
        if (!database.startsWith(root)) {
            throw new IOException("Refusing to clean symbol database outside index root: " + database);
        }
        rejectSymbolicLink(root, "version index root");
        rejectSymbolicLink(database, "symbol database");
        Path lockPath = database.resolveSibling(database.getFileName() + ".lock");
        try (var databaseLock = DatabaseLock.write(database, AtomicH2Database.WRITE_LOCK_TIMEOUT)) {
            if (!databaseLock.isHeld()) {
                throw new IOException("Failed to acquire exclusive database lock for index cleanup");
            }
            rejectH2Locks(root);
            try (DatabaseFileHandle databaseFile = openDatabaseFile(database);
                 FileLock databaseGuard = acquireDatabaseFileLock(databaseFile.channel(), database)) {
                if (!databaseGuard.isValid()) {
                    throw new IOException("Exclusive H2 database file lock became invalid before index cleanup: " + database);
                }
                boolean databaseDeletionAttempted = false;
                try {
                    deleteArtifacts(root, database, lockPath);
                    rejectH2Locks(root);
                    databaseDeletionAttempted = true;
                    deleteContained(root, database);
                } catch (IOException exception) {
                    if (databaseFile.reservationCreated() && !databaseDeletionAttempted) {
                        try {
                            files.deleteIfExists(database);
                        } catch (IOException cleanupFailure) {
                            exception.addSuppressed(cleanupFailure);
                        }
                    }
                    throw exception;
                }
            }
        }
    }
}
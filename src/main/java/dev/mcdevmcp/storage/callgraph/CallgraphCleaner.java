package dev.mcdevmcp.storage.callgraph;

import dev.mcdevmcp.storage.bundle.BundleLock;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Objects;

/**
 * Removes one callgraph bundle while holding its publication write lock.
 */
public final class CallgraphCleaner {
    private static final Duration LOCK_TIMEOUT = Duration.ofSeconds(30);
    private final Duration lockTimeout;

    public CallgraphCleaner() {
        this(LOCK_TIMEOUT);
    }

    CallgraphCleaner(Duration lockTimeout) {
        this.lockTimeout = Objects.requireNonNull(lockTimeout, "lockTimeout");
        if (lockTimeout.isNegative()) {
            throw new IllegalArgumentException("lockTimeout must not be negative");
        }
    }

    private static void deleteContained(Path root) throws IOException {
        rejectUnsafePaths(root);
        Path publicationLock = root.resolve("publication.lock");
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            @SuppressWarnings("NullableProblems")
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                rejectLink(root, directory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            @SuppressWarnings("NullableProblems")
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                rejectLink(root, file);
                if (file.toAbsolutePath().normalize().equals(publicationLock)) {
                    return FileVisitResult.CONTINUE;
                }
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            @SuppressWarnings("NullableProblems")
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                if (!directory.equals(root)) {
                    Files.delete(directory);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void rejectUnsafePaths(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            @SuppressWarnings("NullableProblems")
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                rejectLink(root, directory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            @SuppressWarnings("NullableProblems")
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                rejectLink(root, file);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void rejectLink(Path root, Path candidate) throws IOException {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || Files.isSymbolicLink(candidate)) {
            throw new IOException("Refusing unsafe callgraph cleanup path: " + candidate);
        }
    }

    public void clean(Path bundle) throws IOException {
        Path root = Objects.requireNonNull(bundle, "bundle").toAbsolutePath().normalize();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(root)) {
            throw new IOException("Refusing to clean symbolic-link callgraph bundle: " + root);
        }
        Path publicationLock = root.resolve("publication.lock");
        if (Files.isSymbolicLink(publicationLock)) {
            throw new IOException("Refusing to lock symbolic-link callgraph publication file: " + publicationLock);
        }
        try (BundleLock lock = BundleLock.write(root, lockTimeout)) {
            if (!lock.isHeld()) {
                throw new IOException("Unable to acquire callgraph bundle write lock: " + root);
            }
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Callgraph bundle is not a contained directory: " + root);
            }
            deleteContained(root);
        }
    }
}
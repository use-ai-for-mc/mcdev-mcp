package dev.mcdevmcp.storage;

import dev.mcdevmcp.storage.callgraph.CallgraphCleaner;
import dev.mcdevmcp.storage.h2.IndexCleaner;
import dev.mcdevmcp.storage.model.MinecraftVersion;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Cleans contained per-version cache state without following symbolic links.
 */
public final class CacheCleaner {
    private final PlatformPaths paths;
    private final IndexCleaner indexCleaner;
    private final CallgraphCleaner callgraphCleaner;

    public CacheCleaner(PlatformPaths paths) {
        this(paths, new IndexCleaner(paths), new CallgraphCleaner());
    }

    CacheCleaner(PlatformPaths paths, IndexCleaner indexCleaner, CallgraphCleaner callgraphCleaner) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.indexCleaner = Objects.requireNonNull(indexCleaner, "indexCleaner");
        this.callgraphCleaner = Objects.requireNonNull(callgraphCleaner, "callgraphCleaner");
    }

    private static void rejectLinkedDirectoryPath(Path root, Path candidate, String description) throws IOException {
        Path current = root;
        requireDirectoryOrMissing(current, description);
        for (Path component : root.relativize(candidate)) {
            current = current.resolve(component);
            requireDirectoryOrMissing(current, description);
        }
    }

    private static void requireDirectoryOrMissing(Path path, String description) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing unsafe " + description + " path: " + path);
        }
    }

    private static void collectVersions(Path root, java.util.Map<String, MinecraftVersion> versions, boolean indexRoot) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var children = Files.list(root)) {
            for (Path child : children.toList()) {
                if (Files.isSymbolicLink(child) || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (containsOnlyPersistentLocks(child, indexRoot)) {
                    continue;
                }
                String name = child.getFileName().toString();
                try {
                    versions.putIfAbsent(name, new MinecraftVersion(name));
                } catch (IllegalArgumentException ignored) {
                    // Foreign or malformed directories are not safe version selectors.
                }
            }
        }
    }

    private static boolean containsOnlyPersistentLocks(Path versionRoot, boolean indexRoot) throws IOException {
        boolean foundScaffold = false;
        try (var descendants = Files.walk(versionRoot)) {
            for (Path candidate : descendants.skip(1).toList()) {
                Path relative = versionRoot.relativize(candidate);
                if (isPersistentLockScaffold(relative, candidate, indexRoot)) {
                    foundScaffold = true;
                    continue;
                }
                return false;
            }
        }
        return foundScaffold;
    }

    private static boolean isPersistentLockScaffold(Path relative, Path candidate, boolean indexRoot) {
        if (Files.isSymbolicLink(candidate)) {
            return false;
        }
        if (indexRoot) {
            return Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) && relative.equals(Path.of("symbols.mv.db.lock"));
        }
        if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return relative.equals(Path.of("indexes")) || relative.equals(Path.of("indexes", "callgraph"));
        }
        return Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) && relative.equals(Path.of("indexes", "callgraph", "publication.lock"));
    }

    private static void deleteCacheArtifacts(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(root)) {
            throw new IOException("Refusing to clean symbolic-link cache root: " + root);
        }
        List<Path> artifacts = new ArrayList<>();
        try (var children = Files.list(root)) {
            for (Path child : children.toList()) {
                if (!child.getFileName().toString().equals("indexes")) {
                    artifacts.add(child);
                }
            }
        }
        artifacts.sort(Comparator.comparing(Path::toString));
        for (Path artifact : artifacts) {
            preflightTree(artifact);
        }
        for (Path artifact : artifacts) {
            deleteTree(artifact);
        }
    }

    private static void removeIfEmpty(Path root) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            return;
        }
        try (var children = Files.list(root)) {
            if (children.findAny().isEmpty()) {
                Files.delete(root);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(root)) {
            throw new IOException("Refusing to clean symbolic-link cache root: " + root);
        }
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
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            @SuppressWarnings("NullableProblems")
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void preflightTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(root) || (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Refusing unsafe cache cleanup root: " + root);
        }
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

    private static void rejectLink(Path root, Path path) throws IOException {
        if (!path.toAbsolutePath().normalize().startsWith(root) || Files.isSymbolicLink(path)) {
            throw new IOException("Refusing unsafe cache cleanup path: " + path);
        }
    }

    public void clean(MinecraftVersion version) throws IOException {
        cleanAll(version);
        removeIfEmpty(ownedDirectory(paths.versionCache(version), "version cache"));
    }

    public void cleanAll(MinecraftVersion version) throws IOException {
        Objects.requireNonNull(version, "version");
        Path root = ownedDirectory(paths.versionCache(version), "version cache");
        Path indexRoot = ownedDirectory(paths.indexRoot(version), "version index");
        Path callgraph = ownedDirectory(paths.callgraphBundle(version), "callgraph bundle");
        preflightTree(indexRoot);
        preflightTree(root);
        indexCleaner.cleanIndex(version);
        callgraphCleaner.clean(callgraph);
        cleanCache(version);
    }

    public void cleanCache(MinecraftVersion version) throws IOException {
        Objects.requireNonNull(version, "version");
        Path root = ownedDirectory(paths.versionCache(version), "version cache");
        deleteCacheArtifacts(root);
    }

    /**
     * Lists portable per-version directory names without following links. Semantic support policy remains a caller concern.
     */
    public List<MinecraftVersion> cachedVersions() throws IOException {
        Map<String, MinecraftVersion> versions = new TreeMap<>();
        collectVersions(ownedDirectory(paths.cacheRoot().resolve("cache"), "cache versions"), versions, false);
        collectVersions(ownedDirectory(paths.cacheRoot().resolve("index"), "index versions"), versions, true);
        return List.copyOf(versions.values());
    }

    private Path ownedDirectory(Path candidate, String description) throws IOException {
        Path root = paths.cacheRoot().toAbsolutePath().normalize();
        Path normalized = Objects.requireNonNull(candidate, "candidate").toAbsolutePath().normalize();
        if (normalized.equals(root) || !normalized.startsWith(root)) {
            throw new IOException("Refusing to access " + description + " outside configured cache root: " + normalized);
        }
        rejectLinkedDirectoryPath(root, normalized, description);
        return normalized;
    }
}
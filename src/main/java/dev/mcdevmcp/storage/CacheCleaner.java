package dev.mcdevmcp.storage;

import dev.mcdevmcp.storage.callgraph.CallgraphCleaner;
import dev.mcdevmcp.storage.symbols.IndexCleaner;
import dev.mcdevmcp.storage.migration.CachePathBoundary;
import dev.mcdevmcp.storage.migration.VersionOperationLease;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import reactor.util.annotation.NonNull;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Cleans contained per-version cache state without following symbolic links.
 */
public value class CacheCleaner {
    private final PlatformPaths paths;
    private final CachePathBoundary boundary;

    public CacheCleaner(PlatformPaths paths) {
        this.paths = Objects.requireNonNull(paths, "paths");
        boundary = null;
    }

    public CacheCleaner(CachePathBoundary boundary) {
        this.boundary = Objects.requireNonNull(boundary, "boundary");
        paths = boundary.resolvedPaths();
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

    private static boolean isPersistentLockScaffold(Path relative, Path candidate, boolean indexRoot) {
        if (Files.isSymbolicLink(candidate)) {
            return false;
        }
        if (indexRoot) {
            return Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) && relative.equals(Path.of("symbols.jsonl.lock"));
        }
        if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return relative.equals(Path.of("indexes")) || relative.equals(Path.of("indexes", "callgraph"));
        }
        return Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) && relative.equals(Path.of("indexes", "callgraph", "publication.lock"));
    }

    private void collectVersions(Path root, Map<String, MinecraftVersion> versions, boolean indexRoot) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var children = Files.list(root)) {
            for (Path child : children.toList()) {
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (NoSuchFileException _) {
                    continue;
                }
                if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isDirectory()) {
                    continue;
                }
                String name = child.getFileName().toString();
                try {
                    MinecraftVersion version = new MinecraftVersion(name);
                    boundary.require(child);
                    try (var lease = VersionOperationLease.read(boundary, version)) {
                        lease.require(paths, version);
                        if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) && !containsOnlyPersistentLocks(child, indexRoot)) {
                            versions.putIfAbsent(name, version);
                        }
                    }
                } catch (IllegalArgumentException _) {
                    // Foreign or malformed directories are not safe version selectors.
                }
            }
        }
    }

    private boolean containsOnlyPersistentLocks(Path versionRoot, boolean indexRoot) throws IOException {
        boolean foundScaffold = false;
        try (var descendants = Files.walk(versionRoot)) {
            for (Path candidate : descendants.skip(1).toList()) {
                // Other entries are content, unsafe ones too: cleaning that version rejects them.
                if (!isPersistentLockScaffold(versionRoot.relativize(candidate), candidate, indexRoot)) {
                    return false;
                }
                boundary.require(candidate);
                foundScaffold = true;
            }
        }
        return foundScaffold;
    }

    private void deleteCacheArtifacts(Path root) throws IOException {
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

    private void removeIfEmpty(Path root) throws IOException {
        boundary.require(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            return;
        }
        try (var children = Files.list(root)) {
            if (children.findAny().isEmpty()) {
                Files.delete(root);
            }
        }
    }

    private void deleteTree(Path root) throws IOException {
        boundary.require(root);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(root)) {
            throw new IOException("Refusing to clean symbolic-link cache root: " + root);
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            @NonNull
            public FileVisitResult preVisitDirectory(@NonNull Path directory, @NonNull BasicFileAttributes attributes) throws IOException {
                rejectLink(root, directory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            @NonNull
            public FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attributes) throws IOException {
                rejectLink(root, file);
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            @NonNull
            public FileVisitResult postVisitDirectory(@NonNull Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                boundary.require(directory);
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void preflightTree(Path root) throws IOException {
        boundary.require(root);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(root) || (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Refusing unsafe cache cleanup root: " + root);
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            @NonNull
            public FileVisitResult preVisitDirectory(@NonNull Path directory, @NonNull BasicFileAttributes attributes) throws IOException {
                rejectLink(root, directory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            @NonNull
            public FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attributes) throws IOException {
                rejectLink(root, file);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void rejectLink(Path root, Path path) throws IOException {
        boundary.require(path);
        if (!path.toAbsolutePath().normalize().startsWith(root) || Files.isSymbolicLink(path)) {
            throw new IOException("Refusing unsafe cache cleanup path: " + path);
        }
    }

    public void clean(MinecraftVersion version) throws IOException {
        try (var lease = VersionOperationLease.write(operationBoundary(), version)) {
            lease.requireNoPending(paths, version);
            CacheCleaner pinned = new CacheCleaner(lease.boundary());
            pinned.cleanAllHeld(version);
            pinned.removeIfEmpty(pinned.ownedDirectory(lease.resolvedPaths().versionCache(version), "version cache"));
        }
    }

    public void cleanAll(MinecraftVersion version) throws IOException {
        try (var lease = VersionOperationLease.write(operationBoundary(), version)) {
            lease.requireNoPending(paths, version);
            new CacheCleaner(lease.boundary()).cleanAllHeld(version);
        }
    }

    private void cleanAllHeld(MinecraftVersion version) throws IOException {
        Path root = ownedDirectory(paths.versionCache(version), "version cache");
        Path indexRoot = ownedDirectory(paths.indexRoot(version), "version index");
        Path callgraph = ownedDirectory(paths.callgraphBundle(version), "callgraph bundle");
        preflightTree(indexRoot);
        preflightTree(root);
        boundary.revalidate();
        new IndexCleaner(paths).cleanIndex(version);
        boundary.revalidate();
        new CallgraphCleaner().clean(callgraph);
        deleteCacheArtifacts(root);
    }

    public void cleanCache(MinecraftVersion version) throws IOException {
        try (var lease = VersionOperationLease.write(operationBoundary(), version)) {
            lease.requireNoPending(paths, version);
            CacheCleaner pinned = new CacheCleaner(lease.boundary());
            Path root = pinned.ownedDirectory(lease.resolvedPaths().versionCache(version), "version cache");
            pinned.deleteCacheArtifacts(root);
        }
    }

    /**
     * Lists portable per-version directory names without following links. Semantic support policy remains a caller concern.
     */
    public List<MinecraftVersion> cachedVersions() throws IOException {
        return new CacheCleaner(operationBoundary()).cachedVersionsHeld();
    }

    private CachePathBoundary operationBoundary() throws IOException {
        if (boundary != null) {
            boundary.revalidate();
            return boundary;
        }
        return CachePathBoundary.open(paths);
    }

    private List<MinecraftVersion> cachedVersionsHeld() throws IOException {
        Map<String, MinecraftVersion> versions = new TreeMap<>();
        collectVersions(ownedDirectory(paths.cacheRoot().resolve("cache"), "cache versions"), versions, false);
        collectVersions(ownedDirectory(paths.cacheRoot().resolve("index"), "index versions"), versions, true);
        Path migrations = ownedDirectory(paths.cacheRoot().resolve("migrations"), "migration versions");
        if (Files.isDirectory(migrations, LinkOption.NOFOLLOW_LINKS)) {
            try (var children = Files.list(migrations)) {
                for (Path child : children.toList()) {
                    boundary.require(child);
                    boundary.require(child.resolve("pending.json"));
                    if (Files.exists(child.resolve("pending.json"), LinkOption.NOFOLLOW_LINKS)) {
                        String name = child.getFileName().toString();
                        try {
                            versions.putIfAbsent(name, new MinecraftVersion(name));
                        } catch (IllegalArgumentException _) {
                            // Foreign directories are not version selectors.
                        }
                    }
                }
            }
        }
        return List.copyOf(versions.values());
    }

    private Path ownedDirectory(Path candidate, String description) throws IOException {
        Path root = paths.cacheRoot().toAbsolutePath().normalize();
        Path normalized = Objects.requireNonNull(candidate, "candidate").toAbsolutePath().normalize();
        if (normalized.equals(root) || !normalized.startsWith(root)) {
            throw new IOException("Refusing to access " + description + " outside configured cache root: " + normalized);
        }
        rejectLinkedDirectoryPath(root, normalized, description);
        return boundary.require(normalized);
    }
}
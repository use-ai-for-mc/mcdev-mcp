package dev.mcdevmcp.app;

import dev.mcdevmcp.storage.CacheCleaner;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.callgraph.CallgraphCleaner;
import dev.mcdevmcp.storage.symbols.IndexCleaner;
import dev.mcdevmcp.storage.migration.CachePathBoundary;
import dev.mcdevmcp.storage.migration.VersionOperationLease;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import reactor.util.annotation.NonNull;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

@Command(name = "clean", description = "Clean cached analysis artifacts")
public final class CleanCommand implements Callable<Integer> {
    private final PlatformPaths paths;
    private final Consumer<MinecraftVersion> afterCacheCleanup;
    private CachePathBoundary boundary;

    @Option(names = {"-v", "--version"}, description = "Minecraft version")
    private String version;

    @Option(names = "--index", description = "Clean the symbol index")
    private boolean index;

    @Option(names = "--cache", description = "Clean the version cache")
    private boolean cache;

    @Option(names = "--callgraph", description = "Clean the JSONL callgraph")
    private boolean callgraph;

    @Option(names = "--all", description = "Clean all supported cached state")
    private boolean all;

    @Spec
    private picocli.CommandLine.Model.CommandSpec spec;

    public CleanCommand(PlatformPaths paths) {
        this(paths, _ -> {
        });
    }

    CleanCommand(PlatformPaths paths, Consumer<MinecraftVersion> afterCacheCleanup) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.afterCacheCleanup = Objects.requireNonNull(afterCacheCleanup, "afterCacheCleanup");
    }

    private static void attemptCleanup(List<Exception> failures, Cleanup cleanup) {
        try {
            cleanup.run();
        } catch (IOException | RuntimeException failure) {
            failures.add(failure);
        }
    }

    @Override
    public Integer call() throws IOException {
        boundary = CachePathBoundary.open(paths);
        if (callgraph) {
            return cleanCallgraph();
        }
        if (version != null) {
            return cleanVersion();
        }
        if (all) {
            cache = true;
            index = true;
        }
        if (!cache && !index) {
            printGuidance();
            return 0;
        }
        return cleanGlobal();
    }

    private int cleanCallgraph() {
        if (version == null) {
            throw new IllegalArgumentException("--callgraph requires -v <version>");
        }
        MinecraftVersion minecraft = new MinecraftVersion(version);
        Path bundle = boundary.resolvedPaths().callgraphBundle(minecraft);
        return tryRemove(bundle, "callgraph data for " + version, () -> {
            try (var lease = VersionOperationLease.write(boundary, minecraft)) {
                lease.requireNoPending(paths, minecraft);
                boundary.require(bundle);
                new CallgraphCleaner().clean(bundle);
            }
        }) ? 0 : 1;
    }

    private int cleanVersion() throws IOException {
        MinecraftVersion minecraft = new MinecraftVersion(version);
        try (var lease = VersionOperationLease.write(boundary, minecraft)) {
            lease.requireNoPending(paths, minecraft);
            if (all || (!cache && !index)) {
                cache = true;
                index = true;
            }

            boolean succeeded = true;
            if (cache) {
                Path versionCache = boundary.resolvedPaths().versionCache(minecraft);
                succeeded &= tryRemove(versionCache, "cache for " + version, () -> cleanVersionCache(minecraft, lease));
            }
            if (index) {
                Path versionIndex = boundary.resolvedPaths().indexRoot(minecraft);
                succeeded &= tryRemove(versionIndex, "index for " + version, () -> cleanVersionIndex(minecraft, lease));
            }
            spec.commandLine().getOut().printf("%nRun 'mcdev-mcp init -v %s' to reinitialize.%n", version);
            return succeeded ? 0 : 1;
        }
    }

    private int cleanGlobal() throws IOException {
        Path cacheRoot = boundary.resolvedPaths().cacheRoot().resolve("cache");
        Path indexRoot = boundary.resolvedPaths().cacheRoot().resolve("index");
        Path temporaryRoot = boundary.resolvedPaths().cacheRoot().resolve("tmp");
        List<MinecraftVersion> versions = new CacheCleaner(boundary).cachedVersions();
        List<Exception> cacheFailures = new ArrayList<>();
        List<Exception> indexFailures = new ArrayList<>();
        boolean succeeded = true;

        for (MinecraftVersion minecraft : versions) {
            try (var lease = VersionOperationLease.write(boundary, minecraft)) {
                lease.requireNoPending(paths, minecraft);
                if (cache) {
                    attemptCleanup(cacheFailures, () -> cleanVersionCache(minecraft, lease));
                }
                if (index) {
                    attemptCleanup(indexFailures, () -> cleanVersionIndex(minecraft, lease));
                }
            } catch (IOException | RuntimeException failure) {
                if (cache) cacheFailures.add(failure);
                if (index) indexFailures.add(failure);
            }
        }
        if (cache) {
            succeeded &= tryRemove(cacheRoot, "cache", () -> finishCleanup(cacheRoot, cacheFailures));
        }
        if (index) {
            succeeded &= tryRemove(indexRoot, "index", () -> finishCleanup(indexRoot, indexFailures));
        }
        if (all) {
            succeeded &= tryRemove(temporaryRoot, "tmp", () -> deleteContainedTree(temporaryRoot));
        }
        spec.commandLine().getOut().println();
        spec.commandLine().getOut().println("Run `mcdev-mcp init -v <version>` to reinitialize.");
        return succeeded ? 0 : 1;
    }

    private void cleanVersionCache(MinecraftVersion minecraft, VersionOperationLease lease) throws IOException {
        lease.requireNoPending(paths, minecraft);
        Path versionCache = boundary.resolvedPaths().versionCache(minecraft);
        preflightContainedTree(versionCache);
        new CallgraphCleaner().clean(boundary.resolvedPaths().callgraphBundle(minecraft));
        deleteContainedTree(versionCache);
        afterCacheCleanup.accept(minecraft);
    }

    private void finishCleanup(Path root, List<Exception> failures) throws IOException {
        if (!failures.isEmpty()) {
            IOException failure = new IOException(failures.getFirst().getMessage(), failures.getFirst());
            failures.stream().skip(1).forEach(failure::addSuppressed);
            throw failure;
        }
        removeEmptyRoot(root);
    }

    private void cleanVersionIndex(MinecraftVersion version, VersionOperationLease lease) throws IOException {
        lease.requireNoPending(paths, version);
        preflightContainedTree(boundary.resolvedPaths().indexRoot(version));
        new IndexCleaner(boundary.resolvedPaths()).cleanIndex(version);
    }

    private void removeEmptyRoot(Path root) throws IOException {
        preflightContainedTree(root);
        if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            try (var children = Files.list(root)) {
                if (children.findAny().isEmpty()) {
                    Files.delete(root);
                }
            }
        }
    }

    private boolean tryRemove(Path path, String label, Cleanup cleanup) {
        Path target = path.toAbsolutePath().normalize();
        try {
            boundary.require(target);
            boolean existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            cleanup.run();
            report(existed, label, target);
            return true;
        } catch (IOException | RuntimeException exception) {
            String message = exception.getMessage();
            if (message == null || message.isBlank()) {
                message = exception.getClass().getSimpleName();
            }
            spec.commandLine().getErr().printf("Error removing %s at %s: %s%n", label, target, message);
            spec.commandLine().getErr().println("  (Hint: another process may have files open, or the path may be on read-only media.)");
            return false;
        }
    }

    private void report(boolean existed, String label, Path path) {
        if (existed) {
            spec.commandLine().getOut().printf("Removed %s: %s%n", label, path.toAbsolutePath().normalize());
        }
        else {
            spec.commandLine().getOut().printf("%s not found: %s%n", label, path.toAbsolutePath().normalize());
        }
    }

    private void printGuidance() {
        spec.commandLine().getOut().println("Specify what to clean:");
        spec.commandLine().getOut().println("  --cache           Clean decompiled sources");
        spec.commandLine().getOut().println("  --index           Clean symbol index");
        spec.commandLine().getOut().println("  --callgraph       Clean callgraph database only (requires -v)");
        spec.commandLine().getOut().println("  --all             Clean everything (cache, index, tmp)");
        spec.commandLine().getOut().println("  -v <version>      Clean data for specific version only");
    }

    private void deleteContainedTree(Path candidate) throws IOException {
        boundary.require(candidate);
        Path root = boundary.resolvedPaths().cacheRoot().toAbsolutePath().normalize();
        Path target = candidate.toAbsolutePath().normalize();
        if (target.equals(root) || !target.startsWith(root)) {
            throw new IOException("Refusing to clean path outside configured cache root: " + target);
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        preflightContainedTree(target);
        Files.walkFileTree(target, new ContainedTreeVisitor(boundary, true));
    }

    private void preflightContainedTree(Path candidate) throws IOException {
        boundary.require(candidate);
        Path root = boundary.resolvedPaths().cacheRoot().toAbsolutePath().normalize();
        Path target = candidate.toAbsolutePath().normalize();
        if (target.equals(root) || !target.startsWith(root)) {
            throw new IOException("Refusing to clean path outside configured cache root: " + target);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(target, new ContainedTreeVisitor(boundary, false));
        }
    }

    @FunctionalInterface
    private interface Cleanup {
        void run() throws IOException;
    }

    private static final class ContainedTreeVisitor extends SimpleFileVisitor<Path> {
        private final CachePathBoundary boundary;
        private final boolean delete;

        private ContainedTreeVisitor(CachePathBoundary boundary, boolean delete) {
            this.boundary = boundary;
            this.delete = delete;
        }

        @Override
        @NonNull
        public FileVisitResult preVisitDirectory(@NonNull Path directory, @NonNull BasicFileAttributes attributes) throws IOException {
            rejectUnsafe(directory);
            return FileVisitResult.CONTINUE;
        }

        @Override
        @NonNull
        public FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attributes) throws IOException {
            rejectUnsafe(file);
            if (delete) {
                Files.delete(file);
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        @NonNull
        public FileVisitResult postVisitDirectory(@NonNull Path directory, IOException failure) throws IOException {
            if (failure != null) {
                throw failure;
            }
            if (delete) {
                boundary.require(directory);
                Files.delete(directory);
            }
            return FileVisitResult.CONTINUE;
        }

        private void rejectUnsafe(Path candidate) throws IOException {
            boundary.require(candidate);
        }
    }
}
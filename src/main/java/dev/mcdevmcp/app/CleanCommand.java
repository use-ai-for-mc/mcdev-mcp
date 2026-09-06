package dev.mcdevmcp.app;

import dev.mcdevmcp.storage.CacheCleaner;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.callgraph.CallgraphCleaner;
import dev.mcdevmcp.storage.h2.IndexCleaner;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

@Command(name = "clean", description = "Clean cached analysis artifacts")
@SuppressWarnings("unused")
public final class CleanCommand implements Callable<Integer> {
    private final PlatformPaths paths;

    @Option(names = {"-v", "--version"}, description = "Minecraft version")
    private String version;

    @Option(names = "--index", description = "Clean the H2 index")
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
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    @Override
    public Integer call() throws IOException {
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
        Path bundle = paths.callgraphBundle(minecraft);
        return tryRemove(bundle, "callgraph data for " + version, () -> new CallgraphCleaner().clean(bundle)) ? 0 : 1;
    }

    private int cleanVersion() {
        MinecraftVersion minecraft = new MinecraftVersion(version);
        if (all || (!cache && !index)) {
            cache = true;
            index = true;
        }

        boolean succeeded = true;
        if (cache) {
            Path versionCache = paths.versionCache(minecraft);
            succeeded &= tryRemove(versionCache, "cache for " + version, () -> cleanVersionCache(minecraft));
        }
        if (index) {
            Path versionIndex = paths.indexRoot(minecraft);
            succeeded &= tryRemove(versionIndex, "index for " + version, () -> new IndexCleaner(paths).cleanIndex(minecraft));
        }
        spec.commandLine().getOut().printf("%nRun 'mcdev-mcp init -v %s' to reinitialize.%n", version);
        return succeeded ? 0 : 1;
    }

    private int cleanGlobal() throws IOException {
        Path cacheRoot = paths.cacheRoot().resolve("cache");
        Path indexRoot = paths.cacheRoot().resolve("index");
        Path temporaryRoot = paths.cacheRoot().resolve("tmp");
        List<MinecraftVersion> versions = new CacheCleaner(paths).cachedVersions();
        boolean succeeded = true;

        if (cache) {
            succeeded &= tryRemove(cacheRoot, "cache", () -> cleanCacheRoot(cacheRoot, versions));
        }
        if (index) {
            succeeded &= tryRemove(indexRoot, "index", () -> cleanIndexRoot(indexRoot, versions));
        }
        if (all) {
            succeeded &= tryRemove(temporaryRoot, "tmp", () -> deleteContainedTree(temporaryRoot));
        }
        spec.commandLine().getOut().println();
        spec.commandLine().getOut().println("Run `mcdev-mcp init -v <version>` to reinitialize.");
        return succeeded ? 0 : 1;
    }

    private void cleanVersionCache(MinecraftVersion minecraft) throws IOException {
        Path versionCache = paths.versionCache(minecraft);
        preflightContainedTree(versionCache);
        new CallgraphCleaner().clean(paths.callgraphBundle(minecraft));
        deleteContainedTree(versionCache);
    }

    private void cleanCacheRoot(Path cacheRoot, List<MinecraftVersion> versions) throws IOException {
        preflightContainedTree(cacheRoot);
        for (MinecraftVersion minecraft : versions) {
            new CallgraphCleaner().clean(paths.callgraphBundle(minecraft));
        }
        deleteContainedTree(cacheRoot);
    }

    private void cleanIndexRoot(Path indexRoot, List<MinecraftVersion> versions) throws IOException {
        preflightContainedTree(indexRoot);
        IndexCleaner cleaner = new IndexCleaner(paths);
        for (MinecraftVersion minecraft : versions) {
            cleaner.cleanIndex(minecraft);
        }
        deleteContainedTree(indexRoot);
    }

    private boolean tryRemove(Path path, String label, Cleanup cleanup) {
        Path target = path.toAbsolutePath().normalize();
        boolean existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        try {
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
        Path root = paths.cacheRoot().toAbsolutePath().normalize();
        Path target = candidate.toAbsolutePath().normalize();
        if (target.equals(root) || !target.startsWith(root)) {
            throw new IOException("Refusing to clean path outside configured cache root: " + target);
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        preflightContainedTree(target);
        Files.walkFileTree(target, new ContainedTreeVisitor(root, true));
    }

    private void preflightContainedTree(Path candidate) throws IOException {
        Path root = paths.cacheRoot().toAbsolutePath().normalize();
        Path target = candidate.toAbsolutePath().normalize();
        if (target.equals(root) || !target.startsWith(root)) {
            throw new IOException("Refusing to clean path outside configured cache root: " + target);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.walkFileTree(target, new ContainedTreeVisitor(root, false));
        }
    }

    @FunctionalInterface
    private interface Cleanup {
        void run() throws IOException;
    }

    private static final class ContainedTreeVisitor extends SimpleFileVisitor<Path> {
        private final Path root;
        private final boolean delete;

        private ContainedTreeVisitor(Path root, boolean delete) {
            this.root = root;
            this.delete = delete;
        }

        @Override
        @SuppressWarnings("NullableProblems")
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
            rejectUnsafe(directory);
            return FileVisitResult.CONTINUE;
        }

        @Override
        @SuppressWarnings("NullableProblems")
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            rejectUnsafe(file);
            if (delete) {
                Files.delete(file);
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        @SuppressWarnings("NullableProblems")
        public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
            if (failure != null) {
                throw failure;
            }
            if (delete) {
                Files.delete(directory);
            }
            return FileVisitResult.CONTINUE;
        }

        private void rejectUnsafe(Path candidate) throws IOException {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (!normalized.startsWith(root) || Files.isSymbolicLink(candidate)) {
                throw new IOException("Refusing unsafe cleanup path: " + candidate);
            }
        }
    }
}

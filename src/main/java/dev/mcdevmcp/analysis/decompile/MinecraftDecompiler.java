package dev.mcdevmcp.analysis.decompile;

import dev.mcdevmcp.support.Cancellation;
import dev.mcdevmcp.support.ProgressSink;
import org.jetbrains.java.decompiler.main.Fernflower;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Invokes Vineflower directly and atomically publishes a nonempty Java source tree.
 */
public final class MinecraftDecompiler {
    private static final LinkOption[] NO_FOLLOW_LINKS = {LinkOption.NOFOLLOW_LINKS};

    static Path resolveSourcePath(Path staging, String outputPath, String entryName) throws IOException {
        Path root = staging.toAbsolutePath().normalize();
        Path directory = relativeOutputPath(outputPath, "directory", true);
        Path entry = relativeOutputPath(entryName, "entry", false);
        Path file = root.resolve(directory).resolve(entry).normalize();
        if (!file.startsWith(root) || file.equals(root) || !file.getFileName().toString().endsWith(".java")) {
            throw new IOException("Unsafe decompiler output path: " + entryName);
        }
        return file;
    }

    static void createOutputDirectory(Path staging, String outputPath) throws IOException {
        Path root = staging.toAbsolutePath().normalize();
        Path directory = root.resolve(relativeOutputPath(outputPath, "directory", true)).normalize();
        if (!directory.startsWith(root)) {
            throw new IOException("Unsafe decompiler output directory: " + outputPath);
        }
        ensureDirectoryWithinStaging(root, directory);
    }

    private static IResultSaver saver(Path staging, AtomicInteger written, Cancellation cancellation) {
        return new IResultSaver() {
            @Override
            public void saveFolder(String outputPath) {
                try {
                    checkCancelled(cancellation);
                    createOutputDirectory(staging, outputPath);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }

            @Override
            public void copyFile(String source, String path, String entryName) {
            }

            @Override
            public void saveClassFile(String outputPath, String qualifiedName, String entryName, String content, int[] mapping) {
                write(outputPath, entryName, content);
            }

            @Override
            public void createArchive(String path, String archiveName, Manifest manifest) {
            }

            @Override
            public void saveDirEntry(String path, String archiveName, String entryName) {
            }

            @Override
            public void copyEntry(String source, String path, String archiveName, String entry) {
            }

            @Override
            public void saveClassEntry(String outputPath, String archiveName, String qualifiedName, String entryName, String content) {
                write(outputPath, entryName, content);
            }

            @Override
            public void closeArchive(String path, String archiveName) {
            }

            private void write(String outputPath, String entryName, String content) {
                try {
                    checkCancelled(cancellation);
                    Path file = resolveSourcePath(staging, outputPath, entryName);
                    ensureDirectoryWithinStaging(staging, file.getParent());
                    Files.writeString(file, Objects.requireNonNull(content, "content"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    checkCancelled(cancellation);
                    written.incrementAndGet();
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }
        };
    }

    private static void verifyInputJar(Path input, Cancellation cancellation) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(input, BasicFileAttributes.class, NO_FOLLOW_LINKS);
        } catch (NoSuchFileException exception) {
            throw new IOException("Remapped Minecraft JAR does not exist: " + input, exception);
        }
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
            throw new IOException("Remapped Minecraft JAR must be a regular file, not a link: " + input);
        }

        boolean hasClass = false;
        try (JarFile jar = new JarFile(input.toFile(), true)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                checkCancelled(cancellation);
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                relativeOutputPath(entry.getName(), "archive entry", false);
                hasClass = true;
                try (var contents = jar.getInputStream(entry)) {
                    byte[] buffer = new byte[16 * 1024];
                    while (contents.read(buffer) >= 0) {
                        checkCancelled(cancellation);
                    }
                }
            }
        }
        if (!hasClass) {
            throw new IOException("Remapped Minecraft JAR contains no class files: " + input);
        }
    }

    private static void validateExistingTarget(Path target) throws IOException {
        if (!Files.exists(target, NO_FOLLOW_LINKS)) {
            return;
        }
        BasicFileAttributes attributes = Files.readAttributes(target, BasicFileAttributes.class, NO_FOLLOW_LINKS);
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            throw new IOException("Source target must be a directory, not a link: " + target);
        }
    }

    private static void publish(Path staging, Path target, Path backup, Cancellation cancellation) throws IOException {
        boolean movedExistingTarget = false;
        if (Files.exists(target, NO_FOLLOW_LINKS)) {
            Files.move(target, backup, StandardCopyOption.ATOMIC_MOVE);
            movedExistingTarget = true;
        }

        try {
            checkCancelled(cancellation);
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            BasicFileAttributes published = Files.readAttributes(target, BasicFileAttributes.class, NO_FOLLOW_LINKS);
            if (published.isSymbolicLink() || !published.isDirectory()) {
                throw new IOException("Published source target is not a directory: " + target);
            }
        } catch (IOException | RuntimeException exception) {
            if (movedExistingTarget && !Files.exists(target, NO_FOLLOW_LINKS) && Files.exists(backup, NO_FOLLOW_LINKS)) {
                try {
                    Files.move(backup, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException restoreFailure) {
                    exception.addSuppressed(restoreFailure);
                }
            }
            throw exception;
        }

        if (movedExistingTarget) {
            try {
                deleteTree(backup);
            } catch (IOException ignored) {
                // The newly published target is authoritative; a retained backup is safe to clean later.
            }
        }
    }

    private static Path relativeOutputPath(String value, String description, boolean allowEmpty) throws IOException {
        if (value == null || !allowEmpty && value.isEmpty()) {
            throw new IOException("Missing decompiler output " + description);
        }
        final Path relative;
        try {
            relative = Path.of(value);
        } catch (InvalidPathException exception) {
            throw new IOException("Invalid decompiler output " + description + ": " + value, exception);
        }
        if (relative.isAbsolute() || relative.getRoot() != null) {
            throw new IOException("Absolute decompiler output " + description + ": " + value);
        }
        for (Path component : relative) {
            if (component.toString().equals("..")) {
                throw new IOException("Parent traversal in decompiler output " + description + ": " + value);
            }
        }
        return relative.normalize();
    }

    /**
     * Ensures that the user/agent-supplied {@code parent} of the decompiler
     * output target exists as a real directory, creating any missing
     * components. Unlike the staging-internal check, this tolerates
     * legitimate system-level symlink prefixes (for example {@code /var ->
     * /private/var} on macOS) because the parent path is chosen by the
     * caller rather than derived from untrusted input.
     */
    private static void ensureOutputParentDirectory(Path parent) throws IOException {
        if (!Files.exists(parent, NO_FOLLOW_LINKS)) {
            Files.createDirectories(parent);
        }
        BasicFileAttributes attributes = Files.readAttributes(parent, BasicFileAttributes.class, NO_FOLLOW_LINKS);
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            throw new IOException("Output directory is not a real directory: " + parent);
        }
    }

    /**
     * Ensures that {@code directory} exists under the staging {@code root} as
     * a real directory, creating any missing components, while guaranteeing
     * that no path component escapes {@code root}. Decompiler output paths are
     * derived from untrusted JAR content (the bytes being decompiled), so a
     * component that is a symlink pointing outside staging is rejected before
     * any directory is created beneath it. The anchor is {@code root} rather
     * than the filesystem root, so system symlink prefixes such as
     * {@code /var} (which only appear on the staging root's own ancestry) are
     * never traversed here.
     */
    private static void ensureDirectoryWithinStaging(Path root, Path directory) throws IOException {
        if (!directory.startsWith(root)) {
            throw new IOException("Unsafe decompiler output directory outside staging root: " + directory);
        }
        Path resolvedRoot = root.toRealPath();
        Path current = root;
        for (Path component : root.relativize(directory)) {
            current = current.resolve(component);
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(current, BasicFileAttributes.class, NO_FOLLOW_LINKS);
            } catch (NoSuchFileException missing) {
                try {
                    Files.createDirectory(current);
                } catch (FileAlreadyExistsException race) {
                    // A concurrent decompiler thread created this component.
                }
                attributes = Files.readAttributes(current, BasicFileAttributes.class, NO_FOLLOW_LINKS);
            }
            if (attributes.isSymbolicLink()) {
                Path resolved = current.toRealPath();
                if (!resolved.startsWith(resolvedRoot)) {
                    throw new IOException("Refusing decompiler output path escaping staging root: " + current);
                }
            }
            else if (!attributes.isDirectory()) {
                throw new IOException("Refusing non-directory decompiler output path: " + current);
            }
        }
    }

    private static void cleanupAfterFailure(Path staging, IOException failure) {
        cleanupTree(staging, failure);
    }

    private static void cleanupTree(Path root, IOException failure) {
        try {
            deleteTree(root);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    @SuppressWarnings("NullableProblems")
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, NO_FOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private static void addDiagnostics(IOException failure, BoundedDecompilerLogger logger) {
        if (!logger.messages().isEmpty()) {
            failure.addSuppressed(new IOException("Vineflower diagnostics: " + String.join(" | ", logger.messages())));
        }
    }

    private static void checkCancelledUnchecked(Cancellation cancellation) {
        try {
            checkCancelled(cancellation);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void checkCancelled(Cancellation cancellation) throws IOException {
        try {
            cancellation.throwIfCancelled();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Decompilation cancelled", exception);
        }
    }

    public Path decompile(Path remappedJar, Path sourceRoot) throws IOException {
        return decompile(remappedJar, sourceRoot, (_, _, _) -> {
        }, Cancellation.none());
    }

    public Path decompile(Path remappedJar, Path sourceRoot, ProgressSink progress, Cancellation cancellation) throws IOException {
        Path input = Objects.requireNonNull(remappedJar, "remappedJar").toAbsolutePath().normalize();
        Path target = Objects.requireNonNull(sourceRoot, "sourceRoot").toAbsolutePath().normalize();
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(cancellation, "cancellation");
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("Refusing to use a filesystem root as the source target: " + target);
        }

        checkCancelled(cancellation);
        progress.report("decompile", 0, "Decompiling " + input.getFileName());
        verifyInputJar(input, cancellation);
        ensureOutputParentDirectory(parent);
        validateExistingTarget(target);

        Path staging = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        Path backup = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".bak");
        Files.createDirectory(staging);
        AtomicInteger written = new AtomicInteger();
        BoundedDecompilerLogger logger = new BoundedDecompilerLogger(() -> checkCancelledUnchecked(cancellation));
        IResultSaver saver = saver(staging, written, cancellation);

        try (saver) {
            Fernflower fernflower = new Fernflower(saver, Map.of(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES, "1", IFernflowerPreferences.BYTECODE_SOURCE_MAPPING, "1", IFernflowerPreferences.REMOVE_SYNTHETIC, "1", IFernflowerPreferences.THREADS, Integer.toString(Math.max(1, Runtime.getRuntime().availableProcessors())), IFernflowerPreferences.LOG_LEVEL, "ERROR"), logger);
            try {
                fernflower.addSource(input.toFile());
                fernflower.decompileContext();
            } finally {
                fernflower.clearContext();
            }
            if (written.get() == 0) {
                throw new IOException("Vineflower produced no Java sources for " + input);
            }
            checkCancelled(cancellation);
            publish(staging, target, backup, cancellation);
            progress.report("decompile", 100, "Published " + written.get() + " Java source files");
            return target;
        } catch (UncheckedIOException exception) {
            IOException failure = exception.getCause();
            addDiagnostics(failure, logger);
            cleanupAfterFailure(staging, failure);
            throw failure;
        } catch (IOException exception) {
            addDiagnostics(exception, logger);
            cleanupAfterFailure(staging, exception);
            throw exception;
        } catch (RuntimeException exception) {
            IOException failure = new IOException("Vineflower failed to decompile " + input, exception);
            addDiagnostics(failure, logger);
            cleanupAfterFailure(staging, failure);
            throw failure;
        }
    }
}

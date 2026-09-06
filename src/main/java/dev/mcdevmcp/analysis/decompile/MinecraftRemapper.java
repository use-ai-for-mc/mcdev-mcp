package dev.mcdevmcp.analysis.decompile;

import dev.mcdevmcp.support.Cancellation;
import dev.mcdevmcp.support.ProgressSink;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Runs Tiny Remapper in process and publishes only a verified remapped JAR.
 */
public final class MinecraftRemapper {
    private static final int INITIAL_CONCURRENT_PROGRESS_PERCENT = 29;
    private static final LocalDateTime REPRODUCIBLE_TIMESTAMP = LocalDateTime.of(1980, 1, 1, 0, 0);
    private final int threads;

    public MinecraftRemapper(int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be positive");
        }
        this.threads = threads;
    }

    private static void runRemapper(TinyRemapper remapper, Path input, Path outputJar, int expectedClasses, ProgressSink progress, Cancellation cancellation) throws IOException {
        try (OutputConsumerPath output = new OutputConsumerPath.Builder(outputJar).assumeArchive(true).threadSyncWrites(true).build()) {
            await(remapper.readInputsAsync(input), cancellation);
            checkCancelled(cancellation);
            progress.report("remap", 30, "Remapping " + expectedClasses + " classes");
            AtomicInteger mappedClasses = new AtomicInteger();
            ConcurrentProgress concurrentProgress = new ConcurrentProgress(progress);
            remapper.apply((className, bytecode) -> {
                checkCancelledUnchecked(cancellation);
                output.accept(className, bytecode);
                int mapped = mappedClasses.incrementAndGet();
                int percent = Math.min(74, 30 + mapped * 44 / expectedClasses);
                concurrentProgress.report(percent, "Remapped " + mapped + " of " + expectedClasses + " classes");
            });
            checkCancelled(cancellation);
            progress.report("remap", 75, "Preserving JAR resources");
            List<OutputConsumerPath.ResourceRemapper> resourceRemappers = new ArrayList<>(NonClassCopyMode.FIX_META_INF.remappers);
            resourceRemappers.add(new CancellableResourceCopier(cancellation));
            output.addNonClassFiles(input, remapper, resourceRemappers);
            checkCancelled(cancellation);
        }
    }

    private static void await(Future<?> future, Cancellation cancellation) throws IOException {
        while (true) {
            checkCancelled(cancellation);
            try {
                future.get(100, TimeUnit.MILLISECONDS);
                return;
            } catch (TimeoutException ignored) {
                // Polling keeps cancellation responsive while Tiny Remapper scans the archive.
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Remapping interrupted", exception);
            } catch (ExecutionException exception) {
                throw new IOException("Tiny Remapper could not read the input JAR", exception.getCause());
            }
        }
    }

    private static void verifyMappings(Path mapping, Cancellation cancellation) throws IOException {
        verifyRegularFile(mapping, "Tiny mappings");
        checkCancelled(cancellation);
        MemoryMappingTree mappings = new MemoryMappingTree();
        try (var reader = Files.newBufferedReader(mapping)) {
            MappingReader.read(reader, mappings);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid Tiny mappings: " + mapping, exception);
        }
        if (!"official".equals(mappings.getSrcNamespace()) || !mappings.getDstNamespaces().equals(List.of("named")) || mappings.getClasses().isEmpty() || mappings.getClasses().stream().anyMatch(classMapping -> classMapping.getDstName(0) == null)) {
            throw new IOException("Tiny mappings must contain official-to-named class mappings: " + mapping);
        }
        checkCancelled(cancellation);
    }

    private static int verifyJar(Path jarPath, boolean input, Cancellation cancellation, ProgressSink progress, int progressBase, int progressSpan) throws IOException {
        verifyRegularFile(jarPath, input ? "Input JAR" : "Remapped JAR");
        int classes = 0;
        int visited = 0;
        int lastPercent = -1;
        Set<String> names = new HashSet<>();
        try (JarFile jar = new JarFile(jarPath.toFile(), true)) {
            int total = Math.max(1, jar.size());
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                checkCancelled(cancellation);
                JarEntry entry = entries.nextElement();
                validateEntryName(entry.getName(), entry.isDirectory());
                if (!names.add(entry.getName())) {
                    throw new IOException("Duplicate JAR entry " + entry.getName() + " in " + jarPath);
                }
                if (!entry.isDirectory()) {
                    boolean classFile = entry.getName().endsWith(".class");
                    int magic = 0;
                    int magicBytes = 0;
                    try (InputStream contents = jar.getInputStream(entry)) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = contents.read(buffer)) != -1) {
                            checkCancelled(cancellation);
                            for (int index = 0; classFile && index < read && magicBytes < Integer.BYTES; index++) {
                                magic = magic << Byte.SIZE | Byte.toUnsignedInt(buffer[index]);
                                magicBytes++;
                            }
                        }
                    }
                    if (classFile) {
                        if (magicBytes != Integer.BYTES || magic != 0xCAFEBABE) {
                            throw new IOException("Invalid class entry " + entry.getName() + " in " + jarPath);
                        }
                        if (!input || !isModuleDescriptor(entry.getName())) {
                            classes++;
                        }
                    }
                }
                visited++;
                int percent = progressBase + visited * progressSpan / total;
                if (percent != lastPercent) {
                    lastPercent = percent;
                    progress.report("remap", percent, "Verified " + visited + " JAR entries");
                }
            }
        } catch (SecurityException exception) {
            throw new IOException("JAR signature verification failed for " + jarPath, exception);
        }
        if (classes == 0) {
            throw new IOException((input ? "Input" : "Remapped") + " JAR contains no remappable classes: " + jarPath);
        }
        return classes;
    }

    private static void writeDeterministicJar(Path source, Path destination, int expectedClasses, ProgressSink progress, Cancellation cancellation) throws IOException {
        Set<String> names = new HashSet<>();
        int classes = 0;
        try (JarFile input = new JarFile(source.toFile(), true);
             OutputStream file = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             JarOutputStream output = new JarOutputStream(new BufferedOutputStream(file))) {
            output.setLevel(9);
            List<JarEntry> entries = input.stream().sorted(Comparator.comparing(JarEntry::getName)).toList();
            int lastPercent = -1;
            for (int index = 0; index < entries.size(); index++) {
                checkCancelled(cancellation);
                JarEntry entry = entries.get(index);
                validateEntryName(entry.getName(), entry.isDirectory());
                if (!names.add(entry.getName())) {
                    throw new IOException("Duplicate remapper output entry: " + entry.getName());
                }
                JarEntry normalized = new JarEntry(entry.getName());
                normalized.setTimeLocal(REPRODUCIBLE_TIMESTAMP);
                output.putNextEntry(normalized);
                if (!entry.isDirectory()) {
                    try (InputStream contents = input.getInputStream(entry)) {
                        if (entry.getName().startsWith("META-INF/services/")) {
                            copyCanonicalNewlines(contents, output, cancellation);
                        }
                        else {
                            copy(contents, output, cancellation);
                        }
                    }
                    if (entry.getName().endsWith(".class")) {
                        classes++;
                    }
                }
                output.closeEntry();
                int percent = 80 + (index + 1) * 14 / entries.size();
                if (percent != lastPercent) {
                    lastPercent = percent;
                    progress.report("remap", percent, "Normalized " + (index + 1) + " JAR entries");
                }
            }
        }
        if (classes != expectedClasses) {
            throw new IOException("Remapper produced " + classes + " classes; expected " + expectedClasses);
        }
    }

    private static void copy(InputStream input, OutputStream output, Cancellation cancellation) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            checkCancelled(cancellation);
            output.write(buffer, 0, read);
        }
    }

    private static void copyCanonicalNewlines(InputStream input, OutputStream output, Cancellation cancellation) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        boolean pendingCarriageReturn = false;
        int read;
        while ((read = input.read(buffer)) != -1) {
            checkCancelled(cancellation);
            for (int index = 0; index < read; index++) {
                int value = Byte.toUnsignedInt(buffer[index]);
                if (value == '\r') {
                    if (pendingCarriageReturn) {
                        output.write('\n');
                    }
                    pendingCarriageReturn = true;
                }
                else if (value == '\n') {
                    output.write('\n');
                    pendingCarriageReturn = false;
                }
                else {
                    if (pendingCarriageReturn) {
                        output.write('\n');
                        pendingCarriageReturn = false;
                    }
                    output.write(value);
                }
            }
        }
        if (pendingCarriageReturn) {
            output.write('\n');
        }
    }

    private static void validateExistingTarget(Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        BasicFileAttributes attributes = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
            throw new IOException("Remapped JAR target must be a regular file, not a link: " + target);
        }
    }

    private static void verifyRegularFile(Path path, String description) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new IOException(description + " does not exist or cannot be read: " + path, exception);
        }
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
            throw new IOException(description + " must be a regular file, not a link: " + path);
        }
    }

    private static boolean isModuleDescriptor(String entryName) {
        return entryName.equals("module-info.class") || entryName.endsWith("/module-info.class");
    }

    private static void validateEntryName(String name, boolean directory) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.startsWith("\\") || name.indexOf('\0') >= 0 || name.indexOf('\\') >= 0 || directory != name.endsWith("/")) {
            throw new IOException("Unsafe JAR entry name: " + name);
        }
        String[] components = name.split("/", -1);
        for (int index = 0; index < components.length; index++) {
            String component = components[index];
            boolean trailingDirectoryMarker = directory && index == components.length - 1 && component.isEmpty();
            if (!trailingDirectoryMarker && (component.isEmpty() || component.equals(".") || component.equals(".."))) {
                throw new IOException("Unsafe JAR entry name: " + name);
            }
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
            throw new IOException("Remapping cancelled", exception);
        }
    }

    private static void finishPreservingInterruption(TinyRemapper remapper) {
        boolean interrupted = Thread.interrupted();
        try {
            remapper.finish();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static IOException remappingFailure(Path input, Path target, Cancellation cancellation, RuntimeException exception) {
        if (Thread.currentThread().isInterrupted() || cancellation.isCancelled() || causedByInterruption(exception)) {
            Thread.currentThread().interrupt();
            return new IOException("Remapping cancelled for " + input, exception);
        }
        return new IOException("Tiny Remapper failed to remap " + input + " to " + target, exception);
    }

    private static boolean causedByInterruption(Throwable throwable) {
        Set<Throwable> visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable current = throwable; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    private static void cleanup(Path rawOutput, Path publication, Throwable failure) {
        delete(rawOutput, failure);
        delete(publication, failure);
    }

    private static void delete(Path path, Throwable failure) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    public Path remap(Path inputJar, Path tinyMappings, Path outputJar) throws IOException {
        return remap(inputJar, tinyMappings, outputJar, (_, _, _) -> {
        }, Cancellation.none());
    }

    public Path remap(Path inputJar, Path tinyMappings, Path outputJar, ProgressSink progress, Cancellation cancellation) throws IOException {
        Path input = Objects.requireNonNull(inputJar, "inputJar").toAbsolutePath().normalize();
        Path mapping = Objects.requireNonNull(tinyMappings, "tinyMappings").toAbsolutePath().normalize();
        Path target = Objects.requireNonNull(outputJar, "outputJar").toAbsolutePath().normalize();
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(cancellation, "cancellation");
        Path parent = target.getParent();
        if (parent == null || target.getFileName() == null) {
            throw new IOException("Refusing to use a filesystem root as the remapped JAR target: " + target);
        }
        validateExistingTarget(target);
        checkCancelled(cancellation);
        Files.createDirectories(parent);
        progress.report("remap", 0, "Verifying " + input.getFileName());
        int expectedClasses = verifyJar(input, true, cancellation, progress, 0, 20);
        verifyMappings(mapping, cancellation);
        progress.report("remap", 25, "Loading Tiny mappings");

        String nonce = UUID.randomUUID().toString();
        Path rawOutput = target.resolveSibling(target.getFileName() + "." + nonce + ".remap.tmp");
        Path publication = target.resolveSibling(target.getFileName() + "." + nonce + ".publish.tmp");
        try {
            TinyRemapper remapper = TinyRemapper.newRemapper().withMappings(TinyUtils.createTinyMappingProvider(mapping, "official", "named")).threads(threads).ignoreConflicts(false).build();
            try {
                runRemapper(remapper, input, rawOutput, expectedClasses, progress, cancellation);
            } finally {
                finishPreservingInterruption(remapper);
            }
            checkCancelled(cancellation);
            progress.report("remap", 80, "Writing deterministic remapped JAR");
            writeDeterministicJar(rawOutput, publication, expectedClasses, progress, cancellation);
            try (FileChannel channel = FileChannel.open(publication, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            int publishedClasses = verifyJar(publication, false, cancellation, progress, 95, 4);
            if (publishedClasses != expectedClasses) {
                throw new IOException("Remapper produced " + publishedClasses + " classes; expected " + expectedClasses);
            }
            Files.delete(rawOutput);
            checkCancelled(cancellation);
            Files.move(publication, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            progress.report("remap", 100, "Published " + target.getFileName());
            return target;
        } catch (IOException | Error exception) {
            cleanup(rawOutput, publication, exception);
            throw exception;
        } catch (RuntimeException exception) {
            IOException failure = remappingFailure(input, target, cancellation, exception);
            cleanup(rawOutput, publication, failure);
            throw failure;
        }
    }

    private static final class ConcurrentProgress {
        private final ProgressSink progress;
        private final Object reportLock = new Object();
        private int lastPercent = INITIAL_CONCURRENT_PROGRESS_PERCENT;

        private ConcurrentProgress(ProgressSink progress) {
            this.progress = progress;
        }

        private void report(int percent, String message) {
            synchronized (reportLock) {
                if (percent > lastPercent) {
                    lastPercent = percent;
                    progress.report("remap", percent, message);
                }
            }
        }
    }

    private record CancellableResourceCopier(Cancellation cancellation) implements OutputConsumerPath.ResourceRemapper {
        @Override
        public boolean canTransform(TinyRemapper remapper, Path relativePath) {
            return true;
        }

        @Override
        public void transform(Path destinationDirectory, Path relativePath, InputStream input, TinyRemapper remapper) throws IOException {
            checkCancelled(cancellation);
            Path destination = destinationDirectory.resolve(relativePath.toString());
            Files.createDirectories(destination.getParent());
            try (OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                copy(input, output, cancellation);
            }
        }
    }
}
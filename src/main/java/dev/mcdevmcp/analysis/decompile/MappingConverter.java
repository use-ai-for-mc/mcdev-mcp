package dev.mcdevmcp.analysis.decompile;

import dev.mcdevmcp.support.Cancellation;
import dev.mcdevmcp.support.ProgressSink;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.adapter.MappingDstNsReorder;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.proguard.ProGuardFileReader;
import net.fabricmc.mappingio.format.tiny.Tiny2FileWriter;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.UUID;

/**
 * Converts every official ProGuard mapping element into deterministic Tiny v2.
 */
public final class MappingConverter {
    private static void verifyInput(Path input) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(input, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new IOException("Official mappings do not exist or cannot be read: " + input, exception);
        }
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
            throw new IOException("Official mappings must be a regular file, not a link: " + input);
        }
    }

    private static void checkCancelled(Cancellation cancellation) throws IOException {
        try {
            cancellation.throwIfCancelled();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Mapping conversion cancelled", exception);
        }
    }

    private static void cleanup(Path temporary, Throwable failure) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    public Path convert(Path proguardMappings, Path tinyOutput) throws IOException {
        return convert(proguardMappings, tinyOutput, (_, _, _) -> {
        }, Cancellation.none());
    }

    public Path convert(Path proguardMappings, Path tinyOutput, ProgressSink progress, Cancellation cancellation) throws IOException {
        Path input = Objects.requireNonNull(proguardMappings, "proguardMappings").toAbsolutePath().normalize();
        Path target = Objects.requireNonNull(tinyOutput, "tinyOutput").toAbsolutePath().normalize();
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(cancellation, "cancellation");
        Path parent = target.getParent();
        if (parent == null || target.getFileName() == null) {
            throw new IOException("Refusing to use a filesystem root as the Tiny mapping target: " + target);
        }
        checkCancelled(cancellation);
        verifyInput(input);
        Files.createDirectories(parent);
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            progress.report("mapping", 0, "Converting " + input.getFileName() + " to Tiny v2");
            try (InputStream file = Files.newInputStream(input);
                 InputStream monitored = new ProgressInputStream(file, Files.size(input), progress, cancellation);
                 Reader source = new InputStreamReader(monitored, StandardCharsets.UTF_8);
                 var sink = Files.newBufferedWriter(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                 var writer = new Tiny2FileWriter(sink, false)) {
                ProGuardFileReader.read(source, "named", "official", new MappingSourceNsSwitch(new MappingDstNsReorder(writer, "named"), "official"));
            }
            checkCancelled(cancellation);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            MemoryMappingTree validated = new MemoryMappingTree();
            try (var inputReader = Files.newBufferedReader(temporary)) {
                MappingReader.read(inputReader, validated);
            }
            if (!"official".equals(validated.getSrcNamespace()) || !validated.getDstNamespaces().equals(java.util.List.of("named")) || validated.getClasses().isEmpty() || validated.getClasses().stream().anyMatch(mapping -> mapping.getDstName(0) == null)) {
                throw new IOException("Generated Tiny mapping is invalid: " + temporary);
            }
            checkCancelled(cancellation);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            progress.report("mapping", 100, "Published " + target.getFileName());
            return target;
        } catch (IOException | Error exception) {
            cleanup(temporary, exception);
            throw exception;
        } catch (RuntimeException exception) {
            IOException failure = new IOException("Failed to convert official mappings " + input + " to " + target, exception);
            cleanup(temporary, failure);
            throw failure;
        }
    }

    private static final class ProgressInputStream extends FilterInputStream {
        private final long totalBytes;
        private final ProgressSink progress;
        private final Cancellation cancellation;
        private long consumedBytes;
        private int lastPercent = -1;

        private ProgressInputStream(InputStream input, long totalBytes, ProgressSink progress, Cancellation cancellation) {
            super(input);
            this.totalBytes = totalBytes;
            this.progress = progress;
            this.cancellation = cancellation;
        }

        @Override
        public int read() throws IOException {
            checkCancelled(cancellation);
            int value = in.read();
            if (value >= 0) {
                report(1);
            }
            return value;
        }

        @Override
        @SuppressWarnings("NullableProblems")
        public int read(byte[] bytes, int offset, int length) throws IOException {
            checkCancelled(cancellation);
            int read = in.read(bytes, offset, length);
            if (read > 0) {
                report(read);
            }
            return read;
        }

        private void report(int read) {
            consumedBytes += read;
            int percent = totalBytes == 0 ? 0 : (int) Math.min(90, consumedBytes * 90 / totalBytes);
            if (percent != lastPercent) {
                lastPercent = percent;
                progress.report("mapping", percent, "Converted " + consumedBytes + " mapping bytes");
            }
        }
    }
}
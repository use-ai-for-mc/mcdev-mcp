package dev.mcdevmcp.analysis.decompile;

import dev.mcdevmcp.support.Cancellation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.ZipFile;

/**
 * Fully reads JAR contents so cache validation covers entry data as well as ZIP metadata.
 */
public final class JarArtifactValidator {
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final long MAXIMUM_EXPANDED_JAR_BYTES = 4L * 1024L * 1024L * 1024L;

    private JarArtifactValidator() {
    }

    public static void validate(Path file, Cancellation cancellation) throws IOException {
        validate(file, cancellation, false);
    }

    public static void validateClassJar(Path file, Cancellation cancellation) throws IOException {
        validate(file, cancellation, true);
    }

    public static boolean isValidClassJar(Path file, Cancellation cancellation) throws IOException {
        Path normalized = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        Objects.requireNonNull(cancellation, "cancellation");
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            validate(normalized, cancellation, true);
            return true;
        } catch (IOException exception) {
            if (isCancellation(exception)) {
                throw exception;
            }
            return false;
        }
    }

    private static void validate(Path file, Cancellation cancellation, boolean requireClass) throws IOException {
        Path normalized = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        Objects.requireNonNull(cancellation, "cancellation");
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("JAR is not a regular file: " + normalized);
        }
        checkCancelled(cancellation, normalized);
        try (ZipFile zip = new ZipFile(normalized.toFile())) {
            byte[] buffer = new byte[BUFFER_BYTES];
            long expandedBytes = 0;
            boolean foundFile = false;
            boolean foundClass = false;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                checkCancelled(cancellation, normalized);
                var entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                foundFile = true;
                foundClass |= entry.getName().endsWith(".class");
                CRC32 crc = new CRC32();
                long entryBytes = 0;
                try (InputStream input = zip.getInputStream(entry)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        checkCancelled(cancellation, normalized);
                        if (read > MAXIMUM_EXPANDED_JAR_BYTES - expandedBytes) {
                            throw new IOException("JAR expands beyond " + MAXIMUM_EXPANDED_JAR_BYTES + " bytes: " + normalized);
                        }
                        crc.update(buffer, 0, read);
                        entryBytes += read;
                        expandedBytes += read;
                    }
                }
                if (entry.getSize() >= 0 && entryBytes != entry.getSize()) {
                    throw new IOException("JAR entry size mismatch for " + entry.getName() + " in " + normalized + ": expected " + entry.getSize() + ", got " + entryBytes);
                }
                if (entry.getCrc() >= 0 && crc.getValue() != entry.getCrc()) {
                    throw new IOException("JAR entry CRC mismatch for " + entry.getName() + " in " + normalized);
                }
            }
            if (!foundFile) {
                throw new IOException("JAR has no file entries: " + normalized);
            }
            if (requireClass && !foundClass) {
                throw new IOException("JAR has no class entries: " + normalized);
            }
        }
    }

    private static void checkCancelled(Cancellation cancellation, Path file) throws IOException {
        try {
            cancellation.throwIfCancelled();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("JAR validation cancelled for " + file, exception);
        }
    }

    private static boolean isCancellation(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
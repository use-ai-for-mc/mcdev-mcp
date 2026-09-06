package dev.mcdevmcp.analysis.decompile;

import dev.mcdevmcp.support.Cancellation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

final class JarArtifactValidatorTest {
    @TempDir
    Path temporaryDirectory;

    private static byte[] storedJarBytes(String name, byte[] content) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(content);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            ZipEntry entry = new ZipEntry(name);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(content.length);
            entry.setCompressedSize(content.length);
            entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);
            zip.write(content);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static byte[] corruptEntryData(byte[] jar, byte[] content) {
        byte[] corrupt = jar.clone();
        for (int index = 0; index <= corrupt.length - content.length; index++) {
            if (Arrays.equals(corrupt, index, index + content.length, content, 0, content.length)) {
                corrupt[index] ^= 1;
                return corrupt;
            }
        }
        throw new AssertionError("stored ZIP fixture did not contain its entry bytes");
    }

    @Test
    void rejectsCorruptEntryDataEvenWhenTheCentralDirectoryIsReadable() throws Exception {
        byte[] content = "class-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] complete = storedJarBytes("sample/Example.class", content);
        Path valid = temporaryDirectory.resolve("valid.jar");
        Path corrupt = temporaryDirectory.resolve("corrupt.jar");
        Files.write(valid, complete);
        Files.write(corrupt, corruptEntryData(complete, content));

        JarArtifactValidator.validateClassJar(valid, Cancellation.none());
        try (ZipFile centralDirectoryOnly = new ZipFile(corrupt.toFile())) {
            assertNotNull(centralDirectoryOnly.getEntry("sample/Example.class"));
        }
        assertFalse(JarArtifactValidator.isValidClassJar(corrupt, Cancellation.none()));
        assertThrows(IOException.class, () -> JarArtifactValidator.validateClassJar(corrupt, Cancellation.none()));
    }

    @Test
    void acceptsResourceJarsButRequiresClassesForRemappedArtifacts() throws Exception {
        Path resources = temporaryDirectory.resolve("resources.jar");
        Files.write(resources, storedJarBytes("assets/example.txt", "resource".getBytes(StandardCharsets.UTF_8)));

        JarArtifactValidator.validate(resources, Cancellation.none());
        assertFalse(JarArtifactValidator.isValidClassJar(resources, Cancellation.none()));
    }

    @Test
    void preservesCancellationDuringEntryStreaming() throws Exception {
        Path large = temporaryDirectory.resolve("large.jar");
        Files.write(large, storedJarBytes("sample/Example.class", new byte[256 * 1024]));
        AtomicInteger checks = new AtomicInteger();

        try {
            IOException failure = assertThrows(IOException.class, () -> JarArtifactValidator.validateClassJar(large, () -> checks.incrementAndGet() >= 4));
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            assertTrue(Thread.interrupted(), "test must clear the expected interrupt signal");
        }
    }
}

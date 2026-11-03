package dev.mcdevmcp.storage.migration;

import dev.mcdevmcp.storage.bundle.BundleHashes;
import dev.mcdevmcp.support.Cancellation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

final class SourceProducerIdentityTest {
    @TempDir
    Path temporary;

    private static Class<?> load(Path artifact) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[]{artifact.toUri().toURL()}, null)) {
            return loader.loadClass("FixtureProducer");
        }
    }

    private static Path producerJar(Path directory) throws IOException {
        compile(directory);
        Path jar = directory.resolve("producer.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("FixtureProducer.class"));
            Files.copy(directory.resolve("FixtureProducer.class"), output);
            output.closeEntry();
        }
        return jar;
    }

    private static void compile(Path directory) throws IOException {
        Path source = Files.writeString(directory.resolve("FixtureProducer.java"), "public class FixtureProducer {}");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-d", directory.toString(), source.toString()));
    }

    private static void directoryAlias(Path link, Path destination) throws Exception {
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")) {
            Files.createSymbolicLink(link, destination);
            return;
        }
        try (Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), destination.toString()).redirectErrorStream(true).start()) {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                fail("Junction fixture creation timed out");
            }
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void pinsActualRuntimeJarThroughTrustedAncestorAlias() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("physical"));
        Path artifact = producerJar(directory);
        Path alias = temporary.resolve("alias");
        try {
            directoryAlias(alias, directory);
            Path logicalJar = alias.resolve(artifact.getFileName());
            Class<?> producer = load(logicalJar);
            assertEquals(logicalJar.toUri().toURL(), producer.getProtectionDomain().getCodeSource().getLocation());
            SourceProducerIdentity identity = SourceProducerIdentity.capture(producer, "fixture", Map.of("option", "value"), Cancellation.none());
            assertEquals(artifact.toRealPath().toString(), identity.artifact().path());
            assertEquals(BundleHashes.sha256(artifact, Cancellation.none()), identity.artifact().sha256());
            assertThrows(IOException.class, () -> SourceArtifactIdentity.capture(logicalJar, Cancellation.none()));
        } finally {
            if (Files.exists(alias, LinkOption.NOFOLLOW_LINKS)) Files.delete(alias);
        }
    }

    @Test
    void refusesNonregularRuntimeCodeSource() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("classes"));
        compile(directory);
        try (URLClassLoader loader = new URLClassLoader(new URL[]{directory.toUri().toURL()}, null)) {
            Class<?> producer = loader.loadClass("FixtureProducer");
            assertThrows(IOException.class, () -> SourceProducerIdentity.capture(producer, "fixture", Map.of(), Cancellation.none()));
        }
    }

    @Test
    void rejectsDetectedRuntimeArtifactChangeDuringHashing() throws Exception {
        Path artifact = producerJar(Files.createDirectory(temporary.resolve("producer")));
        Class<?> producer = load(artifact);
        FileTime changedTime = FileTime.fromMillis(Files.getLastModifiedTime(artifact).toMillis() + 5000);
        AtomicBoolean changed = new AtomicBoolean();
        Cancellation mutation = () -> {
            if (changed.compareAndSet(false, true)) {
                try {
                    Files.setLastModifiedTime(artifact, changedTime);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            }
            return false;
        };
        assertThrows(IOException.class, () -> SourceProducerIdentity.capture(producer, "fixture", Map.of(), mutation));
        assertTrue(changed.get());
    }
}
package dev.mcdevmcp.analysis.decompile;

import dev.mcdevmcp.support.Cancellation;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.*;

import static org.junit.jupiter.api.Assertions.*;

final class EmbeddedRemapperTest {
    private static Fixture fixture(Path root) throws Exception {
        Path classes = root.resolve("classes");
        Path source = root.resolve("a/a.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                                  package a;
                                  public class a {
                                      public int b;
                                      public void c(int value) {}
                                      public void d(String value) {}
                                  }
                                  """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-d", classes.toString(), source.toString()));
        Path input = root.resolve("input.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "a.a");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(input), manifest)) {
            addEntry(jar, "a/a.class", Files.readAllBytes(classes.resolve("a/a.class")));
            addEntry(jar, "data/example.txt", "resource".getBytes(StandardCharsets.UTF_8));
            addEntry(jar, "META-INF/services/a.a", "a.a\r\n# keep\r\n".getBytes(StandardCharsets.UTF_8));
            addEntry(jar, "META-INF/TEST.SF", "obsolete signature".getBytes(StandardCharsets.UTF_8));
        }
        Path proguard = root.resolve("client.txt");
        Files.writeString(proguard, """
                                    fixture.Named -> a.a:
                                        int field -> b
                                        void method(int) -> c
                                        void method(java.lang.String) -> d
                                    """);
        Path tiny = new MappingConverter().convert(proguard, root.resolve("client.tiny"));
        return new Fixture(input, proguard, tiny);
    }

    private static void addEntry(JarOutputStream jar, String name, byte[] contents) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(contents);
        jar.closeEntry();
    }

    private static String entryText(JarFile jar, String name) throws IOException {
        JarEntry entry = jar.getJarEntry(name);
        assertNotNull(entry, "Missing JAR entry " + name);
        try (var contents = jar.getInputStream(entry)) {
            return new String(contents.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static long temporaryFiles(Path directory, String targetName) throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith(targetName + ".") && name.endsWith(".tmp");
            }).count();
        }
    }

    @Test
    void remapsClassesAndMetaInfWhilePreservingResources() throws Exception {
        Path root = Files.createTempDirectory("embedded-remapper");
        Fixture fixture = fixture(root);
        List<Integer> progress = new ArrayList<>();
        Path output = new MinecraftRemapper(2).remap(fixture.input(), fixture.mappings(), root.resolve("output.jar"), (_, percent, _) -> progress.add(percent), Cancellation.none());
        try (JarFile jar = new JarFile(output.toFile());
             URLClassLoader loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()})) {
            assertEquals("resource", entryText(jar, "data/example.txt"));
            assertNotNull(jar.getEntry("fixture/Named.class"));
            assertEquals("fixture.Named", jar.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS));
            assertEquals("fixture.Named\n# keep\n", entryText(jar, "META-INF/services/fixture.Named"));
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().equals("META-INF/services/a.a")));
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().equals("META-INF/TEST.SF")));
            Class<?> type = Class.forName("fixture.Named", true, loader);
            assertNotNull(type.getField("field"));
            assertNotNull(type.getMethod("method", int.class));
            assertNotNull(type.getMethod("method", String.class));
        }
        assertEquals(0, progress.getFirst());
        assertEquals(100, progress.getLast());
        for (int index = 1; index < progress.size(); index++) {
            assertTrue(progress.get(index) >= progress.get(index - 1), "progress must be monotonic");
        }
    }

    @Test
    void publishesByteForByteDeterministicJarAcrossThreadCounts() throws Exception {
        Path root = Files.createTempDirectory("embedded-remapper-deterministic");
        Fixture fixture = fixture(root);
        Path first = new MinecraftRemapper(1).remap(fixture.input(), fixture.mappings(), root.resolve("first.jar"));
        Path second = new MinecraftRemapper(3).remap(fixture.input(), fixture.mappings(), root.resolve("second.jar"));
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
    }

    @Test
    void corruptInputsPreservePriorOutputAndDeleteTemporaryFiles() throws Exception {
        Path root = Files.createTempDirectory("embedded-remapper-corrupt");
        Fixture fixture = fixture(root);
        Path output = root.resolve("output.jar");
        byte[] prior = "previous".getBytes(StandardCharsets.UTF_8);
        Files.write(output, prior);

        Files.writeString(fixture.mappings(), "not tiny");
        assertThrows(IOException.class, () -> new MinecraftRemapper(1).remap(fixture.input(), fixture.mappings(), output));
        assertArrayEquals(prior, Files.readAllBytes(output));
        assertEquals(0, temporaryFiles(root, output.getFileName().toString()));

        Path validMappings = new MappingConverter().convert(fixture.proguardMappings(), root.resolve("valid.tiny"));
        Files.writeString(fixture.input(), "not a jar");
        assertThrows(IOException.class, () -> new MinecraftRemapper(1).remap(fixture.input(), validMappings, output));
        assertArrayEquals(prior, Files.readAllBytes(output));
        assertEquals(0, temporaryFiles(root, output.getFileName().toString()));
    }

    @Test
    void cancellationPreservesPriorOutputAndCallerInterruption() throws Exception {
        Path root = Files.createTempDirectory("embedded-remapper-cancel");
        Fixture fixture = fixture(root);
        Path output = root.resolve("output.jar");
        byte[] prior = "previous".getBytes(StandardCharsets.UTF_8);
        Files.write(output, prior);
        try {
            assertThrows(IOException.class, () -> new MinecraftRemapper(1).remap(fixture.input(), fixture.mappings(), output, (_, _, _) -> {
            }, () -> true));
            assertTrue(Thread.interrupted(), "cancellation must preserve the caller interrupt signal");
        } finally {
            assertFalse(Thread.interrupted(), "test must not leak interruption");
        }
        assertArrayEquals(prior, Files.readAllBytes(output));
        assertEquals(0, temporaryFiles(root, output.getFileName().toString()));
    }

    private record Fixture(Path input, Path proguardMappings, Path mappings) {
    }
}

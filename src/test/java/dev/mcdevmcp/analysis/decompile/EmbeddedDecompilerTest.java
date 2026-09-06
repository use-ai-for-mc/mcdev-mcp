package dev.mcdevmcp.analysis.decompile;

import dev.mcdevmcp.support.Cancellation;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class EmbeddedDecompilerTest {
    private static Path existingTarget(Path root) throws IOException {
        Path target = Files.createDirectory(root.resolve("sources"));
        Files.writeString(target.resolve("old.java"), "class Old {}");
        return target;
    }

    private static boolean temporaryJavaExists(Path target) {
        try (var siblings = Files.list(target.getParent())) {
            return siblings.filter(path -> path.getFileName().toString().startsWith(target.getFileName() + ".")).filter(path -> path.getFileName().toString().endsWith(".tmp")).anyMatch(EmbeddedDecompilerTest::containsJavaFile);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static boolean containsJavaFile(Path root) {
        try (var paths = Files.walk(root)) {
            return paths.anyMatch(path -> path.getFileName().toString().endsWith(".java"));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void assertNoPublicationDebris(Path target) throws IOException {
        try (var siblings = Files.list(target.getParent())) {
            assertFalse(siblings.anyMatch(path -> {
                String name = path.getFileName().toString();
                return name.startsWith(target.getFileName() + ".") && (name.endsWith(".tmp") || name.endsWith(".bak"));
            }));
        }
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }
    }

    private static Path jar(Path root) throws Exception {
        return jarWithEntry(root.resolve("input.jar"), "fixture/Example.class", classBytes(root));
    }

    private static byte[] classBytes(Path root) throws Exception {
        Path source = root.resolve("fixture/Example.java");
        Path classes = root.resolve("classes");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package fixture; public class Example { public int value() { return 7; } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-d", classes.toString(), source.toString()));
        return Files.readAllBytes(classes.resolve("fixture/Example.class"));
    }

    private static Path jarWithEntry(Path jar, String entryName, byte[] contents) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(entryName));
            output.write(contents);
            output.closeEntry();
        }
        return jar;
    }

    @Test
    void decompilesJavaAndReportsOnlyBoundaryProgress() throws Exception {
        Path root = Files.createTempDirectory("embedded-decompiler");
        Path jar = jar(root);
        ArrayList<Integer> progress = new ArrayList<>();

        Path output = new MinecraftDecompiler().decompile(jar, root.resolve("sources"), (_, percent, _) -> progress.add(percent), Cancellation.none());

        try (var files = Files.walk(output)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().equals("Example.java")));
        }
        assertEquals(List.of(0, 100), progress);
        assertNoPublicationDebris(output);
    }

    @Test
    void decompilesAcrossSymlinkedOutputPrefix() throws Exception {
        Path root = Files.createTempDirectory("decompiler-link-prefix");
        Path realBase = Files.createDirectory(root.resolve("real-base"));
        Path linkedPrefix = root.resolve("linked-prefix");
        createSymbolicLinkOrSkip(linkedPrefix, realBase);
        Path parent = realBase.resolve("out");
        Files.createDirectories(parent);
        Path jar = jar(root);

        Path output = new MinecraftDecompiler().decompile(jar, linkedPrefix.resolve("out").resolve("sources"));

        try (var files = Files.walk(output)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().equals("Example.java")));
        }
        assertNoPublicationDebris(output);
    }

    @Test
    void refusesSymlinkedOutputParent() throws Exception {
        Path root = Files.createTempDirectory("decompiler-parent-link");
        Path outside = Files.createDirectory(root.resolve("outside"));
        Path parent = root.resolve("linked-parent");
        createSymbolicLinkOrSkip(parent, outside);
        Path jar = jar(root);

        assertThrows(IOException.class, () -> new MinecraftDecompiler().decompile(jar, parent.resolve("sources")));

        assertFalse(Files.exists(outside.resolve("sources")));
    }

    @Test
    void preservesExistingSourcesWhenCancelledBeforeStart() throws Exception {
        Path root = Files.createTempDirectory("embedded-decompiler");
        Path target = existingTarget(root);

        assertThrows(IOException.class, () -> new MinecraftDecompiler().decompile(jar(root), target, (_, _, _) -> {
        }, () -> true));

        assertTrue(Files.exists(target.resolve("old.java")));
        assertNoPublicationDebris(target);
    }

    @Test
    void cancelsAfterAStagedWriteWithoutPublishingOrLeavingDebris() throws Exception {
        Path root = Files.createTempDirectory("embedded-decompiler");
        Path target = existingTarget(root);
        ArrayList<Integer> progress = new ArrayList<>();

        assertThrows(IOException.class, () -> new MinecraftDecompiler().decompile(jar(root), target, (_, percent, _) -> progress.add(percent), () -> temporaryJavaExists(target)));

        assertEquals(List.of(0), progress);
        assertTrue(Files.exists(target.resolve("old.java")));
        assertNoPublicationDebris(target);
    }

    @Test
    void restoresExistingSourcesWhenCancelledAfterBackupMove() throws Exception {
        Path root = Files.createTempDirectory("embedded-decompiler");
        Path target = existingTarget(root);

        assertThrows(IOException.class, () -> new MinecraftDecompiler().decompile(jar(root), target, (_, _, _) -> {
        }, () -> !Files.exists(target)));

        assertEquals("class Old {}", Files.readString(target.resolve("old.java")));
        assertNoPublicationDebris(target);
    }

    @Test
    void preservesInterruptionAndNeverReportsCompletion() throws Exception {
        Path root = Files.createTempDirectory("embedded-decompiler");
        Path target = existingTarget(root);
        ArrayList<Integer> progress = new ArrayList<>();

        try {
            assertThrows(IOException.class, () -> new MinecraftDecompiler().decompile(jar(root), target, (_, percent, _) -> {
                progress.add(percent);
                if (percent == 0) {
                    Thread.currentThread().interrupt();
                }
            }, Cancellation.none()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            assertTrue(Thread.interrupted(), "the interrupt flag should be cleared for subsequent tests");
        }

        assertEquals(List.of(0), progress);
        assertTrue(Files.exists(target.resolve("old.java")));
        assertNoPublicationDebris(target);
    }

    @Test
    void rejectsCorruptJarBeforeTouchingExistingSources() throws Exception {
        Path root = Files.createTempDirectory("embedded-decompiler");
        Path target = existingTarget(root);
        Path corrupt = root.resolve("corrupt.jar");
        Files.writeString(corrupt, "not a zip archive");
        ArrayList<Integer> progress = new ArrayList<>();

        assertThrows(IOException.class, () -> new MinecraftDecompiler().decompile(corrupt, target, (_, percent, _) -> progress.add(percent), Cancellation.none()));

        assertEquals(List.of(0), progress);
        assertTrue(Files.exists(target.resolve("old.java")));
        assertNoPublicationDebris(target);
    }

    @Test
    void rejectsJarWithoutClassFiles() throws Exception {
        Path root = Files.createTempDirectory("embedded-decompiler");
        Path target = existingTarget(root);
        Path empty = root.resolve("empty.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(empty))) {
            // A structurally valid but empty archive is not a decompilation input.
            output.flush();
        }

        assertThrows(IOException.class, () -> new MinecraftDecompiler().decompile(empty, target));

        assertTrue(Files.exists(target.resolve("old.java")));
        assertNoPublicationDebris(target);
    }

    @Test
    void rejectsUnsafeClassArchiveEntry() throws Exception {
        Path root = Files.createTempDirectory("embedded-decompiler");
        Path target = existingTarget(root);
        Path malicious = jarWithEntry(root.resolve("malicious.jar"), "../Escape.class", classBytes(root));

        assertThrows(IOException.class, () -> new MinecraftDecompiler().decompile(malicious, target));

        assertFalse(Files.exists(root.resolve("Escape.java")));
        assertTrue(Files.exists(target.resolve("old.java")));
        assertNoPublicationDebris(target);
    }

    @Test
    void rejectsOutputTraversalAbsolutePathsAndNonJavaEntries() throws Exception {
        Path staging = Files.createTempDirectory("decompiler-paths");

        assertThrows(IOException.class, () -> MinecraftDecompiler.resolveSourcePath(staging, "", Path.of("..", "escape.java").toString()));
        assertThrows(IOException.class, () -> MinecraftDecompiler.resolveSourcePath(staging, "", staging.resolveSibling("absolute.java").toAbsolutePath().toString()));
        assertThrows(IOException.class, () -> MinecraftDecompiler.resolveSourcePath(staging, "", "Example.class"));
        assertEquals(staging.resolve("fixture/Example.java").toAbsolutePath().normalize(), MinecraftDecompiler.resolveSourcePath(staging, "fixture", "Example.java"));
    }

    @Test
    void refusesSymbolicLinksInOutputDirectories() throws Exception {
        Path root = Files.createTempDirectory("decompiler-links");
        Path staging = Files.createDirectory(root.resolve("staging"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        createSymbolicLinkOrSkip(staging.resolve("linked"), outside);

        assertThrows(IOException.class, () -> MinecraftDecompiler.createOutputDirectory(staging, "linked/child"));

        assertFalse(Files.exists(outside.resolve("child")));
    }

    @Test
    void refusesSymbolicSourceTarget() throws Exception {
        Path root = Files.createTempDirectory("decompiler-target-link");
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.writeString(outside.resolve("old.java"), "class Old {}");
        Path target = root.resolve("sources");
        createSymbolicLinkOrSkip(target, outside);

        assertThrows(IOException.class, () -> new MinecraftDecompiler().decompile(jar(root), target));

        assertEquals("class Old {}", Files.readString(outside.resolve("old.java")));
    }

    @Test
    void boundsDiagnosticsByCountAndMessageLengthUnderConcurrency() {
        BoundedDecompilerLogger logger = new BoundedDecompilerLogger();

        IntStream.range(0, 1_000).parallel().forEach(index -> logger.writeMessage(index + "-" + "x".repeat(1_000), IFernflowerLogger.Severity.WARN));
        logger.writeMessage("ignored", IFernflowerLogger.Severity.INFO);

        assertEquals(32, logger.messages().size());
        assertTrue(logger.messages().stream().allMatch(message -> message.length() <= 400));
        assertTrue(logger.messages().stream().allMatch(message -> message.startsWith("WARN: ")));
    }
}

package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.index.IndexBuildException;
import dev.mcdevmcp.analysis.index.IndexRequest;
import dev.mcdevmcp.analysis.index.SourceIndexer;
import dev.mcdevmcp.analysis.index.SourceRoot;
import dev.mcdevmcp.storage.model.FabricApiVersion;
import dev.mcdevmcp.storage.model.SourceNamespace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class IndexerFailureAtomicityTest {
    @TempDir
    Path temporaryDirectory;

    private static IndexBuildException assertPreserved(byte[] expected, Path database, IndexRequest request) {
        IndexBuildException failure = assertThrows(IndexBuildException.class, () -> new SourceIndexer().build(request));
        assertArrayEquals(expected, IndexerTestSupport.bytes(database));
        return failure;
    }

    @Test
    void syntaxMalformedUtf8DuplicateAndCancellationPreservePriorDatabase() throws Exception {
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("empty.jar"), java.util.Map.of());
        Path valid = Files.createDirectories(temporaryDirectory.resolve("valid/ok"));
        Files.writeString(valid.resolve("Valid.java"), "package ok; public class Valid {}", StandardCharsets.UTF_8);
        Path database = temporaryDirectory.resolve("symbols.mv.db");
        new SourceIndexer().build(IndexerTestSupport.request(valid.getParent(), jar, database, 1));
        byte[] original = IndexerTestSupport.bytes(database);

        Path syntax = Files.createDirectories(temporaryDirectory.resolve("syntax"));
        Files.writeString(syntax.resolve("Broken.java"), "class Broken { void nope( { }", StandardCharsets.UTF_8);
        IndexBuildException syntaxFailure = assertPreserved(original, database, IndexerTestSupport.request(syntax, jar, database, 1));
        assertTrue(syntaxFailure.getMessage().contains("syntax"));

        Path malformed = Files.createDirectories(temporaryDirectory.resolve("malformed/dependency"));
        Files.writeString(malformed.getParent().resolve("Entry.java"), "class Entry { dependency.Malformed value; }", StandardCharsets.UTF_8);
        Files.write(malformed.resolve("Malformed.java"), new byte[]{'p', 'a', 'c', 'k', 'a', 'g', 'e', ' ', 'd', 'e', 'p', 'e', 'n', 'd', 'e', 'n', 'c', 'y', ';', ' ', 'c', 'l', 'a', 's', 's', ' ', (byte) 0xc3, 0x28});
        IndexBuildException malformedFailure = assertPreserved(original, database, IndexerTestSupport.request(malformed.getParent(), jar, database, 1));
        assertTrue(malformedFailure.getMessage().contains("UTF-8"));
        assertTrue(malformedFailure.getMessage().contains("Malformed.java"));

        Path duplicateMinecraft = Files.createDirectories(temporaryDirectory.resolve("duplicate-minecraft"));
        Path duplicateFabric = Files.createDirectories(temporaryDirectory.resolve("duplicate-fabric"));
        Files.writeString(duplicateMinecraft.resolve("One.java"), "package duplicate; class Same {}", StandardCharsets.UTF_8);
        Files.writeString(duplicateFabric.resolve("Two.java"), "package duplicate; class Same {}", StandardCharsets.UTF_8);
        List<SourceRoot> duplicateRoots = List.of(new SourceRoot(SourceNamespace.MINECRAFT, Optional.empty(), duplicateMinecraft), new SourceRoot(SourceNamespace.FABRIC, Optional.of(new FabricApiVersion("0.120.0")), duplicateFabric));
        IndexBuildException duplicateFailure = assertPreserved(original, database, IndexerTestSupport.request(duplicateRoots, jar, List.of(), database, 2));
        assertTrue(duplicateFailure.getMessage().contains("duplicate.Same"));

        for (String source : List.of("package bad; class Missing { UnknownType value; }", "package bad; import java.util.*; import java.sql.*; class Ambiguous { Date value; }")) {
            Path unresolved = Files.createDirectories(temporaryDirectory.resolve("unresolved-" + Integer.toUnsignedString(source.hashCode())));
            Files.writeString(unresolved.resolve("Bad.java"), source, StandardCharsets.UTF_8);
            IndexBuildException identityFailure = assertPreserved(original, database, IndexerTestSupport.request(unresolved, jar, database, 1));
            assertFalse(identityFailure.getMessage().isBlank());
        }

        Path many = Files.createDirectories(temporaryDirectory.resolve("many"));
        for (int index = 0; index < 30; index++) {
            Files.writeString(many.resolve("Type" + index + ".java"), "class Type" + index + " {}", StandardCharsets.UTF_8);
        }
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        IndexRequest midBuildBase = IndexerTestSupport.request(many, jar, database, 4);
        IndexRequest midBuildCancellation = new IndexRequest(midBuildBase.minecraftVersion(), midBuildBase.sourceRoots(), midBuildBase.remappedJar(), midBuildBase.classpath(), midBuildBase.outputDatabase(), midBuildBase.threads(), midBuildBase.progress(), () -> checks.incrementAndGet() > 8);
        IndexBuildException midBuildFailure = assertPreserved(original, database, midBuildCancellation);
        assertInstanceOf(InterruptedException.class, midBuildFailure.getCause());
        assertTrue(checks.get() > 8);

        IndexRequest base = IndexerTestSupport.request(valid.getParent(), jar, database, 1);
        IndexRequest cancelled = new IndexRequest(base.minecraftVersion(), base.sourceRoots(), base.remappedJar(), base.classpath(), base.outputDatabase(), base.threads(), base.progress(), () -> true);
        IndexBuildException cancellation = assertPreserved(original, database, cancelled);
        assertInstanceOf(InterruptedException.class, cancellation.getCause());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void unresolvedPackageAnnotationPreservesPriorDatabase() throws Exception {
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("package-error-empty.jar"), java.util.Map.of());
        Path valid = Files.createDirectories(temporaryDirectory.resolve("package-error-valid/ok"));
        Files.writeString(valid.resolve("Valid.java"), "package ok; public class Valid {}", StandardCharsets.UTF_8);
        Path database = temporaryDirectory.resolve("package-error.mv.db");
        new SourceIndexer().build(IndexerTestSupport.request(valid.getParent(), jar, database, 1));
        byte[] original = IndexerTestSupport.bytes(database);
        Path broken = Files.createDirectories(temporaryDirectory.resolve("package-error-broken/broken"));
        Files.writeString(broken.resolve("package-info.java"), "@MissingAnnotation package broken;", StandardCharsets.UTF_8);

        IndexBuildException failure = assertPreserved(original, database, IndexerTestSupport.request(broken.getParent(), jar, database, 1));

        assertTrue(failure.getMessage().contains("diagnostic"), failure + ", cause=" + failure.getCause());
    }

    @Test
    void unresolvedRequiredModulePreservesPriorDatabase() throws Exception {
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("module-error-empty.jar"), java.util.Map.of());
        Path valid = Files.createDirectories(temporaryDirectory.resolve("module-error-valid/ok"));
        Files.writeString(valid.resolve("Valid.java"), "package ok; public class Valid {}", StandardCharsets.UTF_8);
        Path database = temporaryDirectory.resolve("module-error.mv.db");
        new SourceIndexer().build(IndexerTestSupport.request(valid.getParent(), jar, database, 1));
        byte[] original = IndexerTestSupport.bytes(database);
        Path broken = Files.createDirectories(temporaryDirectory.resolve("module-error-broken"));
        Files.writeString(broken.resolve("module-info.java"), "module broken.module { requires missing.required.module; }", StandardCharsets.UTF_8);

        IndexBuildException failure = assertPreserved(original, database, IndexerTestSupport.request(broken, jar, database, 1));

        assertTrue(failure.getMessage().contains("diagnostic"), failure + ", cause=" + failure.getCause());
    }

    @Test
    void cancellationAfterCompilerWorkStartsTerminatesPromptlyAndPreservesPriorDatabase() throws Exception {
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("compiler-cancel-empty.jar"), java.util.Map.of());
        Path sourceRoot = Files.createDirectories(temporaryDirectory.resolve("compiler-cancel/source"));
        Files.writeString(sourceRoot.resolve("Current.java"), "class Current {}", StandardCharsets.UTF_8);
        Path database = temporaryDirectory.resolve("compiler-cancel.mv.db");
        new SourceIndexer().build(IndexerTestSupport.request(sourceRoot, jar, database, 1));
        byte[] original = IndexerTestSupport.bytes(database);
        CountDownLatch compilerStarted = new CountDownLatch(1);
        CountDownLatch compilerRelease = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean compilerInterrupted = new AtomicBoolean();
        JavacSourceParser parser = new JavacSourceParser(() -> {
            compilerStarted.countDown();
            try {
                compilerRelease.await();
            } catch (InterruptedException exception) {
                compilerInterrupted.set(true);
                Thread.currentThread().interrupt();
            }
        });
        IndexRequest base = IndexerTestSupport.request(sourceRoot, jar, database, 1);
        IndexRequest request = new IndexRequest(base.minecraftVersion(), base.sourceRoots(), base.remappedJar(), base.classpath(), base.outputDatabase(), base.threads(), base.progress(), cancelled::get);
        try (var executor = Executors.newSingleThreadExecutor()) {
            try {
                var build = executor.submit(() -> new SourceIndexPipeline(parser, new SymbolIndexWriter()).build(request));
                assertTrue(compilerStarted.await(2, TimeUnit.SECONDS));
                long cancellationStarted = System.nanoTime();
                cancelled.set(true);
                var execution = assertThrows(java.util.concurrent.ExecutionException.class, () -> build.get(2, TimeUnit.SECONDS));
                assertInstanceOf(IndexBuildException.class, execution.getCause());
                assertTrue(System.nanoTime() - cancellationStarted < TimeUnit.SECONDS.toNanos(2));
            } finally {
                compilerRelease.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
        assertTrue(compilerInterrupted.get());
        assertArrayEquals(original, IndexerTestSupport.bytes(database));
    }
}

package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.index.IndexBuildException;
import dev.mcdevmcp.analysis.index.IndexBuildEvidence;
import dev.mcdevmcp.analysis.index.SourceIndexer;
import dev.mcdevmcp.storage.h2.AtomicH2Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class CompleteCompilerDiagnosticsTest {
    private static final int DIAGNOSTICS_PER_SOURCE = 125;

    @TempDir
    Path temporaryDirectory;

    @Test
    void retainsEveryOwnedBodyErrorWithStableLocationsAcrossWorkers() throws Exception {
        Path sourceRoot = Files.createDirectories(temporaryDirectory.resolve("errors"));
        writeRepeatedBody(sourceRoot, "AErrors", "missing();");
        writeRepeatedBody(sourceRoot, "BErrors", "missing();");
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("empty.jar"), Map.of());

        IndexBuildEvidence one = new SourceIndexer().build(IndexerTestSupport.request(sourceRoot, jar, temporaryDirectory.resolve("errors-one.mv.db"), 1)).evidence();
        IndexBuildEvidence four = new SourceIndexer().build(IndexerTestSupport.request(sourceRoot, jar, temporaryDirectory.resolve("errors-four.mv.db"), 4)).evidence();

        assertEquals(one, four);
        assertEquals(2 * DIAGNOSTICS_PER_SOURCE, one.diagnostics().size());
        assertDiagnosticLocations(one, "AErrors.java", "compiler.err.cant.resolve.location.args");
        assertDiagnosticLocations(one, "BErrors.java", "compiler.err.cant.resolve.location.args");
    }

    @Test
    void retainsEveryDefaultRemovalWarningWithStableLocationsAcrossWorkers() throws Exception {
        Path sourceRoot = Files.createDirectories(temporaryDirectory.resolve("warnings"));
        writeRepeatedBody(sourceRoot, "AWarnings", "legacy.Legacy.old();");
        writeRepeatedBody(sourceRoot, "BWarnings", "legacy.Legacy.old();");
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("legacy.jar"), Map.of("legacy/Legacy.java", "package legacy; public class Legacy { @Deprecated(forRemoval=true) public static void old() {} }"));

        IndexBuildEvidence one = new SourceIndexer().build(IndexerTestSupport.request(sourceRoot, jar, temporaryDirectory.resolve("warnings-one.mv.db"), 1)).evidence();
        IndexBuildEvidence four = new SourceIndexer().build(IndexerTestSupport.request(sourceRoot, jar, temporaryDirectory.resolve("warnings-four.mv.db"), 4)).evidence();

        assertEquals(one, four);
        assertEquals(2 * DIAGNOSTICS_PER_SOURCE, one.diagnostics().size());
        assertDiagnosticLocations(one, "AWarnings.java", "compiler.warn.has.been.deprecated.for.removal");
        assertDiagnosticLocations(one, "BWarnings.java", "compiler.warn.has.been.deprecated.for.removal");
    }

    @Test
    void lateFatalDeclarationAfterRecoverableBodyErrorsPreservesPriorIndex() throws Exception {
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("fatal-empty.jar"), Map.of());
        Path sourceRoot = Files.createDirectories(temporaryDirectory.resolve("late-fatal"));
        Path database = createPriorDatabase(jar);
        byte[] prior = Files.readAllBytes(database);
        writeRepeatedBody(sourceRoot, "AErrors", "missing();");
        Files.writeString(sourceRoot.resolve("ZFatal.java"), "class ZFatal { public abstract void impossible(); }\n");

        IndexBuildException failure = assertThrows(IndexBuildException.class, () -> new SourceIndexer().build(IndexerTestSupport.request(sourceRoot, jar, database, 1)));

        assertTrue(failure.getMessage().contains("Fatal Javac diagnostic"), failure.toString());
        assertTrue(failure.getMessage().contains("ZFatal.java"), failure.toString());
        assertArrayEquals(prior, Files.readAllBytes(database));
    }

    @Test
    void evidenceMaterializationFailurePrecedesWriterAndPreservesPriorIndex() throws Exception {
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("evidence-empty.jar"), Map.of());
        Path database = createPriorDatabase(jar);
        byte[] prior = Files.readAllBytes(database);
        Path sourceRoot = Files.createDirectories(temporaryDirectory.resolve("replacement"));
        writeRepeatedBody(sourceRoot, "Replacement", "missing();");
        AtomicBoolean writerEntered = new AtomicBoolean();
        SymbolIndexWriter writer = new SymbolIndexWriter(new AtomicH2Database(), _ -> writerEntered.set(true));
        OutOfMemoryError failure = new OutOfMemoryError("injected evidence materialization failure");
        SourceIndexPipeline pipeline = new SourceIndexPipeline(new JavacSourceParser(), writer, () -> {
            throw failure;
        });

        assertSame(failure, assertThrows(OutOfMemoryError.class, () -> pipeline.build(IndexerTestSupport.request(sourceRoot, jar, database, 1))));
        assertAll(() -> assertFalse(writerEntered.get(), "Evidence must be materialized before writer publication"), () -> assertArrayEquals(prior, Files.readAllBytes(database)));
    }

    private Path createPriorDatabase(Path jar) throws Exception {
        Path sourceRoot = Files.createDirectories(temporaryDirectory.resolve("prior"));
        Files.writeString(sourceRoot.resolve("Prior.java"), "class Prior { int original; }\n");
        Path database = temporaryDirectory.resolve("prior.mv.db");
        new SourceIndexer().build(IndexerTestSupport.request(sourceRoot, jar, database, 1));
        return database;
    }

    private static void writeRepeatedBody(Path sourceRoot, String name, String statement) throws Exception {
        String body = String.join("\n", java.util.Collections.nCopies(DIAGNOSTICS_PER_SOURCE, "        " + statement));
        Files.writeString(sourceRoot.resolve(name + ".java"), "class " + name + " {\n    void run() {\n" + body + "\n    }\n}\n");
    }

    private static void assertDiagnosticLocations(IndexBuildEvidence evidence, String file, String code) {
        List<String> diagnostics = evidence.diagnostics().stream().filter(diagnostic -> diagnostic.startsWith(file + ":")).toList();
        assertEquals(DIAGNOSTICS_PER_SOURCE, diagnostics.size());
        assertEquals(diagnostics.stream().sorted().toList(), diagnostics);
        List<Long> lines = diagnostics.stream().map(diagnostic -> Long.parseLong(diagnostic.substring(file.length() + 1, diagnostic.indexOf(':', file.length() + 1)))).sorted().toList();
        assertEquals(IntStream.range(3, DIAGNOSTICS_PER_SOURCE + 3).mapToObj(value -> (long) value).toList(), lines);
        assertTrue(diagnostics.stream().allMatch(diagnostic -> diagnostic.endsWith("[" + code + "]")), diagnostics.toString());
    }
}

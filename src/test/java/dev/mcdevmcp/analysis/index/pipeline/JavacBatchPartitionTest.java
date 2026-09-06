package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.classfile.ClassFileTypeCatalog;
import dev.mcdevmcp.analysis.index.SourceRoot;
import dev.mcdevmcp.storage.model.SourceNamespace;
import dev.mcdevmcp.support.Cancellation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class JavacBatchPartitionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void emptyAndUnitBoundaryPlansHaveExactOrderedCoverage() {
        assertTrue(JavacSourceParser.partition(List.of()).isEmpty());
        List<DecodedSource> sources = IntStream.range(0, 513).mapToObj(index -> source("Type" + index + ".java", "")).toList();
        assertEquals(List.of(512), sizes(JavacSourceParser.partition(sources.subList(0, 512))));
        List<List<DecodedSource>> batches = JavacSourceParser.partition(sources);
        assertEquals(List.of(512, 1), sizes(batches));
        assertEquals(sources, flatten(batches));
        assertThrows(UnsupportedOperationException.class, () -> batches.add(List.of()));
        assertThrows(UnsupportedOperationException.class, () -> batches.getFirst().clear());
    }

    @Test
    void decodedUtf16BoundaryIsExactAndOversizedUnitsStandAlone() {
        int bound = JavacSourceParser.MAXIMUM_BATCH_CHARACTERS;
        DecodedSource prefix = source("Prefix.java", "x".repeat(bound - 2));
        DecodedSource surrogatePair = source("Pair.java", "\uD83D\uDE00");
        DecodedSource overflow = source("Overflow.java", "x");
        List<DecodedSource> exact = List.of(prefix, surrogatePair);
        assertEquals(List.of(2), sizes(JavacSourceParser.partition(exact)));
        assertEquals(List.of(2, 1), sizes(JavacSourceParser.partition(List.of(prefix, surrogatePair, overflow))));
        DecodedSource oversized = source("Oversized.java", "x".repeat(bound + 1));
        List<DecodedSource> sources = List.of(overflow, oversized, surrogatePair);
        List<List<DecodedSource>> batches = JavacSourceParser.partition(sources);
        assertEquals(List.of(1, 1, 1), sizes(batches));
        assertSame(oversized, batches.get(1).getFirst());
        assertEquals(sources, flatten(batches));
    }

    @Test
    void moduleDescriptorRetainsSingleContextRegardlessOfOrdinaryBounds() {
        List<DecodedSource> sources = new ArrayList<>();
        sources.add(source("module-info.java", "module fixture {}"));
        sources.add(source("Large.java", "x".repeat(JavacSourceParser.MAXIMUM_BATCH_CHARACTERS + 1)));
        for (int index = 0; index < 513; index++) {
            sources.add(source("Type" + index + ".java", ""));
        }
        assertEquals(List.of(sources), JavacSourceParser.partition(sources));
    }

    @Test
    void multipleCompilerContextsPreserveRawCaptureErrorsAndNotesAcrossWorkers() throws Exception {
        Path sourceRoot = Files.createDirectories(temporaryDirectory.resolve("real"));
        String body = " { void run(java.util.List<?> values) { values.add(new Object()); java.util.List raw = new java.util.ArrayList(); raw.add(1); } }\n";
        for (String name : List.of("A", "B", "C", "D")) {
            String padding = name.equals("A") ? "/*" + "x".repeat(JavacSourceParser.MAXIMUM_BATCH_CHARACTERS) + "*/\n" : "";
            Files.writeString(sourceRoot.resolve(name + ".java"), padding + "class " + name + body);
        }
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("empty.jar"), Map.of());
        var requestOne = IndexerTestSupport.request(sourceRoot, jar, temporaryDirectory.resolve("one.mv.db"), 1);
        var requestFour = IndexerTestSupport.request(sourceRoot, jar, temporaryDirectory.resolve("four.mv.db"), 4);
        SourceCorpus corpus = SourceCorpus.discover(requestOne.sourceRoots(), Cancellation.none());
        List<List<DecodedSource>> batches = JavacSourceParser.partition(corpus.sources());
        assertEquals(List.of(1, 3), sizes(batches));
        assertEquals(corpus.sources(), flatten(batches));
        ClassFileTypeCatalog catalog = ClassFileTypeCatalog.read(jar, Cancellation.none());
        CompilerClasspath classpath = CompilerClasspath.read(requestOne);

        ParsedIndex one = new JavacSourceParser().parse(requestOne, catalog, classpath, corpus);
        ParsedIndex four = new JavacSourceParser().parse(requestFour, catalog, classpath, corpus);

        assertEquals(one, four);
        assertEquals(4, one.diagnostics().stream().filter(diagnostic -> diagnostic.kind() == Diagnostic.Kind.ERROR).count());
        assertTrue(one.diagnostics().stream().anyMatch(diagnostic -> diagnostic.message().contains("capture#")), one.diagnostics().toString());
        List<IndexDiagnostic> notes = one.diagnostics().stream().filter(diagnostic -> diagnostic.kind() == Diagnostic.Kind.NOTE).toList();
        assertEquals(5, notes.size(), notes.toString());
        assertEquals(List.of("compiler", "A.java", "A.java", "B.java", "B.java"), notes.stream().map(diagnostic -> diagnostic.sourcePath().toString()).toList());
    }

    private DecodedSource source(String name, String content) {
        SourceRoot root = new SourceRoot(SourceNamespace.MINECRAFT, Optional.empty(), temporaryDirectory);
        return new DecodedSource(root, temporaryDirectory.resolve(name), Path.of(name), name, content, URI.create("memory:/" + name), "", List.of());
    }

    private static List<Integer> sizes(List<List<DecodedSource>> batches) {
        return batches.stream().map(List::size).toList();
    }

    private static List<DecodedSource> flatten(List<List<DecodedSource>> batches) {
        return batches.stream().flatMap(List::stream).toList();
    }
}

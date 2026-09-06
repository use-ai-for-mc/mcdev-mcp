package dev.mcdevmcp.benchmark;

import dev.mcdevmcp.analysis.index.IndexRequest;
import dev.mcdevmcp.analysis.index.IndexSummary;
import dev.mcdevmcp.analysis.index.SourceIndexer;
import dev.mcdevmcp.analysis.index.SourceRoot;
import dev.mcdevmcp.storage.model.MethodReference;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.SourceNamespace;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class CorpusQualificationMainTest {
    private static final MinecraftVersion VERSION = new MinecraftVersion("1.21.11");
    private static final String ZERO_HASH = "0".repeat(64);
    private static final String NODE_CALLGRAPH_HASH = "2".repeat(64);
    private static final String NODE_GENERATOR = "frozen-callgraph-generator";
    private static final NodeCallgraphIdentity NODE_CALLGRAPH = new NodeCallgraphIdentity(NODE_GENERATOR, "4".repeat(64), NodeCallgraphIdentity.METHOD_CALL_PROTOCOL, NodeCallgraphIdentity.SQLITE_CALLS_SCHEMA);
    private static final NodeOracleIdentity ORACLE = new NodeOracleIdentity("a".repeat(40), "b".repeat(40));

    @TempDir
    Path temporaryDirectory;

    @Test
    void accountsForPackageAndModuleUnitsWithoutTreatingThemAsSkipped() {
        var evidence = new dev.mcdevmcp.analysis.index.IndexBuildEvidence(List.of("module-info.java", "sample/Target.java", "sample/package-info.java"), List.of("module-info.java", "sample/Target.java", "sample/package-info.java"), List.of("sample/Target.java"), List.of("module-info.java", "sample/package-info.java"), List.of());
        List<String> failures = new ArrayList<>();

        CorpusQualificationMain.validateAccounting(failures, evidence);

        assertTrue(failures.isEmpty());
        assertEquals(new CompilationUnitCounts(3, 3, 1, 2), CorpusQualificationMain.unitCounts(evidence));
    }

    @Test
    void rejectsPartialOverlappingAndDuplicateCompilationUnitAccounting() {
        var evidence = new dev.mcdevmcp.analysis.index.IndexBuildEvidence(List.of("A.java", "B.java"), List.of("A.java"), List.of("A.java"), List.of("A.java"), List.of());
        List<String> failures = new ArrayList<>();

        CorpusQualificationMain.validateAccounting(failures, evidence);

        assertTrue(failures.contains("discovered units are not exactly parsed compilation units"));
        assertTrue(failures.contains("typed and type-free compilation units overlap"));
    }

    @Test
    void logicalFramingPreventsConcatenationCollisions() {
        var first = new CorpusQualificationMain.LogicalDigest();
        first.value("ab");
        first.value("c");
        var second = new CorpusQualificationMain.LogicalDigest();
        second.value("a");
        second.value("bc");

        assertNotEquals(first.finish(), second.finish());
    }

    @Test
    void crossLanguageProbeFramingHasAStableUnicodeNullAndOrderingVector() throws Exception {
        CorpusProbeProjectionRowV1 row = new CorpusProbeProjectionRowV1(null, null, List.of(), null, "méthod\n\"", null, "void", List.of(new CorpusProbeParameterV1("π", "java.lang.String[]")), List.of("public", "static"), 7, 9, null, null, null, null);
        CorpusProbeProjectionV1 vector = new CorpusProbeProjectionV1(CorpusProbeProjectionV1.SCHEMA, CorpusProbeKind.SYMBOL_METHOD, "sample.É#méthod\n\"", List.of(row));

        assertEquals("484633761215a973adcddf71bbe833a9b0e223db54428e6dbcf93f597532458e", vector.signature());

        CorpusProbeProjectionV1 first = CorpusProbeProjectionV1.referenceProbe(CorpusProbeKind.CALLERS, "sample.Target#run", List.of(new MethodReference("z.Last", "call", null, null, 2), new MethodReference("a.First", "call", "()V", 4, 1)));
        CorpusProbeProjectionV1 second = CorpusProbeProjectionV1.referenceProbe(CorpusProbeKind.CALLERS, "sample.Target#run", List.of(new MethodReference("a.First", "call", "()V", 4, 91), new MethodReference("z.Last", "call", "", null, 92)));

        assertEquals(first.signature(), second.signature());
    }

    @Test
    void referenceProbeRejectsResultsBeyondTheFrozenNodeLimit() {
        List<MethodReference> references = new ArrayList<>();
        for (int index = 0; index <= CorpusProbeProjectionV1.PROBE_REFERENCE_LIMIT; index++) {
            references.add(new MethodReference("sample.C" + String.format("%03d", index), "call", "()V", index, index + 1L));
        }

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> CorpusProbeProjectionV1.referenceProbe(CorpusProbeKind.CALLERS, "sample.Target#run", references));

        assertTrue(failure.getMessage().contains("limit of 100"));
    }

    @Test
    void requiresIdenticalCompleteRepresentativeProbeDefinitions() {
        List<CorpusProbe> complete = placeholderProbes();
        CorpusQualificationMain.validateProbeDefinitions(complete, complete.reversed());

        List<CorpusProbe> missingKind = complete.stream().filter(probe -> probe.kind() != CorpusProbeKind.CALLEES).toList();
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> CorpusQualificationMain.validateProbeDefinitions(missingKind, missingKind));
        assertTrue(missing.getMessage().contains("missing [CALLEES]"));

        List<CorpusProbe> mismatched = new ArrayList<>(complete);
        mismatched.set(0, new CorpusProbe(CorpusProbeKind.SYMBOL_CLASS, "sample.Other", ZERO_HASH));
        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class, () -> CorpusQualificationMain.validateProbeDefinitions(complete, mismatched));
        assertTrue(mismatch.getMessage().contains("same probe kind/key set"));

        List<CorpusProbe> duplicate = new ArrayList<>(complete);
        duplicate.add(complete.getFirst());
        IllegalArgumentException duplicated = assertThrows(IllegalArgumentException.class, () -> CorpusQualificationMain.validateProbeDefinitions(duplicate, complete));
        assertTrue(duplicated.getMessage().contains("Duplicate expectation probe"));
    }

    @Test
    void symbolLogicalHashIgnoresOutputPathBuiltAtAndWorkerCount() throws Exception {
        Fixture fixture = fixture();
        Path one = temporaryDirectory.resolve("one/symbols.mv.db");
        Path four = temporaryDirectory.resolve("four/symbols.mv.db");

        IndexSummary oneSummary = buildIndex(fixture, one, 1);
        IndexSummary fourSummary = buildIndex(fixture, four, 4);

        assertEquals(oneSummary.evidence(), fourSummary.evidence());
        assertEquals(CorpusQualificationMain.logicalTableHash(one), CorpusQualificationMain.logicalTableHash(four));
        assertEquals(List.of("module-info.java", "sample/package-info.java"), oneSummary.evidence().typeFreeCompilationUnits());
    }

    @Test
    void requiresEveryNodeDeltaToBeExplainedAndRejectsUnusedApprovals() {
        CorpusIndexCounts nodeIndex = new CorpusIndexCounts(1, 1, 0, 0, 0);
        CorpusIndexCounts javaIndex = new CorpusIndexCounts(1, 2, 0, 0, 0);
        CorpusCallgraphCounts graph = new CorpusCallgraphCounts(1, 1, 1);
        NodeCorpusBaseline baseline = baseline(ZERO_HASH, ZERO_HASH, nodeIndex, graph, List.of());
        List<String> unexplained = new ArrayList<>();

        List<ReviewedNodeDifference> applied = CorpusQualificationMain.validateNodeDeltas(unexplained, baseline, javaIndex, graph, List.of(), List.of());

        assertTrue(applied.isEmpty());
        assertEquals(List.of("unreviewed Node difference: INDEX_METRIC:types"), unexplained);

        var approval = new ReviewedNodeDifference(ReviewedNodeDifferenceKind.INDEX_METRIC, "types", "Javac includes the reviewed secondary top-level declaration.");
        List<String> explained = new ArrayList<>();
        assertEquals(List.of(approval), CorpusQualificationMain.validateNodeDeltas(explained, baseline, javaIndex, graph, List.of(), List.of(approval)));
        assertTrue(explained.isEmpty());

        List<String> unused = new ArrayList<>();
        CorpusQualificationMain.validateNodeDeltas(unused, baseline, nodeIndex, graph, List.of(), List.of(approval));
        assertEquals(List.of("unused reviewed Node difference: INDEX_METRIC:types"), unused);
        assertThrows(IllegalArgumentException.class, () -> new ReviewedNodeDifference(ReviewedNodeDifferenceKind.INDEX_METRIC, "types", " "));
    }

    @Test
    void comparesProbeCountHashAndDiagnosticEvidenceExactly() {
        CorpusProbe expected = new CorpusProbe(CorpusProbeKind.SYMBOL_CLASS, "sample.Target", ZERO_HASH);
        CorpusProbe actual = new CorpusProbe(CorpusProbeKind.SYMBOL_CLASS, "sample.Target", "1".repeat(64));
        List<String> failures = new ArrayList<>();

        CorpusQualificationMain.compareExpectedProbes(failures, List.of(expected), List.of(actual));
        CorpusQualificationMain.compare(failures, "indexCounts", new CorpusIndexCounts(1, 1, 1, 1, 1), new CorpusIndexCounts(1, 2, 1, 1, 1));
        CorpusQualificationMain.compare(failures, "diagnostics", List.of(), List.of("compiler.err.example"));

        assertEquals(List.of("probe SYMBOL_CLASS:sample.Target mismatch", "indexCounts mismatch", "diagnostics mismatch"), failures);
    }

    @Test
    void rejectsUnsupportedVersionAndCanonicalOutputOverlap() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> CorpusQualificationMain.Arguments.parse(new String[]{"--minecraft-version", "1.21.5", "--source-root", "source", "--remapped-jar", "client.jar", "--node-baseline", "node.json", "--expectation", "expectation.json", "--output-root", "output", "--workers", "1"}));

        Path source = Files.createDirectories(temporaryDirectory.resolve("overlap-source"));
        Path jar = Files.write(temporaryDirectory.resolve("overlap.jar"), new byte[]{1});
        Path expectationFile = temporaryDirectory.resolve("expectation.json");
        Path baselineFile = temporaryDirectory.resolve("baseline.json");
        List<CorpusProbe> probes = placeholderProbes();
        CorpusExpectation expectation = new CorpusExpectation(2, VERSION, ZERO_HASH, ZERO_HASH, NODE_CALLGRAPH_HASH, NODE_CALLGRAPH, new CompilationUnitCounts(0, 0, 0, 0), new CorpusIndexCounts(0, 0, 0, 0, 0), new CorpusCallgraphCounts(0, 0, 0), ZERO_HASH, ZERO_HASH, ZERO_HASH, ORACLE, List.of(), probes, List.of(), ClasspathFixtures.IDENTITY, ClasspathFixtures.RAW_HASH);
        NodeCorpusBaseline baseline = baseline(ZERO_HASH, ZERO_HASH, new CorpusIndexCounts(0, 0, 0, 0, 0), new CorpusCallgraphCounts(0, 0, 0), probes);
        Files.write(expectationFile, McpJsonDefaults.getMapper().writeValueAsBytes(expectation));
        Files.write(baselineFile, McpJsonDefaults.getMapper().writeValueAsBytes(baseline));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> CorpusQualificationMain.main(arguments(source, jar, baselineFile, expectationFile, source.resolve("nested-output"), 1)));

        assertTrue(failure.getMessage().contains("must not overlap"));
    }

    @Test
    void rejectsNodeBaselineWithMismatchedCorpusOrOracleProvenance() throws Exception {
        Fixture fixture = fixture();
        String sourceHash = AnalysisBenchmarkMain.sha256Tree(fixture.sources());
        String jarHash = AnalysisBenchmarkMain.sha256(fixture.jar());
        List<CorpusProbe> probes = placeholderProbes();
        CorpusExpectation expectation = new CorpusExpectation(2, VERSION, sourceHash, jarHash, NODE_CALLGRAPH_HASH, NODE_CALLGRAPH, new CompilationUnitCounts(0, 0, 0, 0), new CorpusIndexCounts(0, 0, 0, 0, 0), new CorpusCallgraphCounts(0, 0, 0), ZERO_HASH, ZERO_HASH, ZERO_HASH, ORACLE, List.of(), probes, List.of(), ClasspathFixtures.IDENTITY, ClasspathFixtures.RAW_HASH);
        Path expectationFile = temporaryDirectory.resolve("provenance-expectation.json");
        Path baselineFile = temporaryDirectory.resolve("provenance-baseline.json");
        Files.write(expectationFile, McpJsonDefaults.getMapper().writeValueAsBytes(expectation));
        CorpusIndexCounts index = new CorpusIndexCounts(0, 0, 0, 0, 0);
        CorpusCallgraphCounts graph = new CorpusCallgraphCounts(0, 0, 0);

        Files.write(baselineFile, McpJsonDefaults.getMapper().writeValueAsBytes(baseline("1".repeat(64), jarHash, index, graph, probes)));
        IllegalArgumentException sourceFailure = assertThrows(IllegalArgumentException.class, () -> CorpusQualificationMain.qualify(CorpusQualificationMain.Arguments.parse(arguments(fixture.sources(), fixture.jar(), baselineFile, expectationFile, temporaryDirectory.resolve("bad-source"), 1)), () -> 1));
        assertTrue(sourceFailure.getMessage().contains("source logical hash"));

        NodeCorpusBaseline wrongCallgraphHash = new NodeCorpusBaseline(1, VERSION, sourceHash, jarHash, "3".repeat(64), NODE_CALLGRAPH, ORACLE, index, graph, probes);
        Files.write(baselineFile, McpJsonDefaults.getMapper().writeValueAsBytes(wrongCallgraphHash));
        IllegalArgumentException hashFailure = assertThrows(IllegalArgumentException.class, () -> CorpusQualificationMain.qualify(CorpusQualificationMain.Arguments.parse(arguments(fixture.sources(), fixture.jar(), baselineFile, expectationFile, temporaryDirectory.resolve("bad-callgraph-hash"), 1)), () -> 1));
        assertTrue(hashFailure.getMessage().contains("precomputed callgraph SHA-256"));

        NodeCallgraphIdentity otherCallgraph = new NodeCallgraphIdentity(NODE_GENERATOR, "5".repeat(64), NodeCallgraphIdentity.METHOD_CALL_PROTOCOL, NodeCallgraphIdentity.SQLITE_CALLS_SCHEMA);
        NodeCorpusBaseline wrongCallgraphIdentity = new NodeCorpusBaseline(1, VERSION, sourceHash, jarHash, NODE_CALLGRAPH_HASH, otherCallgraph, ORACLE, index, graph, probes);
        Files.write(baselineFile, McpJsonDefaults.getMapper().writeValueAsBytes(wrongCallgraphIdentity));
        IllegalArgumentException callgraphIdentityFailure = assertThrows(IllegalArgumentException.class, () -> CorpusQualificationMain.qualify(CorpusQualificationMain.Arguments.parse(arguments(fixture.sources(), fixture.jar(), baselineFile, expectationFile, temporaryDirectory.resolve("bad-callgraph-identity"), 1)), () -> 1));
        assertTrue(callgraphIdentityFailure.getMessage().contains("callgraph generator and schema identity"));

        NodeOracleIdentity otherOracle = new NodeOracleIdentity("c".repeat(40), "d".repeat(40));
        NodeCorpusBaseline wrongOracle = new NodeCorpusBaseline(1, VERSION, sourceHash, jarHash, NODE_CALLGRAPH_HASH, NODE_CALLGRAPH, otherOracle, index, graph, probes);
        Files.write(baselineFile, McpJsonDefaults.getMapper().writeValueAsBytes(wrongOracle));
        IllegalArgumentException oracleFailure = assertThrows(IllegalArgumentException.class, () -> CorpusQualificationMain.qualify(CorpusQualificationMain.Arguments.parse(arguments(fixture.sources(), fixture.jar(), baselineFile, expectationFile, temporaryDirectory.resolve("bad-oracle"), 1)), () -> 1));
        assertTrue(oracleFailure.getMessage().contains("pinned Node oracle identity"));

        NodeCorpusBaseline wrongVersion = new NodeCorpusBaseline(1, new MinecraftVersion("26.1"), sourceHash, jarHash, NODE_CALLGRAPH_HASH, NODE_CALLGRAPH, ORACLE, index, graph, probes);
        Files.write(baselineFile, McpJsonDefaults.getMapper().writeValueAsBytes(wrongVersion));
        IllegalArgumentException versionFailure = assertThrows(IllegalArgumentException.class, () -> CorpusQualificationMain.qualify(CorpusQualificationMain.Arguments.parse(arguments(fixture.sources(), fixture.jar(), baselineFile, expectationFile, temporaryDirectory.resolve("bad-version"), 1)), () -> 1));
        assertTrue(versionFailure.getMessage().contains("Minecraft version"));
    }

    @Test
    void rejectsMalformedOrUnsupportedNodeCallgraphProvenance() {
        assertThrows(IllegalArgumentException.class, () -> new NodeCallgraphIdentity(NODE_GENERATOR, " ", NodeCallgraphIdentity.METHOD_CALL_PROTOCOL, NodeCallgraphIdentity.SQLITE_CALLS_SCHEMA));
        assertThrows(IllegalArgumentException.class, () -> new NodeCallgraphIdentity(NODE_GENERATOR, "A".repeat(64), NodeCallgraphIdentity.METHOD_CALL_PROTOCOL, NodeCallgraphIdentity.SQLITE_CALLS_SCHEMA));
        assertThrows(IllegalArgumentException.class, () -> new NodeCallgraphIdentity("unsafe generator", "4".repeat(64), NodeCallgraphIdentity.METHOD_CALL_PROTOCOL, NodeCallgraphIdentity.SQLITE_CALLS_SCHEMA));
        assertThrows(IllegalArgumentException.class, () -> new NodeCallgraphIdentity(NODE_GENERATOR, "4".repeat(64), "other-protocol", NodeCallgraphIdentity.SQLITE_CALLS_SCHEMA));
        assertThrows(IllegalArgumentException.class, () -> new NodeCallgraphIdentity(NODE_GENERATOR, "4".repeat(64), NodeCallgraphIdentity.METHOD_CALL_PROTOCOL, "other-schema"));
        assertThrows(IllegalArgumentException.class, () -> new NodeCorpusBaseline(1, VERSION, ZERO_HASH, ZERO_HASH, "A".repeat(64), NODE_CALLGRAPH, ORACLE, new CorpusIndexCounts(0, 0, 0, 0, 0), new CorpusCallgraphCounts(0, 0, 0), List.of()));
    }

    @Test
    void allowsImmutableCorpusInputsInProductionCacheButRejectsOutputThere() throws Exception {
        Path productionCache = Files.createDirectories(temporaryDirectory.resolve("production-cache"));
        Fixture fixture = fixture(productionCache);
        String sourceHash = AnalysisBenchmarkMain.sha256Tree(fixture.sources());
        String jarHash = AnalysisBenchmarkMain.sha256(fixture.jar());
        List<CorpusProbe> probes = placeholderProbes();
        CorpusExpectation expectation = new CorpusExpectation(2, VERSION, sourceHash, jarHash, NODE_CALLGRAPH_HASH, NODE_CALLGRAPH, new CompilationUnitCounts(0, 0, 0, 0), new CorpusIndexCounts(0, 0, 0, 0, 0), new CorpusCallgraphCounts(0, 0, 0), ZERO_HASH, ZERO_HASH, ZERO_HASH, ORACLE, List.of(), probes, List.of(), ClasspathFixtures.IDENTITY, ClasspathFixtures.RAW_HASH);
        NodeCorpusBaseline baseline = baseline(sourceHash, jarHash, new CorpusIndexCounts(0, 0, 0, 0, 0), new CorpusCallgraphCounts(0, 0, 0), probes);
        Path expectationFile = temporaryDirectory.resolve("cache-expectation.json");
        Path baselineFile = temporaryDirectory.resolve("cache-baseline.json");
        Files.write(expectationFile, McpJsonDefaults.getMapper().writeValueAsBytes(expectation));
        Files.write(baselineFile, McpJsonDefaults.getMapper().writeValueAsBytes(baseline));
        Path externalOutput = temporaryDirectory.resolve("external-output");

        assertThrows(IllegalStateException.class, () -> CorpusQualificationMain.qualify(CorpusQualificationMain.Arguments.parse(arguments(fixture.sources(), fixture.jar(), baselineFile, expectationFile, externalOutput, productionCache, 1)), () -> 1));
        assertTrue(Files.isRegularFile(externalOutput.resolve(CorpusQualificationMain.REPORT_NAME)));

        IllegalArgumentException overlap = assertThrows(IllegalArgumentException.class, () -> CorpusQualificationMain.qualify(CorpusQualificationMain.Arguments.parse(arguments(fixture.sources(), fixture.jar(), baselineFile, expectationFile, productionCache.resolve("qualification-output"), productionCache, 1)), () -> 1));
        assertTrue(overlap.getMessage().contains("production cache"));
    }

    @Test
    void writesTypedFailureReportWhenTheHeapIsExhausted() throws Exception {
        Path output = temporaryDirectory.resolve("oom-report");
        var arguments = new CorpusQualificationMain.Arguments(VERSION, temporaryDirectory.resolve("source"), temporaryDirectory.resolve("client.jar"), temporaryDirectory.resolve("baseline.json"), temporaryDirectory.resolve("expectation.json"), output, temporaryDirectory.resolve("production-cache"), 4, ClasspathFixtures.empty(temporaryDirectory.resolve("dependencies")));

        CorpusQualificationMain.writeOutOfMemoryReport(arguments, new OutOfMemoryError("fixture"));

        CorpusQualificationReport report = McpJsonDefaults.getMapper().readValue(Files.readAllBytes(output.resolve(CorpusQualificationMain.REPORT_NAME)), CorpusQualificationReport.class);
        assertFalse(report.qualified());
        assertEquals(List.of("Java heap exhausted: OutOfMemoryError"), report.failures());
        assertEquals(VERSION, report.minecraftVersion());
        assertEquals(4, report.workers());
    }

    @Test
    void completeFixtureQualifiesAndIsDeterministicAcrossWorkers() throws Exception {
        Fixture fixture = fixture();
        List<CorpusProbe> placeholderProbes = placeholderProbes();
        String sourceHash = AnalysisBenchmarkMain.sha256Tree(fixture.sources());
        String jarHash = AnalysisBenchmarkMain.sha256(fixture.jar());
        CorpusExpectation placeholder = new CorpusExpectation(2, VERSION, sourceHash, jarHash, NODE_CALLGRAPH_HASH, NODE_CALLGRAPH, new CompilationUnitCounts(0, 0, 0, 0), new CorpusIndexCounts(0, 0, 0, 0, 0), new CorpusCallgraphCounts(0, 0, 0), ZERO_HASH, ZERO_HASH, ZERO_HASH, ORACLE, List.of(), placeholderProbes, List.of(), ClasspathFixtures.IDENTITY, ClasspathFixtures.RAW_HASH);
        NodeCorpusBaseline placeholderBaseline = baseline(sourceHash, jarHash, new CorpusIndexCounts(0, 0, 0, 0, 0), new CorpusCallgraphCounts(0, 0, 0), placeholderProbes);
        Path expectationFile = temporaryDirectory.resolve("fixture-expectation.json");
        Path baselineFile = temporaryDirectory.resolve("fixture-baseline.json");
        Files.write(expectationFile, McpJsonDefaults.getMapper().writeValueAsBytes(placeholder));
        Files.write(baselineFile, McpJsonDefaults.getMapper().writeValueAsBytes(placeholderBaseline));
        Path firstOutput = temporaryDirectory.resolve("qualification-one");

        assertThrows(IllegalStateException.class, () -> CorpusQualificationMain.qualify(CorpusQualificationMain.Arguments.parse(arguments(fixture.sources(), fixture.jar(), baselineFile, expectationFile, firstOutput, 1)), () -> 1));
        CorpusQualificationReport observed = readReport(firstOutput);
        CorpusExpectation reviewed = new CorpusExpectation(2, VERSION, observed.sourceLogicalHash(), observed.remappedJarSha256(), NODE_CALLGRAPH_HASH, NODE_CALLGRAPH, observed.compilationUnits(), observed.indexCounts(), observed.callgraphCounts(), observed.symbolLogicalHash(), observed.callgraphLogicalIdentity(), observed.callgraphLogicalHash(), ORACLE, observed.diagnostics(), observed.probes(), List.of(), ClasspathFixtures.IDENTITY, ClasspathFixtures.RAW_HASH);
        NodeCorpusBaseline reviewedBaseline = baseline(observed.sourceLogicalHash(), observed.remappedJarSha256(), observed.indexCounts(), observed.callgraphCounts(), observed.probes());
        Files.write(expectationFile, McpJsonDefaults.getMapper().writeValueAsBytes(reviewed));
        Files.write(baselineFile, McpJsonDefaults.getMapper().writeValueAsBytes(reviewedBaseline));
        Path secondOutput = temporaryDirectory.resolve("qualification-four");

        CorpusQualificationMain.qualify(CorpusQualificationMain.Arguments.parse(arguments(fixture.sources(), fixture.jar(), baselineFile, expectationFile, secondOutput, 4)), () -> 1);

        CorpusQualificationReport qualified = readReport(secondOutput);
        assertTrue(qualified.qualified(), qualified.failures().toString());
        assertEquals(observed.symbolLogicalHash(), qualified.symbolLogicalHash());
        assertEquals(observed.callgraphLogicalIdentity(), qualified.callgraphLogicalIdentity());
        assertEquals(observed.callgraphLogicalHash(), qualified.callgraphLogicalHash());
        assertEquals(observed.compilationUnits(), qualified.compilationUnits());
        assertEquals(List.of("module-info.java", "sample/package-info.java"), qualified.typeFreeCompilationUnits());
    }

    private static List<CorpusProbe> placeholderProbes() {
        return List.of(new CorpusProbe(CorpusProbeKind.SYMBOL_CLASS, "sample.Target", ZERO_HASH), new CorpusProbe(CorpusProbeKind.SYMBOL_FIELD, "sample.Target#value", ZERO_HASH), new CorpusProbe(CorpusProbeKind.SYMBOL_METHOD, "sample.Target#run", ZERO_HASH), new CorpusProbe(CorpusProbeKind.CALLERS, "sample.Target#helper", ZERO_HASH), new CorpusProbe(CorpusProbeKind.CALLEES, "sample.Target#run", ZERO_HASH));
    }

    private static NodeCorpusBaseline baseline(String sourceHash, String jarHash, CorpusIndexCounts indexCounts, CorpusCallgraphCounts callgraphCounts, List<CorpusProbe> probes) {
        return new NodeCorpusBaseline(1, VERSION, sourceHash, jarHash, NODE_CALLGRAPH_HASH, NODE_CALLGRAPH, ORACLE, indexCounts, callgraphCounts, probes);
    }

    @Test
    void corpusProbeTemplateNamesBothUnqualifiedVersionsWithoutInventingHashes() throws Exception {
        String template;
        try (var input = getClass().getResourceAsStream("/contracts/indexer/corpus-probes.json")) {
            template = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(template.contains("\"status\": \"unqualified-template\""));
        assertTrue(template.contains("\"value\": \"1.21.11\""));
        assertTrue(template.contains("\"value\": \"26.1\""));
        assertTrue(template.contains("intentionally fabricates none"));
        assertFalse(template.contains(ZERO_HASH));
    }

    private IndexSummary buildIndex(Fixture fixture, Path database, int workers) throws Exception {
        return new SourceIndexer().build(new IndexRequest(VERSION, List.of(new SourceRoot(SourceNamespace.MINECRAFT, Optional.empty(), fixture.sources())), fixture.jar(), List.of(), database, workers, (_, _, _) -> {
        }, Cancellation.none()));
    }

    private Fixture fixture() throws Exception {
        return fixture(temporaryDirectory);
    }

    private Fixture fixture(Path root) throws Exception {
        Path sources = root.resolve("fixture-sources");
        Path packageRoot = Files.createDirectories(sources.resolve("sample"));
        Files.writeString(sources.resolve("module-info.java"), "module sample.fixture { exports sample; }\n", StandardCharsets.UTF_8);
        Files.writeString(packageRoot.resolve("package-info.java"), "@Deprecated package sample;\n", StandardCharsets.UTF_8);
        Files.writeString(packageRoot.resolve("Target.java"), "package sample; public class Target { int value; public void run() { helper(); } public void helper() {} }\n", StandardCharsets.UTF_8);
        Path classes = root.resolve("fixture-classes");
        Files.createDirectories(classes);
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), sources.resolve("module-info.java").toString(), packageRoot.resolve("package-info.java").toString(), packageRoot.resolve("Target.java").toString());
        assertEquals(0, result);
        Path jar = root.resolve("fixture.jar");
        try (OutputStream output = Files.newOutputStream(jar); JarOutputStream archive = new JarOutputStream(output);
             var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                JarEntry entry = new JarEntry(classes.relativize(file).toString().replace('\\', '/'));
                entry.setTime(0);
                archive.putNextEntry(entry);
                Files.copy(file, archive);
                archive.closeEntry();
            }
        }
        return new Fixture(sources, jar);
    }

    private static String[] arguments(Path source, Path jar, Path baseline, Path expectation, Path output, int workers) {
        return arguments(source, jar, baseline, expectation, output, output.resolveSibling("production-cache"), workers);
    }

    private static String[] arguments(Path source, Path jar, Path baseline, Path expectation, Path output, Path productionCache, int workers) {
        return new String[]{"--minecraft-version", VERSION.value(), "--source-root", source.toString(), "--remapped-jar", jar.toString(), "--node-baseline", baseline.toString(), "--expectation", expectation.toString(), "--output-root", output.toString(), "--production-cache-root", productionCache.toString(), "--workers", Integer.toString(workers), "--classpath-manifest", ClasspathFixtures.empty(jar.getParent().resolve("dependencies")).toString()};
    }

    private static CorpusQualificationReport readReport(Path output) throws IOException {
        return McpJsonDefaults.getMapper().readValue(Files.readAllBytes(output.resolve(CorpusQualificationMain.REPORT_NAME)), CorpusQualificationReport.class);
    }

    private record Fixture(Path sources, Path jar) {
    }
}

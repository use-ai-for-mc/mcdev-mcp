package dev.mcdevmcp.benchmark;

import dev.mcdevmcp.analysis.callgraph.CallgraphRequest;
import dev.mcdevmcp.analysis.callgraph.CallgraphScanner;
import dev.mcdevmcp.analysis.callgraph.CallgraphSummary;
import dev.mcdevmcp.analysis.index.IndexBuildEvidence;
import dev.mcdevmcp.analysis.index.IndexRequest;
import dev.mcdevmcp.analysis.index.IndexSummary;
import dev.mcdevmcp.analysis.index.SourceIndexer;
import dev.mcdevmcp.analysis.index.SourceRoot;
import dev.mcdevmcp.storage.callgraph.CallgraphBundleValidator;
import dev.mcdevmcp.storage.callgraph.CallgraphManifest;
import dev.mcdevmcp.storage.callgraph.CallgraphPointer;
import dev.mcdevmcp.storage.callgraph.CallgraphRepository;
import dev.mcdevmcp.storage.h2.SymbolRepository;
import dev.mcdevmcp.storage.model.ClassSymbol;
import dev.mcdevmcp.storage.model.FieldSymbol;
import dev.mcdevmcp.storage.model.MethodReference;
import dev.mcdevmcp.storage.model.MethodSymbol;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.SourceNamespace;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;

import java.io.IOException;
import java.io.Serial;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds and validates complete immutable corpus evidence against reviewed typed expectations.
 */
public final class CorpusQualificationMain {
    static final String REPORT_NAME = "corpus-qualification.json";
    private static final Set<String> SUPPORTED_VERSIONS = Set.of("1.21.11", "26.1");
    private static final List<String> INDEX_METRICS = List.of("packages", "types", "fields", "methods", "parameters");
    private static final List<String> CALLGRAPH_METRICS = List.of("classes", "methods", "edges");
    private static final List<String> CALLGRAPH_FILES = List.of("manifest.json", "callers.jsonl", "callers.index.jsonl", "callees.jsonl", "callees.index.jsonl");

    private CorpusQualificationMain() {
    }

    //noinspection RedundantModifiers
    public static void main(String[] arguments) throws Exception {
        Arguments parsed = Arguments.parse(arguments);
        try {
            qualify(parsed);
        } catch (OutOfMemoryError failure) {
            writeOutOfMemoryReport(parsed, failure);
            throw new IllegalStateException("Corpus qualification exhausted the configured Java heap", failure);
        }
    }

    private static void qualify(Arguments arguments) throws Exception {
        qualify(arguments, CorpusQualificationMain::peakRssBytes);
    }

    static void qualify(Arguments arguments, RssProbe rssProbe) throws Exception {
        Objects.requireNonNull(rssProbe, "rssProbe");
        CorpusExpectation expectation = McpJsonDefaults.getMapper().readValue(Files.readAllBytes(arguments.expectation()), CorpusExpectation.class);
        NodeCorpusBaseline baseline = McpJsonDefaults.getMapper().readValue(Files.readAllBytes(arguments.nodeBaseline()), NodeCorpusBaseline.class);
        InputSnapshot inputs = validateInputs(arguments, expectation, baseline);
        Files.createDirectories(arguments.outputRoot());
        Path database = arguments.outputRoot().resolve("symbols.mv.db");
        Path callgraphBundle = arguments.outputRoot().resolve("callgraph");

        CorpusQualificationReport report;
        try (MemorySampler memory = new MemorySampler()) {
            IndexSummary index = new SourceIndexer().build(new IndexRequest(arguments.minecraftVersion(), List.of(new SourceRoot(SourceNamespace.MINECRAFT, Optional.empty(), arguments.sourceRoot())), arguments.remappedJar(), inputs.classpath().paths(), database, arguments.workers(), (_, _, _) -> {
            }, Cancellation.none()));
            CallgraphSummary graph = new CallgraphScanner().scan(new CallgraphRequest(arguments.minecraftVersion(), arguments.remappedJar(), callgraphBundle, arguments.workers(), (_, _, _) -> {
            }, Cancellation.none()));
            memory.sample();

            IndexBuildEvidence evidence = index.evidence();
            CompilationUnitCounts unitCounts = unitCounts(evidence);
            CorpusIndexCounts indexCounts = indexCounts(index);
            CorpusCallgraphCounts callgraphCounts = callgraphCounts(graph);
            String symbolHash = logicalTableHash(database);
            CallgraphHashes callgraphHashes = callgraphHashes(callgraphBundle);
            ProbeEvaluation probeEvaluation = evaluateProbes(database, callgraphBundle, arguments.minecraftVersion(), expectation.probes(), baseline.probes());
            List<String> failures = new ArrayList<>(probeEvaluation.failures());
            validateAccounting(failures, evidence);
            compare(failures, "minecraftVersion", expectation.minecraftVersion(), arguments.minecraftVersion());
            compare(failures, "sourceLogicalHash", expectation.sourceLogicalHash(), inputs.sourceHash());
            compare(failures, "remappedJarSha256", expectation.remappedJarSha256(), inputs.jarHash());
            compare(failures, "compilationUnits", expectation.compilationUnits(), unitCounts);
            compare(failures, "indexCounts", expectation.indexCounts(), indexCounts);
            compare(failures, "callgraphCounts", expectation.callgraphCounts(), callgraphCounts);
            compare(failures, "symbolLogicalHash", expectation.symbolLogicalHash(), symbolHash);
            compare(failures, "callgraphLogicalIdentity", expectation.callgraphLogicalIdentity(), callgraphHashes.identity());
            compare(failures, "callgraphLogicalHash", expectation.callgraphLogicalHash(), callgraphHashes.logicalHash());
            compare(failures, "diagnostics", expectation.diagnostics(), evidence.diagnostics());
            compareExpectedProbes(failures, expectation.probes(), probeEvaluation.probes());
            List<ReviewedNodeDifference> appliedDifferences = validateNodeDeltas(failures, baseline, indexCounts, callgraphCounts, probeEvaluation.probes(), expectation.reviewedNodeDifferences());
            validateInputsUnchanged(failures, arguments, inputs);
            long postGcHeap = postGcLiveHeapBytes();
            memory.sample();
            report = new CorpusQualificationReport(2, failures.isEmpty(), failures, arguments.minecraftVersion(), arguments.workers(), inputs.sourceHash(), inputs.jarHash(), symbolHash, callgraphHashes.identity(), callgraphHashes.logicalHash(), unitCounts, evidence.discoveredCompilationUnits(), evidence.parsedCompilationUnits(), evidence.typedCompilationUnits(), evidence.typeFreeCompilationUnits(), evidence.diagnostics(), indexCounts, callgraphCounts, probeEvaluation.probes(), appliedDifferences, memory.peakLiveHeapBytes(), postGcHeap, rssProbe.peakRssBytes(), inputs.classpath().evidence(), System.getProperty("os.name"), ProcessPeakMemory.metric());
        } catch (Exception | Error failure) {
            inputs.classpath().verifyAfterFailure(List.of(arguments.outputRoot()), failure);
            throw failure;
        }

        writeReport(arguments.outputRoot(), report);
        if (!report.qualified()) {
            throw new IllegalStateException("Corpus qualification failed: " + String.join("; ", report.failures()));
        }
    }

    private static InputSnapshot validateInputs(Arguments arguments, CorpusExpectation expectation, NodeCorpusBaseline baseline) throws Exception {
        if (expectation.schemaVersion() != 2) {
            throw new IllegalArgumentException("Unsupported corpus expectation schema " + expectation.schemaVersion());
        }
        if (baseline.schemaVersion() != 1) {
            throw new IllegalArgumentException("Unsupported Node corpus baseline schema " + baseline.schemaVersion());
        }
        if (!SUPPORTED_VERSIONS.contains(arguments.minecraftVersion().value())) {
            throw new IllegalArgumentException("Corpus qualification supports only Minecraft 1.21.11 and 26.1");
        }
        if (!expectation.minecraftVersion().equals(arguments.minecraftVersion())) {
            throw new IllegalArgumentException("Corpus expectation Minecraft version does not match the request");
        }
        if (!baseline.minecraftVersion().equals(arguments.minecraftVersion())) {
            throw new IllegalArgumentException("Node corpus baseline Minecraft version does not match the request");
        }
        if (!baseline.oracleIdentity().equals(expectation.nodeOracleIdentity())) {
            throw new IllegalArgumentException("Node corpus baseline does not match the reviewed pinned Node oracle identity");
        }
        if (!baseline.nodeCallgraphSha256().equals(expectation.nodeCallgraphSha256())) {
            throw new IllegalArgumentException("Node corpus baseline does not match the reviewed precomputed callgraph SHA-256");
        }
        if (!baseline.nodeCallgraphIdentity().equals(expectation.nodeCallgraphIdentity())) {
            throw new IllegalArgumentException("Node corpus baseline does not match the reviewed callgraph generator and schema identity");
        }
        require(arguments.sourceRoot(), true, "source root");
        require(arguments.remappedJar(), false, "remapped JAR");
        require(arguments.nodeBaseline(), false, "Node baseline");
        require(arguments.expectation(), false, "expectation");
        requireEmptyOrMissingOutput(arguments.outputRoot());
        validateProbeDefinitions(expectation.probes(), baseline.probes());

        List<Path> immutableInputs = List.of(arguments.sourceRoot(), arguments.remappedJar(), arguments.nodeBaseline(), arguments.expectation());
        Path output = canonicalize(arguments.outputRoot());
        for (Path input : immutableInputs) {
            rejectOverlap(output, canonicalize(input), "Corpus output must not overlap immutable input " + input);
        }
        for (int first = 0; first < immutableInputs.size(); first++) {
            for (int second = first + 1; second < immutableInputs.size(); second++) {
                rejectOverlap(canonicalize(immutableInputs.get(first)), canonicalize(immutableInputs.get(second)), "Corpus immutable inputs must not overlap: " + immutableInputs.get(first) + " and " + immutableInputs.get(second));
            }
        }
        Path productionCache = canonicalize(arguments.productionCacheRoot());
        rejectOverlap(output, productionCache, "Corpus output must not overlap the production cache");
        VerifiedCorpusClasspath classpath = CorpusClasspathManifest.verify(arguments.classpathManifest(), arguments.minecraftVersion(), List.of(arguments.outputRoot()));
        if (!classpath.evidence().identity().equals(expectation.classpathIdentity()) || !classpath.evidence().manifestSha256().equals(expectation.classpathManifestSha256())) {
            throw new IllegalArgumentException("Corpus expectation classpath identity or manifest SHA-256 mismatch");
        }
        InputSnapshot snapshot = new InputSnapshot(AnalysisBenchmarkMain.sha256Tree(arguments.sourceRoot()), AnalysisBenchmarkMain.sha256(arguments.remappedJar()), AnalysisBenchmarkMain.sha256(arguments.nodeBaseline()), AnalysisBenchmarkMain.sha256(arguments.expectation()), classpath);
        if (!baseline.sourceLogicalHash().equals(snapshot.sourceHash())) {
            throw new IllegalArgumentException("Node corpus baseline source logical hash does not match the immutable source input");
        }
        if (!baseline.remappedJarSha256().equals(snapshot.jarHash())) {
            throw new IllegalArgumentException("Node corpus baseline remapped-JAR hash does not match the immutable JAR input");
        }
        return snapshot;
    }

    private static void requireEmptyOrMissingOutput(Path path) throws IOException {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Corpus output root must be a real directory: " + path);
        }
        try (var children = Files.list(path)) {
            if (children.findAny().isPresent()) {
                throw new IOException("Corpus output root must be empty: " + path);
            }
        }
    }

    static void validateProbeDefinitions(List<CorpusProbe> expectation, List<CorpusProbe> baseline) {
        Set<ProbeKey> expectedKeys = probeKeys(expectation, "expectation");
        Set<ProbeKey> baselineKeys = probeKeys(baseline, "Node baseline");
        if (!expectedKeys.equals(baselineKeys)) {
            throw new IllegalArgumentException("Corpus expectation and Node baseline must define the same probe kind/key set");
        }
        EnumSet<CorpusProbeKind> missingKinds = EnumSet.allOf(CorpusProbeKind.class);
        expectation.forEach(probe -> missingKinds.remove(probe.kind()));
        if (!missingKinds.isEmpty()) {
            throw new IllegalArgumentException("Corpus probes require representative coverage of every probe kind; missing " + missingKinds);
        }
    }

    private static Set<ProbeKey> probeKeys(List<CorpusProbe> probes, String label) {
        Set<ProbeKey> keys = new HashSet<>();
        for (CorpusProbe probe : probes) {
            if (!keys.add(new ProbeKey(probe.kind(), probe.key()))) {
                throw new IllegalArgumentException("Duplicate " + label + " probe " + probe.kind() + ":" + probe.key());
            }
        }
        return Set.copyOf(keys);
    }

    private static Path canonicalize(Path path) throws IOException {
        Path existing = path.toAbsolutePath().normalize();
        List<Path> missing = new ArrayList<>();
        while (Files.notExists(existing, LinkOption.NOFOLLOW_LINKS)) {
            Path name = existing.getFileName();
            if (name == null || existing.getParent() == null) {
                throw new IOException("Unable to canonicalize path " + path);
            }
            missing.add(name);
            existing = existing.getParent();
        }
        Path canonical = existing.toRealPath();
        for (Path name : missing.reversed()) {
            canonical = canonical.resolve(name);
        }
        return canonical.normalize();
    }

    private static void rejectOverlap(Path first, Path second, String message) {
        if (first.equals(second) || first.startsWith(second) || second.startsWith(first)) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void validateInputsUnchanged(List<String> failures, Arguments arguments, InputSnapshot before) throws Exception {
        try {
            before.classpath().verifyUnchanged(List.of(arguments.outputRoot()));
        } catch (IOException | IllegalArgumentException failure) {
            failures.add("classpath input remained immutable: " + failure.getMessage());
        }
        compare(failures, "source input remained immutable", before.sourceHash(), AnalysisBenchmarkMain.sha256Tree(arguments.sourceRoot()));
        compare(failures, "remapped JAR remained immutable", before.jarHash(), AnalysisBenchmarkMain.sha256(arguments.remappedJar()));
        compare(failures, "Node baseline remained immutable", before.nodeBaselineHash(), AnalysisBenchmarkMain.sha256(arguments.nodeBaseline()));
        compare(failures, "expectation remained immutable", before.expectationHash(), AnalysisBenchmarkMain.sha256(arguments.expectation()));
    }

    static CompilationUnitCounts unitCounts(IndexBuildEvidence evidence) {
        return new CompilationUnitCounts(evidence.discoveredCompilationUnits().size(), evidence.parsedCompilationUnits().size(), evidence.typedCompilationUnits().size(), evidence.typeFreeCompilationUnits().size());
    }

    static void validateAccounting(List<String> failures, IndexBuildEvidence evidence) {
        Set<String> discovered = new HashSet<>(evidence.discoveredCompilationUnits());
        Set<String> parsed = new HashSet<>(evidence.parsedCompilationUnits());
        Set<String> typed = new HashSet<>(evidence.typedCompilationUnits());
        Set<String> typeFree = new HashSet<>(evidence.typeFreeCompilationUnits());
        if (discovered.size() != evidence.discoveredCompilationUnits().size() || parsed.size() != evidence.parsedCompilationUnits().size() || typed.size() != evidence.typedCompilationUnits().size() || typeFree.size() != evidence.typeFreeCompilationUnits().size()) {
            failures.add("compilation-unit accounting contains duplicate paths");
        }
        if (!discovered.equals(parsed)) {
            failures.add("discovered units are not exactly parsed compilation units");
        }
        Set<String> overlap = new HashSet<>(typed);
        overlap.retainAll(typeFree);
        if (!overlap.isEmpty()) {
            failures.add("typed and type-free compilation units overlap");
        }
        Set<String> accounted = new HashSet<>(typed);
        accounted.addAll(typeFree);
        if (!accounted.equals(parsed)) {
            failures.add("parsed units are not exactly accounted for by typed and type-free units");
        }
    }

    private static CorpusIndexCounts indexCounts(IndexSummary index) {
        return new CorpusIndexCounts(index.packages(), index.types(), index.fields(), index.methods(), index.parameters());
    }

    private static CorpusCallgraphCounts callgraphCounts(CallgraphSummary graph) {
        return new CorpusCallgraphCounts(graph.classes(), graph.methods(), graph.edges());
    }

    //noinspection SqlNoDataSourceInspection,SqlResolve
    static String logicalTableHash(Path database) throws Exception {
        return new SymbolRepository(database).query(connection -> {
            LogicalDigest digest = new LogicalDigest();
            hashRows(connection, digest, "metadata", "SELECT singleton,schema_version,minecraft_version,remapped_jar_sha256 FROM metadata ORDER BY singleton");
            hashRows(connection, digest, "packages", "SELECT id,source_namespace,fabric_api_version,fabric_api_version_key,name FROM packages ORDER BY id");
            hashRows(connection, digest, "types", "SELECT id,package_id,source_namespace,fabric_api_version,fabric_api_version_key,binary_name,simple_name,kind,superclass_binary_name,source_path,start_offset,end_offset,start_line,end_line FROM types ORDER BY id");
            hashRows(connection, digest, "type_interfaces", "SELECT type_id,ordinal,interface_binary_name FROM type_interfaces ORDER BY type_id,ordinal");
            hashRows(connection, digest, "fields", "SELECT id,type_id,ordinal,name,type,modifiers,start_offset,end_offset,start_line,end_line FROM fields ORDER BY id");
            hashRows(connection, digest, "methods", "SELECT id,type_id,ordinal,name,descriptor,return_type,modifiers,constructor,start_offset,end_offset,start_line,end_line FROM methods ORDER BY id");
            hashRows(connection, digest, "parameters", "SELECT id,method_id,ordinal,name,type,varargs,start_offset,end_offset,start_line,end_line FROM parameters ORDER BY id");
            return digest.finish();
        });
    }

    private static void hashRows(Connection connection, LogicalDigest digest, String table, String query) throws Exception {
        digest.value(table);
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(query)) {
            int columns = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                digest.row();
                for (int column = 1; column <= columns; column++) {
                    digest.value(rows.getObject(column));
                }
            }
        }
    }

    static CallgraphHashes callgraphHashes(Path bundle) throws Exception {
        CallgraphBundleValidator.validate(bundle);
        CallgraphPointer pointer = McpJsonDefaults.getMapper().readValue(Files.readAllBytes(bundle.resolve("current.json")), CallgraphPointer.class);
        CorpusExpectation.requireSha256(pointer.generation(), "callgraph generation");
        Path generation = bundle.resolve("generations").resolve(pointer.generation()).normalize();
        if (!generation.getParent().equals(bundle.resolve("generations").normalize())) {
            throw new IOException("Unsafe callgraph generation path");
        }
        CallgraphManifest manifest = McpJsonDefaults.getMapper().readValue(Files.readAllBytes(generation.resolve("manifest.json")), CallgraphManifest.class);
        if (!manifest.minecraftVersion().equals(manifest.minecraftVersion().strip())) {
            throw new IOException("Callgraph manifest Minecraft version is not canonical");
        }
        LogicalDigest digest = new LogicalDigest();
        for (String fileName : CALLGRAPH_FILES) {
            Path file = generation.resolve(fileName);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
                throw new IOException("Missing immutable callgraph artifact " + fileName);
            }
            digest.value(fileName);
            digest.value(Files.readAllBytes(file));
        }
        return new CallgraphHashes(pointer.generation(), digest.finish());
    }

    private static ProbeEvaluation evaluateProbes(Path database, Path callgraphBundle, MinecraftVersion version, List<CorpusProbe> expected, List<CorpusProbe> node) throws Exception {
        Map<ProbeKey, CorpusProbe> definitions = new LinkedHashMap<>();
        expected.forEach(probe -> definitions.put(new ProbeKey(probe.kind(), probe.key()), probe));
        node.forEach(probe -> definitions.putIfAbsent(new ProbeKey(probe.kind(), probe.key()), probe));
        List<Map.Entry<ProbeKey, CorpusProbe>> ordered = definitions.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
        SymbolRepository symbols = new SymbolRepository(database);
        CallgraphRepository callgraph = new CallgraphRepository(callgraphBundle, version);
        List<CorpusProbe> actual = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Map.Entry<ProbeKey, CorpusProbe> entry : ordered) {
            ProbeResult result = evaluateProbe(entry.getKey(), symbols, callgraph);
            actual.add(new CorpusProbe(entry.getKey().kind(), entry.getKey().key(), result.signature()));
            if (!result.present()) {
                failures.add("probe returned no output: " + entry.getKey().kind() + ":" + entry.getKey().key());
            }
        }
        return new ProbeEvaluation(List.copyOf(actual), List.copyOf(failures));
    }

    private static ProbeResult evaluateProbe(ProbeKey probe, SymbolRepository symbols, CallgraphRepository callgraph) throws Exception {
        return switch (probe.kind()) {
            case SYMBOL_CLASS -> {
                ClassSymbol symbol = symbols.classByName(probe.key());
                yield new ProbeResult(CorpusProbeProjectionV1.classProbe(probe.key(), symbol).signature(), symbol != null);
            }
            case SYMBOL_FIELD -> {
                MemberKey key = MemberKey.parse(probe.key());
                ClassSymbol owner = symbols.classByName(key.className());
                List<FieldSymbol> fields = owner == null ? List.of() : symbols.fields(owner.id()).stream().filter(field -> field.name().equals(key.member())).toList();
                yield new ProbeResult(CorpusProbeProjectionV1.fieldProbe(probe.key(), fields).signature(), !fields.isEmpty());
            }
            case SYMBOL_METHOD -> {
                MemberKey key = MemberKey.parse(probe.key());
                ClassSymbol owner = symbols.classByName(key.className());
                List<MethodSymbol> methods = owner == null ? List.of() : symbols.methods(owner.id()).stream().filter(method -> method.name().equals(key.member())).toList();
                yield new ProbeResult(CorpusProbeProjectionV1.methodProbe(probe.key(), methods, method -> {
                    try {
                        return symbols.parameters(method.id());
                    } catch (IOException | java.sql.SQLException exception) {
                        throw new ProbeParameterException(exception);
                    }
                }).signature(), !methods.isEmpty());
            }
            case CALLERS, CALLEES -> {
                MemberKey key = MemberKey.parse(probe.key());
                int limitPlusOne = CorpusProbeProjectionV1.PROBE_REFERENCE_LIMIT + 1;
                // The frozen Node oracle applies SQL LIMIT 100. Reading one extra row detects
                // truncation so qualification compares only complete result sets.
                List<MethodReference> references = probe.kind() == CorpusProbeKind.CALLERS ? callgraph.callers(key.className(), key.member(), limitPlusOne) : callgraph.callees(key.className(), key.member(), limitPlusOne);
                yield new ProbeResult(CorpusProbeProjectionV1.referenceProbe(probe.kind(), probe.key(), references).signature(), !references.isEmpty());
            }
        };
    }

    static void compareExpectedProbes(List<String> failures, List<CorpusProbe> expected, List<CorpusProbe> actual) {
        Map<ProbeKey, String> actualSignatures = probeSignatures(actual);
        for (CorpusProbe probe : expected) {
            String actualSignature = actualSignatures.get(new ProbeKey(probe.kind(), probe.key()));
            compare(failures, "probe " + probe.kind() + ":" + probe.key(), probe.signature(), actualSignature);
        }
    }

    static List<ReviewedNodeDifference> validateNodeDeltas(List<String> failures, NodeCorpusBaseline baseline, CorpusIndexCounts index, CorpusCallgraphCounts callgraph, List<CorpusProbe> actualProbes, List<ReviewedNodeDifference> reviewed) {
        Map<DifferenceKey, ReviewedNodeDifference> approvals = new HashMap<>();
        for (ReviewedNodeDifference difference : reviewed) {
            DifferenceKey key = new DifferenceKey(difference.kind(), difference.key());
            if (approvals.putIfAbsent(key, difference) != null) {
                failures.add("duplicate reviewed Node difference: " + key.display());
            }
        }
        Set<DifferenceKey> deltas = new HashSet<>();
        compareMetric(deltas, ReviewedNodeDifferenceKind.INDEX_METRIC, "packages", baseline.indexCounts().packages(), index.packages());
        compareMetric(deltas, ReviewedNodeDifferenceKind.INDEX_METRIC, "types", baseline.indexCounts().types(), index.types());
        compareMetric(deltas, ReviewedNodeDifferenceKind.INDEX_METRIC, "fields", baseline.indexCounts().fields(), index.fields());
        compareMetric(deltas, ReviewedNodeDifferenceKind.INDEX_METRIC, "methods", baseline.indexCounts().methods(), index.methods());
        compareMetric(deltas, ReviewedNodeDifferenceKind.INDEX_METRIC, "parameters", baseline.indexCounts().parameters(), index.parameters());
        compareMetric(deltas, ReviewedNodeDifferenceKind.CALLGRAPH_METRIC, "classes", baseline.callgraphCounts().classes(), callgraph.classes());
        compareMetric(deltas, ReviewedNodeDifferenceKind.CALLGRAPH_METRIC, "methods", baseline.callgraphCounts().methods(), callgraph.methods());
        compareMetric(deltas, ReviewedNodeDifferenceKind.CALLGRAPH_METRIC, "edges", baseline.callgraphCounts().edges(), callgraph.edges());
        Map<ProbeKey, String> actualSignatures = probeSignatures(actualProbes);
        for (CorpusProbe probe : baseline.probes()) {
            if (!Objects.equals(probe.signature(), actualSignatures.get(new ProbeKey(probe.kind(), probe.key())))) {
                deltas.add(new DifferenceKey(ReviewedNodeDifferenceKind.PROBE, probeKey(probe)));
            }
        }
        for (DifferenceKey delta : deltas.stream().sorted().toList()) {
            if (!approvals.containsKey(delta)) {
                failures.add("unreviewed Node difference: " + delta.display());
            }
        }
        for (DifferenceKey approval : approvals.keySet().stream().sorted().toList()) {
            if (!validDifferenceKey(approval)) {
                failures.add("invalid reviewed Node difference key: " + approval.display());
            }
            else if (!deltas.contains(approval)) {
                failures.add("unused reviewed Node difference: " + approval.display());
            }
        }
        return deltas.stream().sorted().map(approvals::get).filter(Objects::nonNull).toList();
    }

    private static boolean validDifferenceKey(DifferenceKey key) {
        return switch (key.kind()) {
            case INDEX_METRIC -> INDEX_METRICS.contains(key.key());
            case CALLGRAPH_METRIC -> CALLGRAPH_METRICS.contains(key.key());
            case PROBE -> {
                int separator = key.key().indexOf(':');
                if (separator < 1 || separator == key.key().length() - 1) {
                    yield false;
                }
                try {
                    CorpusProbeKind.valueOf(key.key().substring(0, separator));
                    yield true;
                } catch (IllegalArgumentException exception) {
                    yield false;
                }
            }
        };
    }

    private static void compareMetric(Set<DifferenceKey> deltas, ReviewedNodeDifferenceKind kind, String key, long node, long java) {
        if (node != java) {
            deltas.add(new DifferenceKey(kind, key));
        }
    }

    private static Map<ProbeKey, String> probeSignatures(List<CorpusProbe> probes) {
        Map<ProbeKey, String> signatures = new HashMap<>();
        probes.forEach(probe -> signatures.put(new ProbeKey(probe.kind(), probe.key()), probe.signature()));
        return Map.copyOf(signatures);
    }

    private static String probeKey(CorpusProbe probe) {
        return probe.kind().name() + ":" + probe.key();
    }

    static void compare(List<String> failures, String label, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            failures.add(label + " mismatch");
        }
    }

    private static void require(Path path, boolean directory, String label) throws IOException {
        boolean correctKind = directory ? Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        if (!correctKind || Files.isSymbolicLink(path)) {
            throw new IOException("Immutable " + label + " is unavailable: " + path);
        }
    }

    private static long postGcLiveHeapBytes() throws InterruptedException {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long collections = ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(collector -> Math.max(0, collector.getCollectionCount())).sum();
        System.gc();
        for (int attempt = 0; attempt < 50; attempt++) {
            long current = ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(collector -> Math.max(0, collector.getCollectionCount())).sum();
            if (current > collections) {
                break;
            }
            Thread.sleep(10);
        }
        return memory.getHeapMemoryUsage().getUsed();
    }

    static long peakRssBytes() throws IOException {
        return ProcessPeakMemory.currentPeakBytes();
    }

    private static void writeReport(Path outputRoot, CorpusQualificationReport report) throws IOException {
        Files.createDirectories(outputRoot);
        Files.write(outputRoot.resolve(REPORT_NAME), McpJsonDefaults.getMapper().writeValueAsBytes(report));
    }

    static void writeOutOfMemoryReport(Arguments arguments, OutOfMemoryError failure) {
        try {
            System.gc();
            String zero = "0".repeat(64);
            long liveHeap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            CorpusClasspathEvidence classpath = CorpusClasspathManifest.verify(arguments.classpathManifest(), arguments.minecraftVersion(), List.of(arguments.outputRoot())).evidence();
            CorpusQualificationReport report = new CorpusQualificationReport(2, false, List.of("Java heap exhausted: " + failure.getClass().getSimpleName()), arguments.minecraftVersion(), arguments.workers(), zero, zero, zero, zero, zero, new CompilationUnitCounts(0, 0, 0, 0), List.of(), List.of(), List.of(), List.of(), List.of(), new CorpusIndexCounts(0, 0, 0, 0, 0), new CorpusCallgraphCounts(0, 0, 0), List.of(), List.of(), liveHeap, liveHeap, 0, classpath, System.getProperty("os.name"), ProcessMemoryMetric.UNAVAILABLE);
            writeReport(arguments.outputRoot(), report);
        } catch (Throwable reportFailure) {
            failure.addSuppressed(reportFailure);
        }
    }

    static final class LogicalDigest {
        private final MessageDigest digest;

        LogicalDigest() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException exception) {
                throw new AssertionError(exception);
            }
        }

        void row() {
            digest.update((byte) 1);
        }

        void value(Object value) {
            if (value == null) {
                digest.update((byte) 0);
                return;
            }
            digest.update((byte) 2);
            byte[] bytes = value instanceof byte[] byteValue ? byteValue : String.valueOf(value).getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }

        String finish() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    record Arguments(MinecraftVersion minecraftVersion, Path sourceRoot, Path remappedJar, Path nodeBaseline, Path expectation, Path outputRoot, Path productionCacheRoot, int workers, Path classpathManifest) {
        static Arguments parse(String[] values) {
            Map<String, String> options = options(values);
            Set<String> expected = Set.of("--minecraft-version", "--source-root", "--remapped-jar", "--node-baseline", "--expectation", "--output-root", "--production-cache-root", "--workers", "--classpath-manifest");
            if (!options.keySet().equals(expected)) {
                throw new IllegalArgumentException("Expected exactly corpus qualification arguments " + expected);
            }
            int workers = Integer.parseInt(required(options, "--workers"));
            if (workers < 1) {
                throw new IllegalArgumentException("--workers must be positive");
            }
            MinecraftVersion version = new MinecraftVersion(required(options, "--minecraft-version"));
            if (!SUPPORTED_VERSIONS.contains(version.value())) {
                throw new IllegalArgumentException("Corpus qualification supports only Minecraft 1.21.11 and 26.1");
            }
            return new Arguments(version, path(options, "--source-root"), path(options, "--remapped-jar"), path(options, "--node-baseline"), path(options, "--expectation"), path(options, "--output-root"), path(options, "--production-cache-root"), workers, path(options, "--classpath-manifest"));
        }

        private static Map<String, String> options(String[] values) {
            Map<String, String> options = new HashMap<>();
            for (int index = 0; index < values.length; index += 2) {
                if (index + 1 >= values.length || !values[index].startsWith("--") || options.put(values[index], values[index + 1]) != null) {
                    throw new IllegalArgumentException("Expected unique --name value corpus qualification arguments");
                }
            }
            return options;
        }

        private static Path path(Map<String, String> options, String name) {
            return Path.of(required(options, name)).toAbsolutePath().normalize();
        }

        private static String required(Map<String, String> options, String name) {
            String value = options.get(name);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing required corpus qualification argument " + name);
            }
            return value;
        }
    }

    record CallgraphHashes(String identity, String logicalHash) {
    }

    @FunctionalInterface
    interface RssProbe {
        long peakRssBytes() throws IOException;
    }

    private record InputSnapshot(String sourceHash, String jarHash, String nodeBaselineHash, String expectationHash, VerifiedCorpusClasspath classpath) {
    }

    private record ProbeKey(CorpusProbeKind kind, String key) implements Comparable<ProbeKey> {
        private ProbeKey {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(key, "key");
        }

        @Override
        public int compareTo(ProbeKey other) {
            int kindOrder = kind.compareTo(other.kind);
            return kindOrder == 0 ? key.compareTo(other.key) : kindOrder;
        }
    }

    private record MemberKey(String className, String member) {
        private static MemberKey parse(String value) {
            int separator = value.lastIndexOf('#');
            if (separator < 1 || separator == value.length() - 1) {
                throw new IllegalArgumentException("Probe key must use binary.Class#member syntax: " + value);
            }
            String member = value.substring(separator + 1);
            return new MemberKey(value.substring(0, separator), member);
        }
    }

    private record ProbeResult(String signature, boolean present) {
    }

    private record ProbeEvaluation(List<CorpusProbe> probes, List<String> failures) {
    }

    private static final class ProbeParameterException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        private ProbeParameterException(Exception cause) {
            super(cause);
        }
    }

    private record DifferenceKey(ReviewedNodeDifferenceKind kind, String key) implements Comparable<DifferenceKey> {
        private String display() {
            return kind + ":" + key;
        }

        @Override
        public int compareTo(DifferenceKey other) {
            int kindOrder = kind.compareTo(other.kind);
            return kindOrder == 0 ? key.compareTo(other.key) : kindOrder;
        }
    }

    private static final class MemorySampler implements AutoCloseable {
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicLong peak = new AtomicLong();
        private final Thread sampler;

        private MemorySampler() {
            sample();
            sampler = Thread.ofVirtual().name("corpus-memory-sampler").start(() -> {
                while (running.get()) {
                    sample();
                    try {
                        //noinspection BusyWait
                        Thread.sleep(10);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
        }

        private void sample() {
            long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            peak.accumulateAndGet(used, Math::max);
        }

        private long peakLiveHeapBytes() {
            return peak.get();
        }

        @Override
        public void close() {
            running.set(false);
            sampler.interrupt();
            try {
                sampler.join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            sample();
        }
    }
}

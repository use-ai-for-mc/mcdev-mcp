package dev.mcdevmcp.benchmark;

import dev.mcdevmcp.storage.h2.SymbolRepository;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ExternalClasspathHarnessTest {
    private static final String ZERO = "0".repeat(64);
    @TempDir
    Path root;

    @Test
    void qualificationResolvesExternalFieldMethodAndParameterTypes() throws Exception {
        Fixture fixture = fixture();
        var evidence = CorpusClasspathManifest.verify(fixture.manifest(), ClasspathFixtures.VERSION, List.of()).evidence();
        Path baseline = root.resolve("baseline.json");
        Path expectation = root.resolve("expectation.json");
        List<CorpusProbe> probes = List.of(new CorpusProbe(CorpusProbeKind.SYMBOL_CLASS, "sample.Target", ZERO), new CorpusProbe(CorpusProbeKind.SYMBOL_FIELD, "sample.Target#value", ZERO), new CorpusProbe(CorpusProbeKind.SYMBOL_METHOD, "sample.Target#run", ZERO), new CorpusProbe(CorpusProbeKind.CALLERS, "sample.Target#helper", ZERO), new CorpusProbe(CorpusProbeKind.CALLEES, "sample.Target#run", ZERO));
        var oracle = new NodeOracleIdentity("a".repeat(40), "b".repeat(40));
        var graphIdentity = new NodeCallgraphIdentity("fixture-generator", "c".repeat(64), NodeCallgraphIdentity.METHOD_CALL_PROTOCOL, NodeCallgraphIdentity.SQLITE_CALLS_SCHEMA);
        String sourceHash = AnalysisBenchmarkMain.sha256Tree(fixture.sources());
        String jarHash = AnalysisBenchmarkMain.sha256(fixture.jar());
        var index = new CorpusIndexCounts(0, 0, 0, 0, 0);
        var graph = new CorpusCallgraphCounts(0, 0, 0);
        Files.write(baseline, McpJsonDefaults.getMapper().writeValueAsBytes(new NodeCorpusBaseline(1, ClasspathFixtures.VERSION, sourceHash, jarHash, ZERO, graphIdentity, oracle, index, graph, probes)));
        Files.write(expectation, McpJsonDefaults.getMapper().writeValueAsBytes(new CorpusExpectation(2, ClasspathFixtures.VERSION, sourceHash, jarHash, ZERO, graphIdentity, new CompilationUnitCounts(0, 0, 0, 0), index, graph, ZERO, ZERO, ZERO, oracle, List.of(), probes, List.of(), evidence.identity(), evidence.manifestSha256())));
        Path output = root.resolve("qualification");
        var arguments = new CorpusQualificationMain.Arguments(ClasspathFixtures.VERSION, fixture.sources(), fixture.jar(), baseline, expectation, output, root.resolve("cache"), 1, fixture.manifest());
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> CorpusQualificationMain.qualify(arguments, () -> 1));
        assertTrue(failure.getMessage().startsWith("Corpus qualification failed:"));
        CorpusQualificationReport report = McpJsonDefaults.getMapper().readValue(Files.readAllBytes(output.resolve(CorpusQualificationMain.REPORT_NAME)), CorpusQualificationReport.class);
        assertEquals(evidence, report.classpath());
        assertTrue(report.diagnostics().isEmpty(), report.diagnostics().toString());
        assertEquals(1, report.compilationUnits().typed());
        assertEquals(1, report.callgraphCounts().classes());
        assertExternalTypes(output.resolve("symbols.mv.db"));
        byte[] manifestBytes = Files.readAllBytes(fixture.manifest());
        IOException original = new IOException("qualification fixture failure");
        var failingArguments = new CorpusQualificationMain.Arguments(ClasspathFixtures.VERSION, fixture.sources(), fixture.jar(), baseline, expectation, root.resolve("failed-qualification"), root.resolve("cache"), 1, fixture.manifest());
        IOException preserved = assertThrows(IOException.class, () -> CorpusQualificationMain.qualify(failingArguments, () -> {
            Files.writeString(fixture.manifest(), Files.readString(fixture.manifest()) + "\n");
            throw original;
        }));
        assertSame(original, preserved);
        assertEquals(1, preserved.getSuppressed().length);
        assertTrue(preserved.getSuppressed()[0].getMessage().contains("Immutable corpus classpath changed"));
        assertFalse(Files.exists(failingArguments.outputRoot().resolve(CorpusQualificationMain.REPORT_NAME)));
        Files.write(fixture.manifest(), manifestBytes);
        Files.write(fixture.library(), new byte[]{1});
        assertThrows(Exception.class, () -> CorpusQualificationMain.qualify(new CorpusQualificationMain.Arguments(ClasspathFixtures.VERSION, fixture.sources(), fixture.jar(), baseline, expectation, root.resolve("corrupt-qualification"), root.resolve("cache"), 1, fixture.manifest()), () -> 1));
        assertFalse(Files.exists(root.resolve("corrupt-qualification/symbols.mv.db")));
    }

    @Test
    void realIndexingChildLoadsOnlyManifestLibraryAndPinsItsIdentity() throws Exception {
        Fixture fixture = fixture();
        var evidence = CorpusClasspathManifest.verify(fixture.manifest(), ClasspathFixtures.VERSION, List.of()).evidence();
        Path output = root.resolve("child");
        var command = new AnalysisBenchmarkMain.ChildCommand(javaExecutable(), System.getProperty("java.class.path"), ClasspathFixtures.VERSION, fixture.sources(), fixture.jar(), output, root.resolve("cache"), 1, BenchmarkPhase.INDEX, BenchmarkGarbageCollector.G1, fixture.manifest(), evidence.identity(), evidence.manifestSha256());
        BenchmarkChildMeasurement measurement = new AnalysisBenchmarkMain.JvmChildProcessRunner(Duration.ofSeconds(45)).run(command);
        assertEquals(evidence.identity(), measurement.classpathIdentity());
        assertEquals(evidence.manifestSha256(), measurement.classpathManifestSha256());
        assertEquals(1, measurement.counts().indexTypes());
        assertExternalTypes(output.resolve("symbols.mv.db"));

        Path empty = ClasspathFixtures.empty(root.resolve("dependencies"));
        var missing = new AnalysisBenchmarkMain.ChildCommand(javaExecutable(), System.getProperty("java.class.path"), ClasspathFixtures.VERSION, fixture.sources(), fixture.jar(), root.resolve("missing-child"), root.resolve("cache"), 1, BenchmarkPhase.INDEX, BenchmarkGarbageCollector.G1, empty, ClasspathFixtures.IDENTITY, ClasspathFixtures.RAW_HASH);
        assertThrows(Exception.class, () -> new AnalysisBenchmarkMain.JvmChildProcessRunner(Duration.ofSeconds(45)).run(missing));
        var wrong = new AnalysisBenchmarkMain.ChildCommand(javaExecutable(), System.getProperty("java.class.path"), ClasspathFixtures.VERSION, fixture.sources(), fixture.jar(), root.resolve("wrong-child"), root.resolve("cache"), 1, BenchmarkPhase.INDEX, BenchmarkGarbageCollector.G1, fixture.manifest(), ZERO, evidence.manifestSha256());
        Exception failure = assertThrows(Exception.class, () -> new AnalysisBenchmarkMain.JvmChildProcessRunner(Duration.ofSeconds(45)).run(wrong));
        assertTrue(failure.getMessage().contains("identity or manifest SHA-256 mismatch"));
    }

    private static void assertExternalTypes(Path database) throws Exception {
        new SymbolRepository(database).query(connection -> {
            try (var statement = connection.createStatement()) {
                for (String sql : List.of("SELECT type FROM fields WHERE name='value'", "SELECT return_type FROM methods WHERE name='run'", "SELECT type FROM parameters WHERE name='argument'")) {
                    try (var rows = statement.executeQuery(sql)) {
                        assertTrue(rows.next(), sql);
                        assertEquals("dependency.Value", rows.getString(1), sql);
                    }
                }
            }
            return null;
        });
    }

    private Fixture fixture() throws Exception {
        Path dependencySource = root.resolve("dependency-source/dependency/Value.java");
        Files.createDirectories(dependencySource.getParent());
        Files.writeString(dependencySource, "package dependency; public class Value {}\n");
        Path dependencyClasses = Files.createDirectories(root.resolve("dependency-classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", dependencyClasses.toString(), dependencySource.toString()));
        Path library = root.resolve("dependencies/example/value/1/value.jar");
        jar(dependencyClasses, library);
        Path sources = Files.createDirectories(root.resolve("sources/sample"));
        Path client = sources.resolve("Target.java");
        Files.writeString(client, "package sample; import dependency.Value; public class Target { public Value value; public Value run(Value argument) { helper(); return argument; } public void helper() {} }\n");
        Path clientClasses = Files.createDirectories(root.resolve("client-classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-classpath", library.toString(), "-d", clientClasses.toString(), client.toString()));
        Path clientJar = root.resolve("client.jar");
        jar(clientClasses, clientJar);
        // An adjacent JAR containing the dependency cannot rescue an explicit empty manifest.
        Files.copy(library, root.resolve("dependencies/unlisted.jar"));
        Path manifest = root.resolve("dependencies/classpath.json");
        var artifact = new CorpusClasspathArtifact("example/value/1/value.jar", Files.size(library), AnalysisBenchmarkMain.sha256(library));
        Files.write(manifest, McpJsonDefaults.getMapper().writeValueAsBytes(new CorpusClasspathManifest(1, CorpusClasspathKind.SYNTHETIC, ClasspathFixtures.VERSION, ZERO, null, List.of(artifact))));
        return new Fixture(sources.getParent(), clientJar, manifest, library);
    }

    private static void jar(Path classes, Path jar) throws Exception {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream archive = new JarOutputStream(Files.newOutputStream(jar));
             var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                archive.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, archive);
                archive.closeEntry();
            }
        }
    }

    private static Path javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
    }

    private record Fixture(Path sources, Path jar, Path manifest, Path library) {
    }
}
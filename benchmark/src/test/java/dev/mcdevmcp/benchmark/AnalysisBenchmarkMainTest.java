package dev.mcdevmcp.benchmark;

import dev.mcdevmcp.storage.model.MinecraftVersion;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisBenchmarkMainTest {
    private static final BenchmarkRuntimeMetadata RUNTIME = BenchmarkRuntimeMetadata.current();

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsCanonicalOutputOverlapWithoutInvokingAChild() throws Exception {
        Fixture fixture = fixture();
        var commands = new ArrayList<AnalysisBenchmarkMain.ChildCommand>();
        AnalysisBenchmarkMain.Arguments arguments = arguments(fixture, fixture.sourceRoot().resolve("benchmark-output"), fixture.cacheRoot());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> AnalysisBenchmarkMain.runParent(arguments, command -> {
            commands.add(command);
            return child(command.phase(), 1, 1, 1);
        }));

        assertEquals("benchmark output root must not overlap immutable source root", failure.getMessage());
        assertTrue(commands.isEmpty());
    }

    @Test
    void rejectsOutputOverlapWithRemappedJarAndProductionCache() throws Exception {
        Fixture fixture = fixture();
        assertThrows(IllegalArgumentException.class, () -> AnalysisBenchmarkMain.runParent(arguments(fixture, fixture.remappedJar().getParent(), temporaryDirectory.resolve("other-cache")), command -> child(command.phase(), 1, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> AnalysisBenchmarkMain.runParent(arguments(fixture, fixture.cacheRoot().resolve("reports"), fixture.cacheRoot()), command -> child(command.phase(), 1, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> AnalysisBenchmarkMain.runParent(arguments(fixture, fixture.cacheRoot(), fixture.cacheRoot().resolve("nested-production")), command -> child(command.phase(), 1, 1, 1)));
    }

    @Test
    void permitsInputsInsideCacheAndUsesTwelveFreshPhaseChildren() throws Exception {
        Fixture fixture = fixture();
        Path output = temporaryDirectory.resolve("output");
        List<AnalysisBenchmarkMain.ChildCommand> commands = new ArrayList<>();
        int[] indexSequence = {0};
        int[] callgraphSequence = {0};
        long[] indexNanos = {99, 5, 1, 4, 2, 3};
        long[] callgraphNanos = {99, 6, 2, 5, 3, 4};
        long[] indexRss = {99, 500, 100, 400, 200, 300};
        long[] callgraphRss = {99, 600, 200, 500, 300, 400};

        BenchmarkReport report = AnalysisBenchmarkMain.runParent(arguments(fixture, output, fixture.cacheRoot()), command -> {
            commands.add(command);
            if (command.phase() == BenchmarkPhase.INDEX) {
                int sequence = indexSequence[0]++;
                return child(BenchmarkPhase.INDEX, indexNanos[sequence], indexRss[sequence], sequence);
            }
            int sequence = callgraphSequence[0]++;
            return child(BenchmarkPhase.CALLGRAPH, callgraphNanos[sequence], callgraphRss[sequence], sequence);
        });

        assertEquals(AnalysisBenchmarkMain.CHILD_INVOCATIONS, commands.size());
        assertEquals(6, indexSequence[0]);
        assertEquals(6, callgraphSequence[0]);
        Set<Path> outputs = new HashSet<>();
        commands.forEach(command -> outputs.add(command.outputRoot()));
        assertEquals(AnalysisBenchmarkMain.CHILD_INVOCATIONS, outputs.size());
        assertEquals(5, report.measurements().size());
        assertEquals(3_000_000_000L, report.medians().indexNanos());
        assertEquals(4_000_000_000L, report.medians().callgraphNanos());
        assertEquals(300L, report.medians().indexPeakRssBytes());
        assertEquals(400L, report.medians().callgraphPeakRssBytes());
        assertEquals(RUNTIME.javaFeature(), report.result().javaFeature());
        assertEquals(RUNTIME.vendor(), report.result().vendor());
        assertEquals(RUNTIME.vmFlags(), report.result().vmFlags());
        assertEquals(64, report.sourceRootSha256().length());
        assertEquals(64, report.remappedJarSha256().length());
        assertEquals(64, report.serverJarSha256().length());
        assertFalse(report.runtime().garbageCollectors().isEmpty());

        Path reportPath = output.resolve("benchmark-run-42.json");
        BenchmarkReport roundTrip = McpJsonDefaults.getMapper().readValue(Files.readAllBytes(reportPath), BenchmarkReport.class);
        assertEquals(report, roundTrip);
    }

    @Test
    void parentRejectsChildIdentityMismatchAndManifestMutation() throws Exception {
        Fixture fixture = fixture();
        IOException mismatch = assertThrows(IOException.class, () -> AnalysisBenchmarkMain.runParent(arguments(fixture, temporaryDirectory.resolve("bad-identity"), fixture.cacheRoot()), command -> {
            BenchmarkChildMeasurement valid = child(command.phase(), 1, 1, 1);
            return new BenchmarkChildMeasurement(valid.phase(), valid.units(), valid.elapsedNanos(), valid.peakRssBytes(), valid.gcCollections(), valid.gcTimeMillis(), valid.counts(), valid.runtime(), 2, "0".repeat(64), valid.classpathManifestSha256());
        }));
        assertTrue(mismatch.getMessage().contains("classpath identity"));
        assertThrows(IllegalArgumentException.class, () -> AnalysisBenchmarkMain.runParent(arguments(fixture, temporaryDirectory.resolve("mutated-manifest"), fixture.cacheRoot()), command -> {
            Files.writeString(command.classpathManifest(), Files.readString(command.classpathManifest()) + "\n");
            return child(command.phase(), 1, 1, 1);
        }));
    }

    @Test
    void failedChildStillChecksIntegrityWithoutMaskingOriginalFailure() throws Exception {
        Fixture fixture = fixture();
        IOException original = new IOException("original child failure");
        IOException failure = assertThrows(IOException.class, () -> AnalysisBenchmarkMain.runParent(arguments(fixture, temporaryDirectory.resolve("failed-mutating-child"), fixture.cacheRoot()), command -> {
            Files.writeString(command.classpathManifest(), Files.readString(command.classpathManifest()) + "\n");
            throw original;
        }));
        assertSame(original, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[0].getMessage().contains("Immutable corpus classpath changed"));
    }

    @Test
    void calculatesEveryMedianIndependentlyAndRejectsChangingCounts() {
        BenchmarkWorkCounts indexCounts = indexCounts();
        BenchmarkWorkCounts callgraphCounts = callgraphCounts();
        List<BenchmarkMeasurement> values = List.of(measurement(50, 15, 500, 100, 5, indexCounts, callgraphCounts), measurement(10, 55, 100, 500, 1, indexCounts, callgraphCounts), measurement(40, 25, 400, 200, 4, indexCounts, callgraphCounts), measurement(20, 45, 200, 400, 2, indexCounts, callgraphCounts), measurement(30, 35, 300, 300, 3, indexCounts, callgraphCounts));

        BenchmarkMedians medians = BenchmarkMedians.of(values);

        assertEquals(30, medians.indexNanos());
        assertEquals(35, medians.callgraphNanos());
        assertEquals(300, medians.indexPeakRssBytes());
        assertEquals(300, medians.callgraphPeakRssBytes());
        assertEquals(3, medians.indexGcCollections());
        BenchmarkWorkCounts changed = new BenchmarkWorkCounts(101, 1, 101, 2, 3, 4, 0, 0, 0);
        List<BenchmarkMeasurement> inconsistent = new ArrayList<>(values);
        inconsistent.set(4, measurement(30, 35, 300, 300, 3, changed, callgraphCounts));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkMedians.of(inconsistent));
    }

    @Test
    void rejectsWrongChildPhaseAndChangedRuntimeMetadata() throws Exception {
        Fixture fixture = fixture();
        AnalysisBenchmarkMain.Arguments arguments = arguments(fixture, temporaryDirectory.resolve("wrong-phase-output"), fixture.cacheRoot());
        IOException phaseFailure = assertThrows(IOException.class, () -> AnalysisBenchmarkMain.runParent(arguments, _ -> child(BenchmarkPhase.CALLGRAPH, 1, 1, 1)));
        assertTrue(phaseFailure.getMessage().contains("wrong phase"));

        BenchmarkRuntimeMetadata otherRuntime = new BenchmarkRuntimeMetadata(RUNTIME.javaFeature(), RUNTIME.vendor() + "-other", RUNTIME.javaVersion(), RUNTIME.runtimeVersion(), RUNTIME.vmName(), RUNTIME.vmVersion(), RUNTIME.vmFlags(), RUNTIME.garbageCollectors(), RUNTIME.osName(), RUNTIME.memoryMetric());
        IOException runtimeFailure = assertThrows(IOException.class, () -> AnalysisBenchmarkMain.runParent(arguments(fixture, temporaryDirectory.resolve("runtime-output"), fixture.cacheRoot()), command -> command.phase() == BenchmarkPhase.INDEX ? child(BenchmarkPhase.INDEX, 1, 1, 1) : child(BenchmarkPhase.CALLGRAPH, 1, 1, 1, otherRuntime)));
        assertTrue(runtimeFailure.getMessage().contains("different runtimes"));
    }

    @Test
    void childCommandCarriesEveryTypedPhaseArgumentAndReportsPhaseFailure() throws Exception {
        Fixture fixture = fixture();
        AnalysisBenchmarkMain.ChildCommand command = new AnalysisBenchmarkMain.ChildCommand(javaExecutable(), System.getProperty("java.class.path"), new MinecraftVersion("1.21.11"), fixture.sourceRoot(), fixture.remappedJar(), temporaryDirectory.resolve("child-output"), fixture.cacheRoot(), 2, BenchmarkPhase.INDEX, BenchmarkGarbageCollector.G1, ClasspathFixtures.empty(fixture.cacheRoot().resolve("dependencies")), ClasspathFixtures.IDENTITY, ClasspathFixtures.RAW_HASH);
        List<String> processCommand = command.asProcessCommand();
        assertEquals(javaExecutable().toString(), processCommand.getFirst());
        assertTrue(processCommand.contains("--child"));
        assertTrue(processCommand.contains("-Xmx4g"));
        assertTrue(processCommand.contains("-XX:+UseG1GC"));
        assertTrue(processCommand.contains("--phase"));
        assertTrue(processCommand.contains(BenchmarkPhase.INDEX.name()));
        assertTrue(processCommand.contains(fixture.sourceRoot().toString()));
        assertTrue(processCommand.contains(fixture.remappedJar().toString()));
        assertTrue(processCommand.contains(fixture.cacheRoot().toString()));

        AnalysisBenchmarkMain.ChildCommand failing = new AnalysisBenchmarkMain.ChildCommand(javaExecutable(), System.getProperty("java.class.path"), new MinecraftVersion("1.21.11"), fixture.sourceRoot().resolve("missing"), fixture.remappedJar(), temporaryDirectory.resolve("failed-child-output"), fixture.cacheRoot(), 1, BenchmarkPhase.INDEX, BenchmarkGarbageCollector.G1, ClasspathFixtures.empty(fixture.cacheRoot().resolve("dependencies")), ClasspathFixtures.IDENTITY, ClasspathFixtures.RAW_HASH);
        IOException failure = assertThrows(IOException.class, () -> new AnalysisBenchmarkMain.JvmChildProcessRunner(Duration.ofSeconds(30)).run(failing));
        assertTrue(failure.getMessage().contains("failed for INDEX"));
        assertTrue(failure.getMessage().contains("exit"));
    }

    @Test
    void processRunnerBoundsOutputEnforcesDeadlineAndPreservesExitEvidence() throws Exception {
        BenchmarkProcessOutput success = AnalysisBenchmarkMain.executeProcess(fixtureProcessCommand("success"), Duration.ofSeconds(10));
        assertEquals(0, success.exitCode());
        assertEquals("fixture-ok" + System.lineSeparator(), success.standardOutput());

        BenchmarkProcessOutput failure = AnalysisBenchmarkMain.executeProcess(fixtureProcessCommand("failure"), Duration.ofSeconds(10));
        assertEquals(17, failure.exitCode());
        assertTrue(failure.standardError().contains("fixture-failure"));

        BenchmarkProcessOutput overflow = AnalysisBenchmarkMain.executeProcess(fixtureProcessCommand("overflow"), Duration.ofSeconds(10));
        assertTrue(overflow.standardOutputOverflowed());
        assertEquals(AnalysisBenchmarkMain.MAXIMUM_CHILD_OUTPUT_BYTES, overflow.standardOutput().length());

        IOException timeout = assertThrows(IOException.class, () -> AnalysisBenchmarkMain.executeProcess(fixtureProcessCommand("sleep"), Duration.ofMillis(100)));
        assertTrue(timeout.getMessage().contains("exceeded deadline"));
    }

    @Test
    void executesIndexAndCallgraphAgainstTinySourceAndClassJar() throws Exception {
        Fixture fixture = compiledFixture();
        Path indexOutput = temporaryDirectory.resolve("phase-index");
        Path graphOutput = temporaryDirectory.resolve("phase-callgraph");
        Files.createDirectories(indexOutput);
        Files.createDirectories(graphOutput);

        BenchmarkWorkCounts index = AnalysisBenchmarkMain.runIndex(new AnalysisBenchmarkMain.ChildArguments(new MinecraftVersion("1.21.11"), fixture.sourceRoot(), fixture.remappedJar(), indexOutput, fixture.cacheRoot(), 1, BenchmarkPhase.INDEX, ClasspathFixtures.empty(fixture.cacheRoot().resolve("dependencies")), ClasspathFixtures.IDENTITY, ClasspathFixtures.RAW_HASH));
        BenchmarkWorkCounts callgraph = AnalysisBenchmarkMain.runCallgraph(new AnalysisBenchmarkMain.ChildArguments(new MinecraftVersion("1.21.11"), fixture.sourceRoot(), fixture.remappedJar(), graphOutput, fixture.cacheRoot(), 1, BenchmarkPhase.CALLGRAPH, ClasspathFixtures.empty(fixture.cacheRoot().resolve("dependencies")), ClasspathFixtures.IDENTITY, ClasspathFixtures.RAW_HASH));

        assertEquals(1, index.indexTypes());
        assertTrue(index.indexMethods() >= 2);
        assertEquals(1, callgraph.callgraphClasses());
        assertTrue(callgraph.callgraphMethods() >= 2);
        assertTrue(callgraph.callgraphEdges() >= 1);
        assertTrue(Files.isRegularFile(indexOutput.resolve("symbols.mv.db")));
        assertTrue(Files.isDirectory(graphOutput.resolve("bundle")));
    }

    private Fixture fixture() throws IOException {
        Path cache = temporaryDirectory.resolve("cache");
        Path source = cache.resolve("source");
        Files.createDirectories(source);
        Files.writeString(source.resolve("Sample.java"), "class Sample {}", StandardCharsets.UTF_8);
        Path jar = cache.resolve("remapped.jar");
        Files.createFile(jar);
        return new Fixture(cache, source, jar);
    }

    private Fixture compiledFixture() throws IOException {
        Path cache = temporaryDirectory.resolve("compiled-cache");
        Path sourceRoot = cache.resolve("source");
        Path packageRoot = sourceRoot.resolve("fixture");
        Path classes = temporaryDirectory.resolve("compiled-classes");
        Files.createDirectories(packageRoot);
        Files.createDirectories(classes);
        Path source = packageRoot.resolve("Sample.java");
        Files.writeString(source, "package fixture; public class Sample { public int caller() { return callee(); } private int callee() { return 1; } }", StandardCharsets.UTF_8);
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString());
        assertEquals(0, result);
        Path jar = cache.resolve("remapped.jar");
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fixture/Sample.class"));
            Files.copy(classes.resolve("fixture/Sample.class"), output);
            output.closeEntry();
        }
        return new Fixture(cache, sourceRoot, jar);
    }

    private static AnalysisBenchmarkMain.Arguments arguments(Fixture fixture, Path output, Path productionCache) {
        return new AnalysisBenchmarkMain.Arguments(new MinecraftVersion("1.21.11"), fixture.sourceRoot(), fixture.remappedJar(), fixture.remappedJar(), output, productionCache, 1, "machine-a", "run-42", BenchmarkGarbageCollector.G1, ClasspathFixtures.empty(fixture.cacheRoot().resolve("dependencies")));
    }

    private static BenchmarkChildMeasurement child(BenchmarkPhase phase, long elapsedSeconds, long rss, long gcCollections) {
        return child(phase, elapsedSeconds, rss, gcCollections, RUNTIME);
    }

    private static BenchmarkChildMeasurement child(BenchmarkPhase phase, long elapsedSeconds, long rss, long gcCollections, BenchmarkRuntimeMetadata runtime) {
        BenchmarkWorkCounts counts = phase == BenchmarkPhase.INDEX ? indexCounts() : callgraphCounts();
        return new BenchmarkChildMeasurement(phase, counts.units(), elapsedSeconds * 1_000_000_000L, rss, gcCollections, gcCollections * 10, counts, runtime, 2, ClasspathFixtures.IDENTITY, ClasspathFixtures.RAW_HASH);
    }

    private static BenchmarkMeasurement measurement(long indexNanos, long callgraphNanos, long indexRss, long callgraphRss, long gc, BenchmarkWorkCounts indexCounts, BenchmarkWorkCounts callgraphCounts) {
        return new BenchmarkMeasurement(indexNanos, callgraphNanos, 1_000.0d / indexNanos, 2_000.0d / callgraphNanos, indexRss, callgraphRss, gc, gc * 10, 10 - gc, (10 - gc) * 10, indexCounts, callgraphCounts);
    }

    private static BenchmarkWorkCounts indexCounts() {
        return new BenchmarkWorkCounts(100, 1, 100, 2, 3, 4, 0, 0, 0);
    }

    private static BenchmarkWorkCounts callgraphCounts() {
        return new BenchmarkWorkCounts(200, 0, 0, 0, 0, 0, 10, 20, 200);
    }

    private static List<String> fixtureProcessCommand(String command) {
        return List.of(javaExecutable().toString(), "-cp", System.getProperty("java.class.path"), BenchmarkChildProcessFixtureMain.class.getName(), command);
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toAbsolutePath().normalize();
    }

    private record Fixture(Path cacheRoot, Path sourceRoot, Path remappedJar) {
    }
}

package dev.mcdevmcp.app;

import dev.mcdevmcp.analysis.callgraph.CallgraphSummary;
import dev.mcdevmcp.analysis.index.IndexSummary;
import dev.mcdevmcp.analysis.index.SourceRoot;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.SourceNamespace;
import dev.mcdevmcp.support.Cancellation;
import dev.mcdevmcp.support.ProgressSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

final class CliContractTest {
    @TempDir
    Path temporaryDirectory;

    private static void createCallgraphMarker(PlatformPaths paths, MinecraftVersion version) throws Exception {
        Files.createDirectories(paths.callgraphBundle(version).resolve("generations/one"));
        Files.writeString(paths.callgraphBundle(version).resolve("current.json"), "{}");
        Files.writeString(paths.callgraphBundle(version).resolve("generations/one/calls.jsonl"), "{}\n");
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (Exception exception) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }
    }

    private static CliResult execute(AnalysisOperations operations, PlatformPaths paths, String... arguments) {
        var output = new StringWriter();
        var error = new StringWriter();
        int exitCode = Main.execute(arguments, 28, new PrintWriter(output), new PrintWriter(error), new CommandContext(operations, paths));
        return new CliResult(exitCode, output.toString(), error.toString());
    }

    private static String lines(String... lines) {
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    @Test
    void cleanHelpNamesOnlyCurrentManagedState() {
        CliResult result = execute(new RecordingOperations(temporaryDirectory), "clean", "--help");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("--all                    Clean everything (cache, index, temporary analysis state)"));
        assertFalse(result.stdout().contains("DecompilerMC"));
        assertEquals("", result.stderr());
    }

    @Test
    void acceptsSupportedMinecraftRelease() {
        assertTrue(MinecraftVersionValidator.isSupported("1.21.11"));
        assertTrue(MinecraftVersionValidator.isSupported("1.14"));
        assertTrue(MinecraftVersionValidator.isSupported("26.1"));
        assertTrue(MinecraftVersionValidator.isSupported("26.1.0"));
        assertTrue(MinecraftVersionValidator.isSupported("26.1.0-rc1"));
        assertTrue(MinecraftVersionValidator.isSupported("26.1-snapshot-10"));
    }

    @Test
    void rejectsUnsupportedOrMalformedVersions() {
        assertFalse(MinecraftVersionValidator.isSupported("1.13"));
        assertFalse(MinecraftVersionValidator.isSupported("2.0"));
        assertFalse(MinecraftVersionValidator.isSupported("20.1"));
        assertFalse(MinecraftVersionValidator.isSupported("25.9"));
        assertFalse(MinecraftVersionValidator.isSupported("26."));
        assertFalse(MinecraftVersionValidator.isSupported("26.1."));
        assertFalse(MinecraftVersionValidator.isSupported("26.1..foo"));
        assertFalse(MinecraftVersionValidator.isSupported("26.1.foo"));
        assertFalse(MinecraftVersionValidator.isSupported("26.1-"));
        assertFalse(MinecraftVersionValidator.isSupported("26.1--snapshot"));
        assertFalse(MinecraftVersionValidator.isSupported("26.2147483648"));
        assertFalse(MinecraftVersionValidator.isSupported("26.1.2147483648"));
        assertFalse(MinecraftVersionValidator.isSupported("1.21.11junk"));
    }

    @Test
    void initUsesInjectedOperationsAndWritesProgressToStdout() {
        var operations = new RecordingOperations(temporaryDirectory);

        CliResult result = execute(operations, "init", "-v", "1.21.11");

        assertEquals(0, result.exitCode());
        assertEquals(List.of("prepare", "index", "callgraph"), operations.calls());
        assertEquals(lines("[prepare] 0% - prepared sources", "[index] 55% - indexed sources", "Prepared 1 source root(s); indexed 7 types.", "[callgraph] 100% - scanned bytecode", "Recorded 11 call edges."), result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void rebuildAndSkipFlagsSelectOnlyRequestedOperations() throws Exception {
        var initOperations = new RecordingOperations(temporaryDirectory);
        CliResult init = execute(initOperations, "init", "--version", "26.1", "--skip-callgraph");

        assertEquals(0, init.exitCode());
        assertEquals(List.of("prepare", "index"), initOperations.calls());
        assertFalse(init.stdout().contains("callgraph"));

        var rebuildOperations = new RecordingOperations(temporaryDirectory);
        PlatformPaths rebuildPaths = new PlatformPaths(temporaryDirectory.resolve("rebuild-cache"));
        Files.createDirectories(rebuildPaths.sourceRoot(new MinecraftVersion("26.1")));
        CliResult rebuild = execute(rebuildOperations, rebuildPaths, "rebuild", "-v", "26.1", "--with-callgraph");

        assertEquals(0, rebuild.exitCode());
        assertEquals(List.of("index", "callgraph"), rebuildOperations.calls());
        assertTrue(rebuild.stdout().contains("Indexed 7 types."));
        assertTrue(rebuild.stdout().contains("Recorded 11 call edges."));
        assertEquals("", rebuild.stderr());
    }

    @Test
    void commandFailuresAndParameterErrorsStayOnStderrWithoutStacks() throws Exception {
        var operations = new RecordingOperations(temporaryDirectory);
        operations.fail();
        PlatformPaths paths = new PlatformPaths(temporaryDirectory.resolve("failure-cache"));
        MinecraftVersion version = new MinecraftVersion("1.21.11");
        Files.createDirectories(paths.sourceRoot(version));
        Files.createDirectories(paths.symbolDatabase(version).getParent());
        Files.writeString(paths.symbolDatabase(version), "fixture");

        CliResult failure = execute(operations, paths, "callgraph", "-v", "1.21.11");

        assertEquals(1, failure.exitCode());
        assertEquals(lines("Generating callgraph for Minecraft 1.21.11..."), failure.stdout());
        assertEquals(lines("analysis failed"), failure.stderr());
        assertFalse(failure.stderr().contains("Exception"));

        CliResult missingVersion = execute(new RecordingOperations(temporaryDirectory), "rebuild");

        assertEquals(1, missingVersion.exitCode());
        assertEquals("", missingVersion.stdout());
        assertEquals(lines("error: required option '-v, --version <version>' not specified"), missingVersion.stderr());
    }

    @Test
    void rootHelpIgnoresTrailingArgumentsAndUnknownHelpUsesRootUsageOnStderr() {
        RecordingOperations operations = new RecordingOperations(temporaryDirectory);
        CliResult rootHelp = execute(operations, "--help");
        CliResult trailing = execute(operations, "--help", "extra");
        CliResult unknown = execute(operations, "help", "unknown");

        assertEquals(rootHelp, trailing);
        assertEquals(0, rootHelp.exitCode());
        assertEquals("", rootHelp.stderr());
        assertEquals(1, unknown.exitCode());
        assertEquals("", unknown.stdout());
        assertEquals(rootHelp.stdout(), unknown.stderr());
    }

    @Test
    void statusEnumeratesCachedVersionsInNodeCompatibleShape() throws Exception {
        PlatformPaths paths = new PlatformPaths(temporaryDirectory.resolve("status"));
        MinecraftVersion cached = new MinecraftVersion("1.21.11");
        MinecraftVersion indexed = new MinecraftVersion("26.1");
        Files.createDirectories(paths.sourceRoot(cached));
        Files.createDirectories(paths.indexRoot(indexed));
        Files.writeString(paths.indexRoot(indexed).resolve("manifest.json"), "{}");
        Files.createDirectories(paths.cacheRoot().resolve("cache/not-a-version"));

        CliResult result = execute(new RecordingOperations(temporaryDirectory), paths, "status");

        assertEquals(0, result.exitCode());
        assertEquals(lines("Cached Minecraft versions:", "", "  1.21.11:", "    Decompiled: ✓", "    Indexed: ✗", "    Callgraph: ✗", "", "Total: 1 version(s) cached"), result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void statusReportsCorruptCallgraphForOneVersion() throws Exception {
        PlatformPaths paths = new PlatformPaths(temporaryDirectory.resolve("corrupt-status"));
        MinecraftVersion version = new MinecraftVersion("1.21.11");
        Files.createDirectories(paths.callgraphBundle(version));
        Files.writeString(paths.callgraphBundle(version).resolve("current.json"), "{}");

        CliResult result = execute(new RecordingOperations(temporaryDirectory), paths, "status", "-v", version.value());

        assertEquals(0, result.exitCode());
        assertEquals(lines("", "Minecraft 1.21.11:", "  Decompiled: ✗", "  Indexed: ✗", "  Callgraph: ✗", "", "  Run 'mcdev-mcp init -v 1.21.11' to initialize."), result.stdout());
        assertEquals("", result.stderr());
    }

    @Test
    void cleanMatchesNodeSelectorAndVersionRules() throws Exception {
        PlatformPaths paths = new PlatformPaths(temporaryDirectory.resolve("clean"));
        MinecraftVersion first = new MinecraftVersion("1.21.11");
        MinecraftVersion second = new MinecraftVersion("26.1");
        createCallgraphMarker(paths, first);
        createCallgraphMarker(paths, second);

        CliResult result = execute(new RecordingOperations(temporaryDirectory), paths, "clean", "--callgraph");

        assertEquals(1, result.exitCode());
        assertEquals("", result.stdout());
        assertEquals(lines("--callgraph requires -v <version>"), result.stderr());
        assertTrue(Files.exists(paths.callgraphBundle(first).resolve("current.json")));
        assertTrue(Files.exists(paths.callgraphBundle(second).resolve("current.json")));

        CliResult missing = execute(new RecordingOperations(temporaryDirectory), paths, "clean");
        assertEquals(0, missing.exitCode());
        assertEquals(lines("Specify what to clean:", "  --cache           Clean decompiled sources", "  --index           Clean symbol index", "  --callgraph       Clean callgraph database only (requires -v)", "  --all             Clean everything (cache, index, tmp)", "  -v <version>      Clean data for specific version only"), missing.stdout());
        assertEquals("", missing.stderr());

        CliResult conflict = execute(new RecordingOperations(temporaryDirectory), paths, "clean", "--cache", "--index");
        assertEquals(0, conflict.exitCode());
        assertEquals("", conflict.stderr());

        createCallgraphMarker(paths, first);
        CliResult versioned = execute(new RecordingOperations(temporaryDirectory), paths, "clean", "--callgraph", "-v", first.value());
        assertEquals(0, versioned.exitCode());
        assertEquals(lines("Removed callgraph data for 1.21.11: " + paths.callgraphBundle(first).toAbsolutePath().normalize()), versioned.stdout());
        assertEquals("", versioned.stderr());
        assertFalse(Files.exists(paths.callgraphBundle(first).resolve("current.json")));
    }

    @Test
    void cleanContinuesWithTheNextSelectedTargetAfterOneFails() throws Exception {
        PlatformPaths paths = new PlatformPaths(temporaryDirectory.resolve("clean-failure"));
        MinecraftVersion version = new MinecraftVersion("1.21.11");
        Path source = paths.sourceRoot(version).resolve("Example.java");
        Path database = paths.symbolDatabase(version);
        Path outside = temporaryDirectory.resolve("outside");
        Files.createDirectories(source.getParent());
        Files.createDirectories(database.getParent());
        Files.createDirectories(outside);
        Files.writeString(source, "class Example {}");
        Files.writeString(database, "h2");
        createSymbolicLinkOrSkip(paths.versionCache(version).resolve("linked"), outside);

        CliResult result = execute(new RecordingOperations(temporaryDirectory), paths, "clean", "--cache", "--index");

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("Removed index: " + paths.cacheRoot().resolve("index").toAbsolutePath().normalize()));
        assertTrue(result.stderr().contains("Error removing cache at " + paths.cacheRoot().resolve("cache").toAbsolutePath().normalize()));
        assertTrue(result.stderr().contains("(Hint: another process may have files open"));
        assertTrue(Files.exists(source));
        assertFalse(Files.exists(database));
    }

    @Test
    void cleanAllThenStatusLeavesNoGhostVersions() throws Exception {
        PlatformPaths paths = new PlatformPaths(temporaryDirectory.resolve("clean-all"));
        MinecraftVersion cached = new MinecraftVersion("1.21.11");
        MinecraftVersion indexed = new MinecraftVersion("26.1");
        Path source = paths.sourceRoot(cached).resolve("Example.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Example {}");
        createCallgraphMarker(paths, cached);
        Files.createDirectories(paths.indexRoot(indexed));
        Files.writeString(paths.indexRoot(indexed).resolve("manifest.json"), "{}");

        CliResult clean = execute(new RecordingOperations(temporaryDirectory), paths, "clean", "--all");

        assertEquals(0, clean.exitCode());
        assertEquals(lines("Removed cache: " + paths.cacheRoot().resolve("cache").toAbsolutePath().normalize(), "Removed index: " + paths.cacheRoot().resolve("index").toAbsolutePath().normalize(), "tmp not found: " + paths.cacheRoot().resolve("tmp").toAbsolutePath().normalize(), "", "Run `mcdev-mcp init -v <version>` to reinitialize."), clean.stdout());
        assertEquals("", clean.stderr());
        assertFalse(Files.exists(source));
        assertFalse(Files.exists(paths.indexRoot(indexed).resolve("manifest.json")));

        CliResult status = execute(new RecordingOperations(temporaryDirectory), paths, "status");

        assertEquals(0, status.exitCode());
        assertEquals(lines("Status: Not initialized", "Run `mcdev-mcp init -v <version>` to set up."), status.stdout());
        assertEquals("", status.stderr());
    }

    private CliResult execute(AnalysisOperations operations, String... arguments) {
        return execute(operations, new PlatformPaths(temporaryDirectory.resolve("cache-root")), arguments);
    }

    private record CliResult(int exitCode, String stdout, String stderr) {
    }

    private static final class RecordingOperations implements AnalysisOperations {
        private final Path root;
        private final List<String> calls = new ArrayList<>();
        private String failure;

        private RecordingOperations(Path root) {
            this.root = root;
        }

        private List<String> calls() {
            return List.copyOf(calls);
        }

        private void fail() {
            failure = "analysis failed";
        }

        @Override
        public PreparedSources prepareSources(MinecraftVersion version, ProgressSink progress, Cancellation cancellation) {
            before("prepare", progress, -20, "prepared sources");
            Path artifact = root.resolve("client.jar");
            SourceRoot sources = new SourceRoot(SourceNamespace.MINECRAFT, Optional.empty(), root.resolve("sources"));
            return new PreparedSources(version, List.of(sources), artifact, artifact, artifact);
        }

        @Override
        public IndexSummary rebuildIndex(MinecraftVersion version, ProgressSink progress, Cancellation cancellation) {
            before("index", progress, 55, "indexed sources");
            return new IndexSummary(2, 7, 3, 5, 1, Duration.ZERO);
        }

        @Override
        public CallgraphSummary rebuildCallgraph(MinecraftVersion version, ProgressSink progress, Cancellation cancellation) {
            before("callgraph", progress, 120, "scanned bytecode");
            return new CallgraphSummary(2, 5, 11, Duration.ZERO);
        }

        private void before(String operation, ProgressSink progress, int percent, String message) {
            calls.add(operation);
            if (failure != null) {
                throw new IllegalStateException(failure);
            }
            progress.report(operation, percent, message);
        }
    }
}

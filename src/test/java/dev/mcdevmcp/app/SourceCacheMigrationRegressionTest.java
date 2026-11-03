package dev.mcdevmcp.app;

import com.sun.net.httpserver.HttpServer;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.symbols.SymbolRepository;
import dev.mcdevmcp.storage.symbols.VersionStateRepository;
import dev.mcdevmcp.storage.migration.SourceOwnership;
import dev.mcdevmcp.storage.migration.SourcePreparationStamp;
import dev.mcdevmcp.storage.migration.SourceProvenance;
import dev.mcdevmcp.storage.migration.SourceTreeInventory;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.VersionState;
import dev.mcdevmcp.support.Cancellation;
import dev.mcdevmcp.support.RequiredRuntime;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;
import reactor.util.annotation.NonNull;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class SourceCacheMigrationRegressionTest {
    private static final MinecraftVersion VERSION = new MinecraftVersion("26.1");
    private static final String SOURCE = "package sample; public class Example { public String value() { return \"generated\"; } }";
    private static final Duration PATIENCE = Duration.ofMinutes(2);
    private static final String MALFORMED = "package sample; public class Example { void value(<VAR_NAMELESS_ENCLOSURE>) { } }";

    @TempDir
    Path temporaryDirectory;

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture release timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static void awaitLockWait(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (thread.getState() == Thread.State.TIMED_WAITING && Arrays.stream(thread.getStackTrace()).anyMatch(frame -> frame.getClassName().equals("dev.mcdevmcp.storage.symbols.IndexLock") && frame.getMethodName().equals("acquire"))) {
                return;
            }
            if (thread.join(Duration.ofMillis(10))) {
                break;
            }
        }
        fail("Initializer did not queue on the fair version-operation lock");
    }

    private static CliResult execute(Fixture fixture, String... arguments) {
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        int exitCode = Main.execute(arguments, RequiredRuntime.FEATURE, new PrintWriter(output), new PrintWriter(error), new CommandContext(fixture.pipeline(), fixture.paths()));
        return new CliResult(exitCode, output.toString(), error.toString());
    }

    /**
     * The transaction directories under the version's migration root, the retained record of each publication.
     */
    private static List<Path> transactions(Fixture fixture) throws IOException {
        try (var entries = Files.list(fixture.paths().cacheRoot().resolve("migrations").resolve(VERSION.value()))) {
            return entries.filter(Files::isDirectory).sorted().toList();
        }
    }

    private Fixture fixture() throws Exception {
        byte[] jar = AnalysisPipelineIntegrationTest.compileJar(temporaryDirectory.resolve("fixture"), "sample.Example", SOURCE);
        PlatformPaths paths = new PlatformPaths(temporaryDirectory.resolve("cache"));
        Files.createDirectories(paths.remappedJar(VERSION).getParent());
        Files.write(paths.remappedJar(VERSION), jar);
        HttpServer server = AnalysisPipelineIntegrationTest.server();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        String sha1 = AnalysisPipelineIntegrationTest.sha1(jar);
        server.createContext("/manifest", exchange -> AnalysisPipelineIntegrationTest.respond(exchange, McpJsonDefaults.getMapper().writeValueAsBytes(Map.of("versions", List.of(Map.of("id", VERSION.value(), "url", base + "/version"))))));
        server.createContext("/version", exchange -> AnalysisPipelineIntegrationTest.respond(exchange, McpJsonDefaults.getMapper().writeValueAsBytes(Map.of("downloads", Map.of("client", Map.of("url", base + "/client", "sha1", sha1, "size", jar.length))))));
        server.createContext("/client", exchange -> AnalysisPipelineIntegrationTest.respond(exchange, jar));
        return new Fixture(paths, AnalysisPipelineIntegrationTest.pipeline(paths, server), server);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void existingEmptyOrMarkerSourcesRequireExplicitRefreshWithoutChangingOriginals(boolean marker) throws Exception {
        try (Fixture fixture = fixture()) {
            PlatformPaths paths = fixture.paths();
            Files.createDirectories(paths.sourceRoot(VERSION));
            if (marker) Files.writeString(paths.sourceRoot(VERSION).resolve("user.txt"), "keep user marker");
            Files.createDirectories(paths.symbolIndex(VERSION).getParent());
            Files.writeString(paths.symbolIndex(VERSION), "keep old database");
            SourceTreeInventory sources = SourceTreeInventory.capture(paths.sourceRoot(VERSION), Cancellation.none());
            fixture.server().stop(0);

            CliResult result = execute(fixture, "rebuild", "-v", VERSION.value());

            assertEquals(1, result.exitCode());
            assertTrue(result.stderr().contains("init -v 26.1 --refresh-sources to regenerate with retained originals."), result.stderr());
            assertFalse(result.stderr().contains("run init first"), result.stderr());
            assertEquals(sources, SourceTreeInventory.capture(paths.sourceRoot(VERSION), Cancellation.none()));
            assertEquals("keep old database", Files.readString(paths.symbolIndex(VERSION)));
            assertFalse(Files.exists(paths.cacheRoot().resolve("migrations/26.1/pending.json")));
        }
    }

    @Test
    void callgraphKeepsHistoricalIndexExistencePreconditionInsideProductionPipeline() throws Exception {
        try (Fixture fixture = fixture()) {
            Path source = fixture.paths().sourceRoot(VERSION).resolve("sample/Example.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, SOURCE);
            fixture.server().stop(0);

            CliResult missing = execute(fixture, "callgraph", "-v", VERSION.value());
            assertEquals(1, missing.exitCode());
            assertEquals("Generating callgraph for Minecraft 26.1..." + System.lineSeparator(), missing.stdout());
            assertEquals("Minecraft 26.1 not indexed. Run 'init -v 26.1' first." + System.lineSeparator(), missing.stderr());
            assertFalse(Files.exists(fixture.paths().callgraphBundle(VERSION)));
            assertEquals(SOURCE, Files.readString(source));

            // Historically this command required existence, not index readiness.
            Files.createDirectories(fixture.paths().symbolIndex(VERSION).getParent());
            Files.writeString(fixture.paths().symbolIndex(VERSION), "historical existence sentinel");
            CliResult indexed = execute(fixture, "callgraph", "-v", VERSION.value());
            assertEquals(0, indexed.exitCode(), indexed.stderr());
            assertTrue(indexed.stdout().contains("Recorded 1 call edges and 0 field accesses."), indexed.stdout());
            assertEquals("", indexed.stderr());
            assertTrue(Files.exists(fixture.paths().callgraphBundle(VERSION).resolve("current.json")));
            assertEquals("historical existence sentinel", Files.readString(fixture.paths().symbolIndex(VERSION)));
        }
    }

    @Test
    void globalCleanKeepsInitializerOutsideBothSelectedDeletions() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.initialize();
            Path database = fixture.paths().symbolIndex(VERSION);
            CountDownLatch cacheRemoved = new CountDownLatch(1);
            CountDownLatch releaseCleanup = new CountDownLatch(1);
            CountDownLatch initEntered = new CountDownLatch(1);
            AtomicReference<Thread> initThread = new AtomicReference<>();
            CountDownLatch initStarted = new CountDownLatch(1);
            StringWriter output = new StringWriter();
            StringWriter error = new StringWriter();
            CleanCommand command = new CleanCommand(fixture.paths(), version -> {
                assertEquals(VERSION, version);
                assertFalse(Files.exists(fixture.paths().sourceRoot(VERSION)));
                assertTrue(Files.exists(database));
                cacheRemoved.countDown();
                await(releaseCleanup);
            });
            CommandLine cli = new CommandLine(command).setOut(new PrintWriter(output)).setErr(new PrintWriter(error));
            try (var executor = Executors.newFixedThreadPool(2)) {
                try {
                    var cleanup = executor.submit(() -> cli.execute("--all"));
                    assertTrue(cacheRemoved.await(10, TimeUnit.SECONDS));
                    var initializing = executor.submit(() -> {
                        initThread.set(Thread.currentThread());
                        initEntered.countDown();
                        return fixture.pipeline().initialize(VERSION, SourceRefreshPolicy.NORMAL, (_, _, _) -> initStarted.countDown(), Cancellation.none());
                    });
                    assertTrue(initEntered.await(10, TimeUnit.SECONDS));
                    awaitLockWait(initThread.get());
                    assertFalse(initStarted.await(200, TimeUnit.MILLISECONDS));
                    assertFalse(initializing.isDone());
                    releaseCleanup.countDown();
                    assertEquals(0, cleanup.get(20, TimeUnit.SECONDS), error.toString());
                    initializing.get(20, TimeUnit.SECONDS);
                    assertEquals(VersionState.READY, new VersionStateRepository(fixture.paths()).state(VERSION));
                    assertNotNull(new SymbolRepository(database).classByName("sample.Example"));
                    assertTrue(Files.isRegularFile(fixture.paths().sourceRoot(VERSION).resolve("sample/Example.java")));
                } finally {
                    releaseCleanup.countDown();
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test
    void integrityMatchedGeneratedCacheAutomaticallyRepairsAndRetainsOriginalPair() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.initialize();
            Path source = fixture.paths().sourceRoot(VERSION).resolve("sample/Example.java");
            Path stampPath = fixture.paths().versionCache(VERSION).resolve("source-preparation.json");
            SourcePreparationStamp prior = SourceProvenance.read(stampPath).orElseThrow();
            Files.writeString(source, MALFORMED);
            Files.writeString(fixture.paths().sourceRoot(VERSION).resolve("producer-marker.txt"), "old generated marker");
            Files.createDirectories(fixture.paths().sourceRoot(VERSION).resolve("empty-generated-directory"));
            SourceTreeInventory before = SourceTreeInventory.capture(fixture.paths().sourceRoot(VERSION), Cancellation.none());
            // Simulate an older producer's integrity-matched stamp, not ownership inferred from validity.
            Files.delete(stampPath);
            SourceProvenance.write(stampPath, new SourcePreparationStamp(1, SourceOwnership.GENERATED, prior.producer(), prior.inputs(), before, prior.validation()));
            byte[] oldIndex = Files.readAllBytes(fixture.paths().symbolIndex(VERSION));
            byte[] oldStamp = Files.readAllBytes(stampPath);
            List<String> stages = new ArrayList<>();

            InitializationResult result = fixture.pipeline().initialize(VERSION, SourceRefreshPolicy.NORMAL, (stage, _, _) -> stages.add(stage), Cancellation.none());

            assertTrue(stages.contains("decompile"));
            Path old = result.retainedMigration().orElseThrow().resolve("old");
            assertEquals(before, SourceTreeInventory.capture(old.resolve("client"), Cancellation.none()));
            assertArrayEquals(oldIndex, Files.readAllBytes(old.resolve("index/symbols.jsonl")));
            assertArrayEquals(oldStamp, Files.readAllBytes(old.resolve("source-preparation.json")));
            assertFalse(Files.readString(source).contains("VAR_NAMELESS_ENCLOSURE"));
            assertEquals(VersionState.READY, new VersionStateRepository(fixture.paths()).state(VERSION));
            assertEquals(SourceOwnership.GENERATED, SourceProvenance.read(stampPath).orElseThrow().ownership());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"decompile-failure", "decompile-cancel", "validation-failure", "validation-cancel", "index-failure", "index-cancel"})
    void actualStagingFailureOrCancellationPreservesCanonicalPair(String fault) throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.initialize();
            SourceTreeInventory source = SourceTreeInventory.capture(fixture.paths().sourceRoot(VERSION), Cancellation.none());
            byte[] index = Files.readAllBytes(fixture.paths().symbolIndex(VERSION));
            Path stamp = fixture.paths().versionCache(VERSION).resolve("source-preparation.json");
            byte[] provenance = Files.readAllBytes(stamp);
            List<Path> transactions = transactions(fixture);
            AtomicBoolean cancel = new AtomicBoolean();
            AtomicBoolean injected = new AtomicBoolean();
            // A cancelled refresh leaves its thread interrupted, so it runs on a thread of its own.
            IllegalStateException failure = assertTimeoutPreemptively(PATIENCE, () -> assertThrows(IllegalStateException.class, () -> fixture.pipeline().initialize(VERSION, SourceRefreshPolicy.EXPLICIT_REFRESH, (stage, percent, _) -> {
                boolean boundary = switch (fault) {
                    case "decompile-cancel" -> stage.equals("decompile") && percent == 0;
                    case "decompile-failure", "validation-failure", "validation-cancel" ->
                            stage.equals("decompile") && percent == 100;
                    default -> stage.equals("index") && percent == 75;
                };
                if (!boundary) return;
                injected.set(true);
                if (fault.endsWith("cancel")) {
                    cancel.set(true);
                }
                else if (fault.equals("validation-failure")) {
                    try (var staged = Files.walk(fixture.paths().cacheRoot().resolve("migrations"))) {
                        Path generated = staged.filter(path -> path.endsWith("candidate/client/sample/Example.java")).findFirst().orElseThrow();
                        Files.writeString(generated, MALFORMED);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }
                else {
                    throw new IllegalStateException("injected " + fault);
                }
            }, cancel::get)));
            assertTrue(injected.get(), fault);
            if (fault.equals("validation-failure")) {
                assertTrue(failure.getMessage().contains("Generated sources failed complete source validation"), failure.getMessage());
            }
            assertEquals(source, SourceTreeInventory.capture(fixture.paths().sourceRoot(VERSION), Cancellation.none()));
            assertArrayEquals(index, Files.readAllBytes(fixture.paths().symbolIndex(VERSION)));
            assertArrayEquals(provenance, Files.readAllBytes(stamp));
            assertFalse(Files.exists(fixture.paths().cacheRoot().resolve("migrations/26.1/pending.json")));
            assertEquals(transactions, transactions(fixture), "a refresh that fails before publication leaves nothing staged");
            assertEquals(VersionState.READY, new VersionStateRepository(fixture.paths()).state(VERSION));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"failure", "cancel", "skip"})
    void realCliPostCommitGraphFailureDoesNotRollBackPairOrOldGraph(String mode) throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.initialize();
            fixture.pipeline().rebuildCallgraph(VERSION, (_, _, _) -> {
            }, Cancellation.none());
            Path source = fixture.paths().sourceRoot(VERSION).resolve("sample/Example.java");
            Files.writeString(source, SOURCE.replace("generated", "old edited source"));
            byte[] oldIndex = Files.readAllBytes(fixture.paths().symbolIndex(VERSION));
            SourceTreeInventory oldGraph = SourceTreeInventory.capture(fixture.paths().callgraphBundle(VERSION), Cancellation.none());
            AtomicBoolean graphEntered = new AtomicBoolean();
            StringWriter output = new StringWriter();
            StringWriter error = new StringWriter();
            PrintWriter writer = new PrintWriter(output) {
                @Override
                public PrintWriter printf(@NonNull String format, Object... arguments) {
                    super.printf(format, arguments);
                    if (arguments.length == 3 && "callgraph".equals(arguments[0]) && Integer.valueOf(5).equals(arguments[1])) {
                        graphEntered.set(true);
                        if (mode.equals("cancel")) {
                            Thread.currentThread().interrupt();
                        }
                        else {
                            throw new IllegalStateException("injected real callgraph progress failure");
                        }
                    }
                    return this;
                }
            };
            List<String> arguments = new ArrayList<>(List.of("init", "-v", VERSION.value(), "--refresh-sources"));
            if (mode.equals("skip")) arguments.add("--skip-callgraph");
            // The cancelling mode leaves the command's thread interrupted, so the command runs on a thread of its own.
            int exitCode = assertTimeoutPreemptively(PATIENCE, () -> Main.execute(arguments.toArray(String[]::new), RequiredRuntime.FEATURE, writer, new PrintWriter(error), new CommandContext(fixture.pipeline(), fixture.paths())));
            assertEquals(mode.equals("skip") ? 0 : 1, exitCode, error.toString());
            assertEquals(!mode.equals("skip"), graphEntered.get());
            if (!mode.equals("skip")) {
                assertTrue(error.toString().contains("Source/index committed; callgraph failed"), error.toString());
            }
            assertTrue(output.toString().contains("Prepared 1 source root(s); indexed 1 types."));
            assertFalse(Files.readString(source).contains("old edited source"));
            assertEquals(VersionState.READY, new VersionStateRepository(fixture.paths()).state(VERSION));
            assertEquals(oldGraph, SourceTreeInventory.capture(fixture.paths().callgraphBundle(VERSION), Cancellation.none()));
            try (var retained = Files.walk(fixture.paths().cacheRoot().resolve("migrations/26.1"))) {
                Path oldSource = retained.filter(path -> path.endsWith("old/client/sample/Example.java")).filter(path -> {
                    try {
                        return Files.readString(path).contains("old edited source");
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }).findFirst().orElseThrow();
                Path old = oldSource.getParent().getParent().getParent();
                assertArrayEquals(oldIndex, Files.readAllBytes(old.resolve("index/symbols.jsonl")));
            }
        }
    }

    private record CliResult(int exitCode, String stdout, String stderr) {
    }

    private record Fixture(PlatformPaths paths, AnalysisPipeline pipeline, HttpServer server) implements AutoCloseable {
        void initialize() {
            pipeline.initialize(VERSION, SourceRefreshPolicy.NORMAL, (_, _, _) -> {
            }, Cancellation.none());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
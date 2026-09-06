package dev.mcdevmcp.parity;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

@Tag("parity")
@ResourceLock("node-oracle-materializer")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class DifferentialCliTest {
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);
    private static final Path JAVA = Path.of(System.getProperty("mcdevMcpJava"));
    private static final Path JAR = Path.of(System.getProperty("mcdevMcpJar"));
    private static final String LEGACY_VERSION = "1.21.11";
    private static final String SECOND_VERSION = "26.1";
    private static final String LEGACY_MANIFEST = """
                                                  {
                                                    "version": "1.21.11",
                                                    "generated": "2026-07-15T00:00:00.000Z",
                                                    "packages": {
                                                      "minecraft": [],
                                                      "fabric": []
                                                    }
                                                  }
                                                  """;
    private static final List<CliCase> HELP_CASES = List.of(new CliCase("root-no-arguments", List.of(), Fixture.EMPTY), new CliCase("root-help", List.of("--help"), Fixture.EMPTY), new CliCase("root-help-extra", List.of("--help", "extra"), Fixture.EMPTY), new CliCase("help-unknown", List.of("help", "unknown"), Fixture.EMPTY), new CliCase("serve-help", List.of("serve", "--help"), Fixture.EMPTY), new CliCase("init-help", List.of("init", "--help"), Fixture.EMPTY), new CliCase("callgraph-help", List.of("callgraph", "--help"), Fixture.EMPTY), new CliCase("rebuild-help", List.of("rebuild", "--help"), Fixture.EMPTY), new CliCase("status-help", List.of("status", "--help"), Fixture.EMPTY));
    private static final List<CliCase> VALIDATION_CASES = List.of(new CliCase("unknown-command", List.of("unknown"), Fixture.EMPTY), new CliCase("init-missing-version", List.of("init"), Fixture.EMPTY), new CliCase("init-invalid-version", List.of("init", "-v", "1.13"), Fixture.EMPTY), new CliCase("callgraph-missing-version", List.of("callgraph"), Fixture.EMPTY), new CliCase("callgraph-invalid-version", List.of("callgraph", "-v", "1.13"), Fixture.EMPTY), new CliCase("rebuild-missing-version", List.of("rebuild"), Fixture.EMPTY), new CliCase("rebuild-invalid-version", List.of("rebuild", "-v", "1.13"), Fixture.EMPTY), new CliCase("status-invalid-version", List.of("status", "-v", "1.13"), Fixture.EMPTY), new CliCase("clean-invalid-version", List.of("clean", "--cache", "-v", "1.13"), Fixture.CLEANABLE));
    private static final List<CliCase> CACHE_CASES = List.of(new CliCase("callgraph-missing-cache", List.of("callgraph", "-v", LEGACY_VERSION), Fixture.EMPTY), new CliCase("rebuild-missing-cache", List.of("rebuild", "-v", LEGACY_VERSION), Fixture.EMPTY), new CliCase("status-empty-cache", List.of("status"), Fixture.EMPTY), new CliCase("status-missing-version-cache", List.of("status", "-v", LEGACY_VERSION), Fixture.EMPTY));
    private static final List<CliCase> CLEAN_CASES = List.of(new CliCase("clean-no-selector", List.of("clean"), Fixture.CLEANABLE, StateChange.UNCHANGED), new CliCase("clean-version-default", List.of("clean", "-v", LEGACY_VERSION), Fixture.CLEANABLE, StateChange.DEFAULT_VERSION), new CliCase("clean-cache", List.of("clean", "--cache"), Fixture.CLEANABLE, StateChange.CACHE_ALL), new CliCase("clean-cache-version", List.of("clean", "--cache", "-v", LEGACY_VERSION), Fixture.CLEANABLE, StateChange.CACHE_VERSION), new CliCase("clean-index", List.of("clean", "--index"), Fixture.CLEANABLE, StateChange.INDEX_ALL), new CliCase("clean-index-version", List.of("clean", "--index", "-v", LEGACY_VERSION), Fixture.CLEANABLE, StateChange.INDEX_VERSION), new CliCase("clean-callgraph", List.of("clean", "--callgraph"), Fixture.CLEANABLE, StateChange.CALLGRAPH_ALL), new CliCase("clean-callgraph-version", List.of("clean", "--callgraph", "-v", LEGACY_VERSION), Fixture.CLEANABLE, StateChange.CALLGRAPH_VERSION), new CliCase("clean-all", List.of("clean", "--all"), Fixture.CLEANABLE, StateChange.ALL_ALL), new CliCase("clean-all-version", List.of("clean", "--all", "-v", LEGACY_VERSION), Fixture.CLEANABLE, StateChange.ALL_VERSION), new CliCase("clean-conflicting-selectors", List.of("clean", "--cache", "--index"), Fixture.CLEANABLE, StateChange.CONFLICTING_SELECTORS));

    @TempDir
    Path temporaryDirectory;

    private NodeOracleMaterializer oracle;

    @BeforeAll
    void materializeOracle() throws Exception {
        oracle = NodeOracleMaterializer.materialize();
    }

    @AfterAll
    void closeOracle() {
        if (oracle != null) {
            oracle.close();
        }
    }

    @TestFactory
    Stream<DynamicTest> publicCommandHelpMatchesNodeOracle() {
        return differentialTests(HELP_CASES);
    }

    @TestFactory
    Stream<DynamicTest> offlineValidationAndMissingCacheBehaviorMatchesNodeOracle() {
        // Task 13 requires CLI help, validation, missing-cache, legacy-status, and clean parity,
        // while static-tool success/error/empty/truncation is exercised over STDIO. Successful init
        // requires downloads, and Node callgraph success builds an external generator, so neither
        // belongs in this offline process gate. The rebuild-success test below exercises the
        // feasible stateful command against one shared source fixture despite the intentionally
        // different JSON and H2 index layouts.
        return differentialTests(Stream.concat(VALIDATION_CASES.stream(), CACHE_CASES.stream()).toList());
    }

    @TestFactory
    Stream<DynamicTest> cleanSelectorBehaviorMatchesNodeOracle() {
        return differentialTests(CLEAN_CASES);
    }

    @Test
    void legacyOnlyStatusUsesTheApprovedNeedsRebuildDifference() throws Exception {
        CliCase testCase = new CliCase("status-legacy-index", List.of("status", "-v", LEGACY_VERSION), Fixture.LEGACY);
        ExecutionPair pair = execute(testCase);
        assertStateTransitions(testCase, pair);

        assertEquals(0, pair.node().exitCode());
        assertEquals(0, pair.java().exitCode());
        assertTrue(pair.node().stdout().contains("Indexed: ✓"), pair.node()::stdout);
        assertEquals("\nMinecraft 1.21.11:\n  Decompiled: ✓\n  Indexed: ✓\n  Callgraph: ✗\n  Packages: 0 Minecraft, 0 Fabric\n  Generated: 2026-07-15T00:00:00.000Z\n", pair.node().stdout());
        assertEquals("1.21.11: needs-rebuild, callgraph absent\n", pair.java().stdout());
        assertEquals("", pair.node().stderr());
        assertEquals("", pair.java().stderr());
    }

    @Test
    void rewriteVersionIsAnApprovedIntentionalDifference() throws Exception {
        CliCase testCase = new CliCase("version", List.of("--version"), Fixture.EMPTY);
        ExecutionPair pair = execute(testCase);
        assertStateTransitions(testCase, pair);

        assertEquals(new ProcessResult(0, "2.2.1\n", ""), pair.node());
        assertEquals(new ProcessResult(0, "3.0.0\n", ""), pair.java());
    }

    @Test
    void cleanHelpUsesTheApprovedCurrentAnalysisStateDifference() throws Exception {
        CliCase testCase = new CliCase("clean-help", List.of("clean", "--help"), Fixture.EMPTY);
        ExecutionPair pair = execute(testCase);
        assertStateTransitions(testCase, pair);

        assertTrue(pair.node().stdout().contains("DecompilerMC"), pair.node()::stdout);
        assertTrue(pair.java().stdout().contains("temporary analysis state"), pair.java()::stdout);
        assertEquals(pair.node().stdout().replace("DecompilerMC", "$APPROVED_ANALYSIS_STATE"), pair.java().stdout().replace("temporary analysis state", "$APPROVED_ANALYSIS_STATE"));
        assertEquals(pair.node().exitCode(), pair.java().exitCode());
        assertEquals(pair.node().stderr(), pair.java().stderr());
    }

    @Test
    void cleanableFixtureCoversLegacyAndCurrentOwnedArtifactLayouts() throws Exception {
        CliEnvironment nodeEnvironment = CliEnvironment.create(temporaryDirectory.resolve("fixture-inventory/node"), Fixture.CLEANABLE, RuntimeKind.NODE);
        CliEnvironment javaEnvironment = CliEnvironment.create(temporaryDirectory.resolve("fixture-inventory/java"), Fixture.CLEANABLE, RuntimeKind.JAVA);
        List<String> nodePaths = LogicalState.capture(nodeEnvironment, RuntimeKind.NODE).artifacts().stream().map(ArtifactSnapshot::relativePath).toList();
        List<String> javaPaths = LogicalState.capture(javaEnvironment, RuntimeKind.JAVA).artifacts().stream().map(ArtifactSnapshot::relativePath).toList();

        assertTrue(nodePaths.contains("cache/1.21.11/client/fixture/Example.java"), nodePaths::toString);
        assertTrue(nodePaths.contains("cache"), nodePaths::toString);
        assertTrue(nodePaths.contains("cache/1.21.11"), nodePaths::toString);
        assertTrue(nodePaths.contains("cache/1.21.11/jars/1.21.11_unobfuscated.jar"), nodePaths::toString);
        assertTrue(nodePaths.contains("index/manifest.json"), nodePaths::toString);
        assertTrue(nodePaths.contains("index/1.21.11/manifest.json"), nodePaths::toString);
        assertTrue(nodePaths.contains("index/1.21.11/minecraft/fixture.json"), nodePaths::toString);
        assertTrue(nodePaths.contains("cache/1.21.11/callgraph/callgraph.db"), nodePaths::toString);
        assertFalse(nodePaths.contains("index/1.21.11/symbols.mv.db"), nodePaths::toString);
        assertFalse(nodePaths.contains("cache/1.21.11/indexes/callgraph/current.json"), nodePaths::toString);

        assertTrue(javaPaths.contains("cache/1.21.11/client/fixture/Example.java"), javaPaths::toString);
        assertTrue(javaPaths.contains("cache"), javaPaths::toString);
        assertTrue(javaPaths.contains("cache/1.21.11"), javaPaths::toString);
        assertTrue(javaPaths.contains("cache/1.21.11/jars/1.21.11_unobfuscated.jar"), javaPaths::toString);
        assertTrue(javaPaths.contains("index/1.21.11/symbols.mv.db"), javaPaths::toString);
        assertTrue(javaPaths.contains("index/1.21.11/symbols.trace.db"), javaPaths::toString);
        assertTrue(javaPaths.contains("cache/1.21.11/indexes/callgraph/current.json"), javaPaths::toString);
        assertTrue(javaPaths.contains("cache/1.21.11/indexes/callgraph/generations/fixture/callers.jsonl"), javaPaths::toString);
        assertFalse(javaPaths.contains("index/1.21.11/manifest.json"), javaPaths::toString);
        assertFalse(javaPaths.contains("cache/1.21.11/callgraph/callgraph.db"), javaPaths::toString);
        assertTrue(nodePaths.contains("tmp/1.21.11/fixture.partial"), nodePaths::toString);
        assertTrue(javaPaths.contains("tmp/1.21.11/fixture.partial"), javaPaths::toString);
    }

    @Test
    void rebuildSuccessProducesAnIndexFromTheSameOfflineSourceFixture() throws Exception {
        CliCase testCase = new CliCase("rebuild-success", List.of("rebuild", "-v", LEGACY_VERSION), Fixture.REBUILDABLE);
        ExecutionPair pair = execute(testCase);

        assertEquals(0, pair.node().exitCode(), pair.node()::stderr);
        assertEquals(0, pair.java().exitCode(), pair.java()::stderr);
        assertEquals("", pair.node().stderr());
        assertEquals("", pair.java().stderr());
        String opening = "Rebuilding index for Minecraft " + LEGACY_VERSION + "...\n";
        assertTrue(pair.node().stdout().startsWith(opening), pair.node()::stdout);
        assertTrue(pair.java().stdout().startsWith(opening), pair.java()::stdout);
        assertTrue(pair.node().stdout().contains("✓ Index rebuilt!"), pair.node()::stdout);
        assertTrue(pair.java().stdout().contains("Indexed "), pair.java()::stdout);

        String source = "cache/" + LEGACY_VERSION + "/client/fixture/Example.java";
        assertEquals(artifact(pair.nodeBefore(), source), artifact(pair.nodeAfter(), source));
        assertEquals(artifact(pair.javaBefore(), source), artifact(pair.javaAfter(), source));
        assertEquals(withoutIndex(pair.nodeBefore()), withoutIndex(pair.nodeAfter()));
        assertEquals(withoutIndex(pair.javaBefore()), withoutIndex(pair.javaAfter()));
        assertTrue(hasArtifact(pair.nodeAfter(), "index/" + LEGACY_VERSION + "/manifest.json"));
        assertTrue(hasArtifact(pair.javaAfter(), "index/" + LEGACY_VERSION + "/symbols.mv.db"));
    }

    private static ArtifactSnapshot artifact(LogicalState state, String path) {
        return state.artifacts().stream().filter(candidate -> candidate.relativePath().equals(path)).findFirst().orElseThrow();
    }

    private static boolean hasArtifact(LogicalState state, String path) {
        return state.artifacts().stream().anyMatch(candidate -> candidate.relativePath().equals(path));
    }

    private static List<ArtifactSnapshot> withoutIndex(LogicalState state) {
        return state.artifacts().stream().filter(candidate -> !StateChange.under(candidate.relativePath(), "index")).toList();
    }

    private Stream<DynamicTest> differentialTests(List<CliCase> cases) {
        return cases.stream().map(testCase -> dynamicTest(testCase.name(), () -> assertMatches(testCase)));
    }

    private void assertMatches(CliCase testCase) throws Exception {
        ExecutionPair pair = execute(testCase);
        LogicalState expectedNode = testCase.stateChange().expected(pair.nodeBefore(), RuntimeKind.NODE);
        LogicalState expectedJava = testCase.stateChange().expected(pair.javaBefore(), RuntimeKind.JAVA);
        if (!pair.node().equals(pair.java()) || !expectedNode.equals(pair.nodeAfter()) || !expectedJava.equals(pair.javaAfter())) {
            Path report = writeMismatchReport(testCase, pair);
            assertEquals(expectedNode, pair.nodeAfter(), () -> "Node CLI state transition mismatch; report: " + report.toAbsolutePath().normalize());
            assertEquals(expectedJava, pair.javaAfter(), () -> "Java CLI state transition mismatch; report: " + report.toAbsolutePath().normalize());
            assertEquals(pair.node(), pair.java(), () -> "CLI parity mismatch; report: " + report.toAbsolutePath().normalize());
        }
    }

    private static void assertStateTransitions(CliCase testCase, ExecutionPair pair) {
        assertEquals(testCase.stateChange().expected(pair.nodeBefore(), RuntimeKind.NODE), pair.nodeAfter());
        assertEquals(testCase.stateChange().expected(pair.javaBefore(), RuntimeKind.JAVA), pair.javaAfter());
    }

    private ExecutionPair execute(CliCase testCase) throws Exception {
        Path caseRoot = temporaryDirectory.resolve(testCase.name());
        CliEnvironment nodeEnvironment = CliEnvironment.create(caseRoot.resolve("node"), testCase.fixture(), RuntimeKind.NODE);
        CliEnvironment javaEnvironment = CliEnvironment.create(caseRoot.resolve("java"), testCase.fixture(), RuntimeKind.JAVA);

        LogicalState nodeBefore = LogicalState.capture(nodeEnvironment, RuntimeKind.NODE);
        ProcessResult node = run(nodeProcess(testCase.arguments(), nodeEnvironment));
        LogicalState nodeAfter = LogicalState.capture(nodeEnvironment, RuntimeKind.NODE);
        LogicalState javaBefore = LogicalState.capture(javaEnvironment, RuntimeKind.JAVA);
        ProcessResult java = run(javaProcess(testCase.arguments(), javaEnvironment));
        LogicalState javaAfter = LogicalState.capture(javaEnvironment, RuntimeKind.JAVA);
        return new ExecutionPair(normalize(node, nodeEnvironment), normalize(java, javaEnvironment), nodeBefore, nodeAfter, javaBefore, javaAfter);
    }

    private ProcessBuilder nodeProcess(List<String> arguments, CliEnvironment environment) {
        List<String> commandArguments = new ArrayList<>(arguments.size() + 1);
        commandArguments.add("dist/cli.js");
        commandArguments.addAll(arguments);
        ProcessBuilder builder = oracle.nodeProcess(commandArguments.toArray(String[]::new));
        applyEnvironment(builder, environment);
        return builder;
    }

    private static ProcessBuilder javaProcess(List<String> arguments, CliEnvironment environment) {
        List<String> command = new ArrayList<>(arguments.size() + 8);
        command.add(JAVA.toString());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Duser.language=en");
        command.add("-Duser.country=US");
        command.add("-Duser.home=" + environment.home());
        command.add("-Djava.io.tmpdir=" + environment.temporary());
        command.add("-jar");
        command.add(JAR.toString());
        command.addAll(arguments);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(Path.of("").toAbsolutePath().normalize().toFile());
        applyEnvironment(builder, environment);
        return builder;
    }

    private static void applyEnvironment(ProcessBuilder builder, CliEnvironment environment) {
        Map<String, String> values = builder.environment();
        values.keySet().removeIf(name -> {
            String normalized = name.toUpperCase(Locale.ROOT);
            return normalized.startsWith("MCDEV_") || normalized.equals("DEBUGBRIDGE_PORT") || normalized.equals("NODE_OPTIONS") || normalized.equals("JAVA_TOOL_OPTIONS") || normalized.equals("_JAVA_OPTIONS") || normalized.equals("JDK_JAVA_OPTIONS");
        });
        values.put("HOME", environment.home().toString());
        values.put("USERPROFILE", environment.home().toString());
        values.put("LOCALAPPDATA", environment.localApplicationData().toString());
        values.put("APPDATA", environment.roamingApplicationData().toString());
        values.put("XDG_CACHE_HOME", environment.xdgCache().toString());
        values.put("TEMP", environment.temporary().toString());
        values.put("TMP", environment.temporary().toString());
    }

    private static ProcessResult run(ProcessBuilder builder) throws Exception {
        Process process = builder.start();
        ProcessTreeTracker processTree = new ProcessTreeTracker(process);
        ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
        long deadline = System.nanoTime() + PROCESS_TIMEOUT.toNanos();
        try {
            Future<byte[]> stdout = readers.submit(() -> readAll(process.getInputStream()));
            Future<byte[]> stderr = readers.submit(() -> readAll(process.getErrorStream()));
            if (!process.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) {
                processTree.terminateAndAwait(Duration.ofSeconds(5));
                throw new TimeoutException("CLI process exceeded " + PROCESS_TIMEOUT + ": " + builder.command());
            }
            processTree.terminateAndAwait(Duration.ofSeconds(5));
            return new ProcessResult(process.exitValue(), decode(stdout, deadline), decode(stderr, deadline));
        } catch (InterruptedException exception) {
            try {
                processTree.terminateAndAwait(Duration.ofSeconds(5));
            } catch (IOException | InterruptedException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            Thread.currentThread().interrupt();
            throw exception;
        } finally {
            readers.shutdownNow();
            awaitReaderShutdown(readers);
            processTree.stop();
        }
    }

    private static long remaining(long deadline) throws TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new TimeoutException("CLI process output deadline expired");
        }
        return remaining;
    }

    private static void awaitReaderShutdown(ExecutorService readers) {
        boolean interrupted = false;
        try {
            try {
                if (!readers.awaitTermination(1, TimeUnit.SECONDS)) {
                    readers.shutdownNow();
                }
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static byte[] readAll(InputStream stream) {
        try {
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String decode(Future<byte[]> future, long deadline) throws InterruptedException, ExecutionException, TimeoutException {
        return new String(future.get(remaining(deadline), TimeUnit.NANOSECONDS), StandardCharsets.UTF_8);
    }

    private static ProcessResult normalize(ProcessResult result, CliEnvironment environment) {
        return new ProcessResult(result.exitCode(), normalize(result.stdout(), environment), normalize(result.stderr(), environment));
    }

    private static String normalize(String text, CliEnvironment environment) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        for (String version : List.of(LEGACY_VERSION, SECOND_VERSION)) {
            normalized = replacePath(normalized, environment.cacheRoot().resolve("cache").resolve(version).resolve("callgraph"), "<CALLGRAPH:" + version + ">");
            normalized = replacePath(normalized, environment.cacheRoot().resolve("cache").resolve(version).resolve("indexes").resolve("callgraph"), "<CALLGRAPH:" + version + ">");
        }
        List<Path> fixturePaths = List.of(environment.root(), environment.home(), environment.localApplicationData(), environment.roamingApplicationData(), environment.xdgCache(), environment.temporary());
        for (Path fixturePath : fixturePaths) {
            normalized = replacePath(normalized, fixturePath, "<FIXTURE_ROOT>");
        }
        return normalized;
    }

    private static String replacePath(String text, Path path, String replacement) {
        String absolute = path.toAbsolutePath().normalize().toString();
        return text.replace(absolute, replacement).replace(absolute.replace('\\', '/'), replacement);
    }

    private static Path writeMismatchReport(CliCase testCase, ExecutionPair pair) throws IOException {
        Path reports = Path.of("build", "reports", "parity", "cli").toAbsolutePath().normalize();
        Files.createDirectories(reports);
        Path report = reports.resolve(testCase.name() + ".txt");
        String contents = """
                          case: %s
                          arguments: %s
                          fixture: %s
                          
                          node:
                          exit: %d
                          stdout:
                          %s
                          stderr:
                          %s
                          state before:
                          %s
                          state after:
                          %s
                          
                          java:
                          exit: %d
                          stdout:
                          %s
                          stderr:
                          %s
                          state before:
                          %s
                          state after:
                          %s
                          
                          first difference:
                          %s
                          """.formatted(testCase.name(), testCase.arguments(), testCase.fixture(), pair.node().exitCode(), pair.node().stdout(), pair.node().stderr(), pair.nodeBefore(), pair.nodeAfter(), pair.java().exitCode(), pair.java().stdout(), pair.java().stderr(), pair.javaBefore(), pair.javaAfter(), firstDifference(testCase, pair));
        Files.writeString(report, contents, StandardCharsets.UTF_8);
        return report;
    }

    private static String firstDifference(CliCase testCase, ExecutionPair pair) {
        ProcessResult node = pair.node();
        ProcessResult java = pair.java();
        if (node.exitCode() != java.exitCode()) {
            return "exit: node=" + node.exitCode() + ", java=" + java.exitCode();
        }
        if (!node.stdout().equals(java.stdout())) {
            return textDifference("stdout", node.stdout(), java.stdout());
        }
        if (!node.stderr().equals(java.stderr())) {
            return textDifference("stderr", node.stderr(), java.stderr());
        }
        LogicalState expectedNode = testCase.stateChange().expected(pair.nodeBefore(), RuntimeKind.NODE);
        if (!expectedNode.equals(pair.nodeAfter())) {
            return "node state: expected=" + expectedNode + ", actual=" + pair.nodeAfter();
        }
        return "java state: expected=" + testCase.stateChange().expected(pair.javaBefore(), RuntimeKind.JAVA) + ", actual=" + pair.javaAfter();
    }

    private static String textDifference(String stream, String node, String java) {
        int commonLength = Math.min(node.length(), java.length());
        int offset = 0;
        while (offset < commonLength && node.charAt(offset) == java.charAt(offset)) {
            offset++;
        }
        int line = 1;
        int column = 1;
        for (int index = 0; index < offset; index++) {
            if (node.charAt(index) == '\n') {
                line++;
                column = 1;
            }
            else {
                column++;
            }
        }
        return "%s at line %d, column %d: node=%s, java=%s".formatted(stream, line, column, excerpt(node, offset), excerpt(java, offset));
    }

    private static String excerpt(String text, int offset) {
        if (offset >= text.length()) {
            return "<end>";
        }
        int end = Math.min(text.length(), offset + 80);
        return text.substring(offset, end).replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t");
    }

    private static final class ProcessTreeTracker {
        private final Process process;
        private final Set<ProcessHandle> tree = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean monitoring = new AtomicBoolean(true);
        private final Thread monitor;

        private ProcessTreeTracker(Process process) {
            this.process = process;
            capture();
            monitor = Thread.ofVirtual().name("cli-parity-process-tree-" + process.pid()).start(this::monitor);
        }

        private void monitor() {
            while (monitoring.get()) {
                capture();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                if (Thread.interrupted()) {
                    return;
                }
            }
            capture();
        }

        private void capture() {
            tree.add(process.toHandle());
            try (Stream<ProcessHandle> descendants = process.descendants()) {
                descendants.forEach(tree::add);
            }
        }

        private void terminateAndAwait(Duration timeout) throws IOException, InterruptedException {
            capture();
            Duration half = timeout.dividedBy(2);
            destroy(false);
            if (awaitTermination(half)) {
                return;
            }
            capture();
            destroy(true);
            if (!awaitTermination(timeout.minus(half))) {
                List<Long> survivors = tree.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).sorted().toList();
                throw new IOException("Failed to terminate CLI process tree: " + survivors);
            }
        }

        private void destroy(boolean forcibly) {
            tree.stream().sorted((left, right) -> Boolean.compare(left.pid() == process.pid(), right.pid() == process.pid())).forEach(handle -> {
                if (!handle.isAlive()) {
                    return;
                }
                if (forcibly) {
                    handle.destroyForcibly();
                }
                else {
                    handle.destroy();
                }
            });
        }

        private boolean awaitTermination(Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (tree.stream().anyMatch(ProcessHandle::isAlive)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(25)));
            }
            return true;
        }

        private void stop() {
            monitoring.set(false);
            monitor.interrupt();
            boolean interrupted = false;
            try {
                try {
                    monitor.join(1_000);
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private record CliCase(String name, List<String> arguments, Fixture fixture, StateChange stateChange) {
        private CliCase(String name, List<String> arguments, Fixture fixture) {
            this(name, arguments, fixture, StateChange.UNCHANGED);
        }

        private CliCase {
            arguments = List.copyOf(arguments);
        }
    }

    private record ExecutionPair(ProcessResult node, ProcessResult java, LogicalState nodeBefore, LogicalState nodeAfter, LogicalState javaBefore, LogicalState javaAfter) {
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }

    private record LogicalState(List<ArtifactSnapshot> artifacts) {
        private LogicalState {
            artifacts = List.copyOf(artifacts);
        }

        private static LogicalState capture(CliEnvironment environment, RuntimeKind runtime) throws IOException {
            Path root = environment.cacheRoot();
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                return new LogicalState(List.of());
            }
            List<ArtifactSnapshot> artifacts = new ArrayList<>();
            Set<Path> ignoredScaffolds = runtime == RuntimeKind.JAVA ? persistentJavaLockScaffolds(root) : Set.of();
            try (Stream<Path> candidates = Files.walk(root)) {
                for (Path candidate : candidates.skip(1).sorted().toList()) {
                    Path relative = root.relativize(candidate);
                    if (ignoredScaffolds.contains(candidate)) {
                        continue;
                    }
                    if (Files.isSymbolicLink(candidate)) {
                        artifacts.add(new ArtifactSnapshot(portable(relative), ArtifactType.SYMBOLIC_LINK, -1, portable(Files.readSymbolicLink(candidate))));
                    }
                    else if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                        BasicFileAttributes attributes = Files.readAttributes(candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        artifacts.add(new ArtifactSnapshot(portable(relative), ArtifactType.REGULAR_FILE, attributes.size(), sha256(candidate)));
                    }
                    else if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                        artifacts.add(new ArtifactSnapshot(portable(relative), ArtifactType.DIRECTORY, 0, ""));
                    }
                    else if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                        BasicFileAttributes attributes = Files.readAttributes(candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        artifacts.add(new ArtifactSnapshot(portable(relative), ArtifactType.OTHER, attributes.size(), ""));
                    }
                }
            }
            return new LogicalState(artifacts);
        }

        private static Set<Path> persistentJavaLockScaffolds(Path root) throws IOException {
            Set<Path> ignored = new LinkedHashSet<>();
            try (Stream<Path> candidates = Files.walk(root)) {
                for (Path candidate : candidates.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                    if (containsOnlyPersistentJavaLocks(root, candidate)) {
                        ignored.add(candidate);
                        try (Stream<Path> descendants = Files.walk(candidate)) {
                            descendants.skip(1).forEach(ignored::add);
                        }
                    }
                }
            }
            return ignored;
        }

        private static boolean containsOnlyPersistentJavaLocks(Path root, Path directory) throws IOException {
            List<Path> entries;
            try (Stream<Path> descendants = Files.walk(directory)) {
                entries = descendants.skip(1).toList();
            }
            List<Path> locks = new ArrayList<>();
            for (Path candidate : entries) {
                if (Files.isSymbolicLink(candidate)) {
                    return false;
                }
                if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) || !isPersistentJavaLock(root.relativize(candidate))) {
                    return false;
                }
                locks.add(candidate);
            }
            if (locks.isEmpty()) {
                return false;
            }
            for (Path candidate : entries) {
                if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS) && locks.stream().noneMatch(lock -> lock.startsWith(candidate))) {
                    return false;
                }
            }
            return true;
        }

        private static boolean isPersistentJavaLock(Path relative) {
            int names = relative.getNameCount();
            return names == 3 && relative.getName(0).toString().equals("index") && relative.getName(2).toString().equals("symbols.mv.db.lock") || names == 5 && relative.getName(0).toString().equals("cache") && relative.getName(2).toString().equals("indexes") && relative.getName(3).toString().equals("callgraph") && relative.getName(4).toString().equals("publication.lock");
        }

        private static String portable(Path path) {
            return path.toString().replace('\\', '/');
        }

        private static String sha256(Path path) throws IOException {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new AssertionError("SHA-256 is required by the Java runtime", exception);
            }
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    private record ArtifactSnapshot(String relativePath, ArtifactType type, long size, String fingerprint) {
    }

    // Node and Java store their callgraphs differently, but every clean selector removes the same
    // logical target. These rules prove each process changed exactly its runtime-owned files.
    private enum StateChange {
        UNCHANGED, DEFAULT_VERSION, CACHE_ALL, CACHE_VERSION, INDEX_ALL, INDEX_VERSION, CALLGRAPH_ALL, CALLGRAPH_VERSION, ALL_ALL, ALL_VERSION, CONFLICTING_SELECTORS;

        private LogicalState expected(LogicalState before, RuntimeKind runtime) {
            return new LogicalState(before.artifacts().stream().filter(artifact -> !removes(artifact.relativePath(), runtime)).toList());
        }

        private boolean removes(String path, RuntimeKind runtime) {
            return switch (this) {
                case UNCHANGED, CALLGRAPH_ALL -> false;
                case DEFAULT_VERSION, ALL_VERSION ->
                        under(path, "cache/" + LEGACY_VERSION) || under(path, "index/" + LEGACY_VERSION);
                case CACHE_ALL -> under(path, "cache");
                case CACHE_VERSION -> under(path, "cache/" + LEGACY_VERSION);
                case INDEX_ALL -> under(path, "index");
                case INDEX_VERSION -> under(path, "index/" + LEGACY_VERSION);
                case CALLGRAPH_VERSION ->
                        runtime == RuntimeKind.NODE ? under(path, "cache/" + LEGACY_VERSION + "/callgraph") : javaCallgraph(path);
                case ALL_ALL -> under(path, "cache") || under(path, "index") || under(path, "tmp");
                case CONFLICTING_SELECTORS -> under(path, "cache") || under(path, "index");
            };
        }

        private static boolean javaCallgraph(String path) {
            return path.equals("cache/" + LEGACY_VERSION + "/indexes") || under(path, "cache/" + LEGACY_VERSION + "/indexes/callgraph");
        }

        private static boolean under(String path, String root) {
            return path.equals(root) || path.startsWith(root + "/");
        }
    }

    private enum ArtifactType {
        DIRECTORY, REGULAR_FILE, SYMBOLIC_LINK, OTHER
    }

    private enum RuntimeKind {
        NODE, JAVA
    }

    private record CliEnvironment(Path root, Path home, Path localApplicationData, Path roamingApplicationData, Path xdgCache, Path temporary) {
        private static CliEnvironment create(Path root, Fixture fixture, RuntimeKind runtime) throws IOException {
            Path absoluteRoot = root.toAbsolutePath().normalize();
            CliEnvironment environment = new CliEnvironment(absoluteRoot, absoluteRoot.resolve("home"), absoluteRoot.resolve("local-app-data"), absoluteRoot.resolve("roaming-app-data"), absoluteRoot.resolve("xdg-cache"), absoluteRoot.resolve("tmp"));
            Files.createDirectories(environment.home());
            Files.createDirectories(environment.localApplicationData());
            Files.createDirectories(environment.roamingApplicationData());
            Files.createDirectories(environment.xdgCache());
            Files.createDirectories(environment.temporary());
            fixture.populate(environment.cacheRoot(), runtime);
            for (String directory : List.of("cache", "index", "index/minecraft", "index/fabric", "tmp", "tools")) {
                Files.createDirectories(environment.cacheRoot().resolve(directory));
            }
            return environment;
        }

        private Path cacheRoot() {
            String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (osName.contains("mac") || osName.contains("darwin")) {
                return home.resolve("Library").resolve("Caches").resolve("mcdev-mcp");
            }
            if (osName.contains("win")) {
                return localApplicationData.resolve("mcdev-mcp").resolve("Cache");
            }
            return xdgCache.resolve("mcdev-mcp");
        }
    }

    private enum Fixture {
        EMPTY {
            @Override
            void populate(Path cacheRoot, RuntimeKind runtime) throws IOException {
                Files.createDirectories(cacheRoot);
            }
        }, REBUILDABLE {
            @Override
            void populate(Path cacheRoot, RuntimeKind runtime) throws IOException {
                populateCachedVersion(cacheRoot, LEGACY_VERSION, runtime);
            }
        }, LEGACY {
            @Override
            void populate(Path cacheRoot, RuntimeKind runtime) throws IOException {
                populateCachedVersion(cacheRoot, LEGACY_VERSION, runtime);
                populateLegacyIndex(cacheRoot, LEGACY_VERSION);
            }
        }, CLEANABLE {
            @Override
            void populate(Path cacheRoot, RuntimeKind runtime) throws IOException {
                populateCachedVersion(cacheRoot, LEGACY_VERSION, runtime);
                populateCachedVersion(cacheRoot, SECOND_VERSION, runtime);
                if (runtime == RuntimeKind.NODE) {
                    populateUnversionedLegacyIndex(cacheRoot);
                    populateLegacyIndex(cacheRoot, LEGACY_VERSION);
                    populateLegacyIndex(cacheRoot, SECOND_VERSION);
                    populateLegacyCallgraph(cacheRoot, LEGACY_VERSION);
                    populateLegacyCallgraph(cacheRoot, SECOND_VERSION);
                }
                else {
                    populateH2IndexArtifacts(cacheRoot, LEGACY_VERSION);
                    populateH2IndexArtifacts(cacheRoot, SECOND_VERSION);
                    populateJsonlCallgraph(cacheRoot, LEGACY_VERSION);
                    populateJsonlCallgraph(cacheRoot, SECOND_VERSION);
                }
                Files.createDirectories(cacheRoot.resolve("tmp").resolve("fixture"));
                Files.writeString(cacheRoot.resolve("tmp").resolve("fixture").resolve("marker.txt"), "fixture", StandardCharsets.UTF_8);
                writeArtifact(cacheRoot.resolve("tmp").resolve(LEGACY_VERSION).resolve("fixture.partial"), "partial");
                writeArtifact(cacheRoot.resolve("tmp").resolve(SECOND_VERSION).resolve("fixture.partial"), "partial");
            }
        };

        abstract void populate(Path cacheRoot, RuntimeKind runtime) throws IOException;

        static void populateCachedVersion(Path cacheRoot, String version, RuntimeKind runtime) throws IOException {
            Path source = cacheRoot.resolve("cache").resolve(version).resolve("client").resolve("fixture").resolve("Example.java");
            writeArtifact(source, "package fixture; final class Example {}\n");
            Path jars = cacheRoot.resolve("cache").resolve(version).resolve("jars");
            if (runtime == RuntimeKind.NODE) {
                writeArtifact(jars.resolve(version + "_unobfuscated.jar"), "unobfuscated");
                writeArtifact(jars.resolve(version + "_obfuscated.jar"), "obfuscated");
            }
            else {
                writeClassJar(jars.resolve(version + "_unobfuscated.jar"));
                writeArtifact(jars.resolve("client.jar"), "client");
                writeArtifact(jars.resolve("client-unobfuscated.jar"), "official-unobfuscated");
            }
        }

        static void populateLegacyIndex(Path cacheRoot, String version) throws IOException {
            Path manifest = cacheRoot.resolve("index").resolve(version).resolve("manifest.json");
            writeArtifact(manifest, LEGACY_MANIFEST.replace(LEGACY_VERSION, version));
            writeArtifact(manifest.getParent().resolve("minecraft").resolve("fixture.json"), "{\"package\":\"fixture\",\"classes\":[]}\n");
            writeArtifact(manifest.getParent().resolve("fabric").resolve("fixture.json"), "{\"package\":\"fixture\",\"classes\":[]}\n");
        }

        static void populateUnversionedLegacyIndex(Path cacheRoot) throws IOException {
            Path index = cacheRoot.resolve("index");
            writeArtifact(index.resolve("manifest.json"), LEGACY_MANIFEST);
            writeArtifact(index.resolve("minecraft").resolve("fixture.json"), "{\"package\":\"fixture\",\"classes\":[]}\n");
            writeArtifact(index.resolve("fabric").resolve("fixture.json"), "{\"package\":\"fixture\",\"classes\":[]}\n");
        }

        static void populateH2IndexArtifacts(Path cacheRoot, String version) throws IOException {
            Path index = cacheRoot.resolve("index").resolve(version);
            writeArtifact(index.resolve("symbols.mv.db"), "h2");
            writeArtifact(index.resolve("symbols.newFile"), "new");
            writeArtifact(index.resolve("symbols.tempFile"), "temporary");
            writeArtifact(index.resolve("symbols.trace.db"), "trace");
            writeArtifact(index.resolve("symbols.trace.db.old"), "old-trace");
            writeArtifact(index.resolve("symbols.42.temp.db"), "numbered-temporary");
            writeArtifact(index.resolve("symbols.mv.db.bak"), "backup");
        }

        static void populateLegacyCallgraph(Path cacheRoot, String version) throws IOException {
            Path legacyCallgraph = cacheRoot.resolve("cache").resolve(version).resolve("callgraph");
            writeArtifact(legacyCallgraph.resolve("callgraph.db"), "legacy");
            writeArtifact(legacyCallgraph.resolve("client-remapped.jar"), "remapped");
            writeArtifact(legacyCallgraph.resolve("_generator_config").resolve("config.properties"), "output.dir=fixture\n");
        }

        static void populateJsonlCallgraph(Path cacheRoot, String version) throws IOException {
            Path generation = cacheRoot.resolve("cache").resolve(version).resolve("indexes").resolve("callgraph").resolve("generations").resolve("fixture");
            writeArtifact(generation.resolve("manifest.json"), "{}\n");
            writeArtifact(generation.resolve("callers.jsonl"), "{}\n");
            writeArtifact(generation.resolve("callers.index.jsonl"), "{}\n");
            writeArtifact(generation.resolve("callees.jsonl"), "{}\n");
            writeArtifact(generation.resolve("callees.index.jsonl"), "{}\n");
            writeArtifact(generation.getParent().getParent().resolve("current.json"), "{}\n");
        }

        static void writeArtifact(Path path, String contents) throws IOException {
            Files.createDirectories(path.getParent());
            Files.writeString(path, contents, StandardCharsets.UTF_8);
        }

        static void writeClassJar(Path path) throws IOException {
            Files.createDirectories(path.getParent());
            String entry = DifferentialCliTest.class.getName().replace('.', '/') + ".class";
            try (InputStream input = DifferentialCliTest.class.getClassLoader().getResourceAsStream(entry);
                 JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
                if (input == null) {
                    throw new IOException("Missing compiled parity fixture class: " + entry);
                }
                output.putNextEntry(new JarEntry(entry));
                input.transferTo(output);
                output.closeEntry();
            }
        }
    }
}
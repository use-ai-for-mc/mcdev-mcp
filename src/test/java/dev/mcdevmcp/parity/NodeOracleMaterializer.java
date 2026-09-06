package dev.mcdevmcp.parity;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Materializes the frozen Node checkout without ever modifying the source checkout.
 */
public final class NodeOracleMaterializer implements AutoCloseable {
    private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() {
    };
    private static final String MASTER_BRANCH = "refs/heads/master";
    private static final String SCRATCH_RELATIVE_PATH = ".superpowers/parity/node-oracle";
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration PROCESS_STOP_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration OUTPUT_PUMP_POLL_INTERVAL = Duration.ofMillis(100);

    private final Path scratchDirectory;
    private final ScratchLayout scratchLayout;
    private final OracleSnapshot originalSnapshot;
    private final Path npmUserConfig;
    private boolean closed;

    private NodeOracleMaterializer(Path scratchDirectory, ScratchLayout scratchLayout, OracleSnapshot originalSnapshot, Path npmUserConfig) {
        this.scratchDirectory = scratchDirectory;
        this.scratchLayout = scratchLayout;
        this.originalSnapshot = originalSnapshot;
        this.npmUserConfig = npmUserConfig;
    }

    /**
     * Clones the pinned master checkout into the ignored parity scratch directory and builds it.
     */
    public static NodeOracleMaterializer materialize() throws IOException, InterruptedException {
        Path currentWorktree = currentWorktree();
        OracleContract contract = readContract(currentWorktree);
        Worktree oracle = findOracleWorktree(currentWorktree, contract);
        OracleSnapshot snapshot = OracleSnapshot.capture(oracle.path());
        if (snapshot.status().length != 0) {
            throw new IllegalStateException("Oracle worktree is dirty: " + oracle.path());
        }

        ScratchLayout scratchLayout = scratchLayout(currentWorktree);
        Path scratchDirectory = null;

        Throwable failure = null;
        try {
            Files.createDirectories(scratchLayout.parityDirectory());
            scratchLayout = scratchLayout(currentWorktree);
            Files.createDirectories(scratchLayout.scratchDirectory());
            scratchLayout = scratchLayout(currentWorktree);
            scratchDirectory = createScratchDirectory(scratchLayout);
            run(currentWorktree, List.of("git", "clone", "--local", "--no-hardlinks", "--no-checkout", oracle.path().toString(), scratchDirectory.toString()), Map.of());
            run(currentWorktree, List.of("git", "-C", scratchDirectory.toString(), "checkout", "--detach", contract.commit()), Map.of());

            Path npmUserConfig = scratchDirectory.resolve(".npmrc");
            Files.writeString(npmUserConfig, "\n", StandardCharsets.UTF_8);
            Map<String, String> npmEnvironment = npmEnvironment(npmUserConfig);
            run(scratchDirectory, List.of(npmExecutable(), "ci"), npmEnvironment);
            run(scratchDirectory, List.of(npmExecutable(), "--userconfig", npmUserConfig.toString(), "run", "build"), npmEnvironment);
            return new NodeOracleMaterializer(scratchDirectory, scratchLayout, snapshot, npmUserConfig);
        } catch (IOException | InterruptedException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            if (failure != null) {
                verifyOriginalUnchanged(snapshot, failure);
                deleteScratchAfterFailure(scratchLayout, scratchDirectory, failure);
            }
        }
    }

    /**
     * Starts a command in the materialized clone with the same locked-down npm environment used to build it.
     */
    public ProcessBuilder process(List<String> command) {
        requireOpen();
        if (command.isEmpty()) {
            throw new IllegalArgumentException("Oracle command must not be empty");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(scratchDirectory.toFile());
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.putAll(npmEnvironment(npmUserConfig));
        return builder;
    }

    public ProcessBuilder nodeProcess(String... arguments) {
        List<String> command = new ArrayList<>(arguments.length + 1);
        command.add(isWindows() ? "node.exe" : "node");
        command.addAll(List.of(arguments));
        return process(command);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Throwable failure = null;
        try {
            originalSnapshot.assertUnchanged();
        } catch (RuntimeException | Error exception) {
            failure = exception;
        }
        try {
            deleteScratchDirectory(scratchLayout, scratchDirectory);
        } catch (IOException | RuntimeException | Error exception) {
            if (failure == null) {
                failure = exception;
            }
            else {
                failure.addSuppressed(exception);
            }
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new IllegalStateException("Unable to clean up Node oracle scratch directory", failure);
        }
    }

    static List<Worktree> parseWorktreePorcelain(byte[] porcelain) {
        String text = decodeUtf8(porcelain, "git worktree list --porcelain");
        List<Worktree> worktrees = new ArrayList<>();
        Map<String, String> fields = new LinkedHashMap<>();
        String[] lines = text.split("\\r?\\n", -1);
        for (String line : lines) {
            if (line.isEmpty()) {
                if (!fields.isEmpty()) {
                    worktrees.add(toWorktree(fields));
                    fields = new LinkedHashMap<>();
                }
                continue;
            }
            int separator = line.indexOf(' ');
            String key = separator < 0 ? line : line.substring(0, separator);
            String value = separator < 0 ? "" : line.substring(separator + 1);
            if (!isPorcelainField(key, value) || fields.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Malformed git worktree porcelain line: " + line);
            }
        }
        if (!fields.isEmpty()) {
            worktrees.add(toWorktree(fields));
        }
        if (worktrees.isEmpty()) {
            throw new IllegalArgumentException("git worktree list --porcelain returned no worktrees");
        }
        return List.copyOf(worktrees);
    }

    private static boolean isPorcelainField(String key, String value) {
        return switch (key) {
            case "worktree", "HEAD", "branch" -> !value.isBlank();
            case "detached", "bare" -> value.isEmpty();
            case "locked", "prunable" -> true;
            default -> false;
        };
    }

    private static Worktree toWorktree(Map<String, String> fields) {
        String worktree = fields.get("worktree");
        if (worktree == null) {
            throw new IllegalArgumentException("Worktree porcelain entry is missing worktree");
        }
        if (fields.containsKey("branch") && fields.containsKey("detached")) {
            throw new IllegalArgumentException("Worktree porcelain entry cannot be both branch and detached: " + worktree);
        }
        return new Worktree(Path.of(worktree), fields.get("HEAD"), fields.get("branch"));
    }

    private static OracleContract readContract(Path currentWorktree) throws IOException {
        Path contract = currentWorktree.resolve("contracts/node-oracle.json");
        Map<String, Object> fields = McpJsonDefaults.getMapper().readValue(Files.readAllBytes(contract), MAP_TYPE);
        Object branch = fields.get("branch");
        Object commit = fields.get("commit");
        if (!(branch instanceof String branchName) || !(commit instanceof String commitSha) || !"master".equals(branchName) || !commitSha.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Invalid Node oracle contract: " + contract);
        }
        return new OracleContract(branchName, commitSha);
    }

    private static Worktree findOracleWorktree(Path currentWorktree, OracleContract contract) throws IOException, InterruptedException {
        List<Worktree> candidates = parseWorktreePorcelain(run(currentWorktree, List.of("git", "worktree", "list", "--porcelain"), Map.of())).stream().filter(worktree -> MASTER_BRANCH.equals(worktree.branch()) && contract.commit().equals(worktree.head())).map(NodeOracleMaterializer::canonicalPath).toList();
        if (candidates.size() != 1) {
            throw new IllegalStateException("Expected exactly one master worktree at " + contract.commit() + ", found " + candidates.size());
        }
        Worktree oracle = candidates.getFirst();
        if (oracle.path().equals(currentWorktree)) {
            throw new IllegalStateException("Refusing to use the current worktree as the oracle");
        }
        return oracle;
    }

    private static Worktree canonicalPath(Worktree worktree) {
        try {
            return new Worktree(worktree.path().toRealPath(), worktree.head(), worktree.branch());
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to resolve oracle worktree: " + worktree.path(), exception);
        }
    }

    private static Path currentWorktree() throws IOException, InterruptedException {
        Path workingDirectory = Path.of("").toRealPath();
        byte[] output = run(workingDirectory, List.of("git", "rev-parse", "--show-toplevel"), Map.of());
        return Path.of(decodeUtf8(output, "git rev-parse --show-toplevel").strip()).toRealPath();
    }

    static ScratchLayout scratchLayout(Path currentWorktree) throws IOException {
        Path workspaceBoundary = currentWorktree.toRealPath();
        Path parityDirectory = workspaceBoundary.resolve(".superpowers/parity").toAbsolutePath().normalize();
        Path scratchDirectory = workspaceBoundary.resolve(SCRATCH_RELATIVE_PATH).toAbsolutePath().normalize();
        if (!parityDirectory.startsWith(workspaceBoundary) || scratchDirectory.equals(parityDirectory) || !scratchDirectory.startsWith(parityDirectory)) {
            throw new IllegalStateException("Refusing to materialize scratch outside " + parityDirectory + ": " + scratchDirectory);
        }
        rejectLinkedAncestors(workspaceBoundary, scratchDirectory);
        return new ScratchLayout(workspaceBoundary, parityDirectory, scratchDirectory);
    }

    static Path createScratchDirectory(Path currentWorktree) throws IOException {
        ScratchLayout layout = scratchLayout(currentWorktree);
        Files.createDirectories(layout.parityDirectory());
        layout = scratchLayout(currentWorktree);
        Files.createDirectories(layout.scratchDirectory());
        return createScratchDirectory(scratchLayout(currentWorktree));
    }

    private static Path createScratchDirectory(ScratchLayout layout) throws IOException {
        Path scratchDirectory = Files.createTempDirectory(layout.scratchDirectory(), "oracle-").toAbsolutePath().normalize();
        requireScratchInstance(layout, scratchDirectory, false);
        return scratchDirectory;
    }

    static void deleteScratchDirectory(Path currentWorktree, Path scratchDirectory) throws IOException {
        deleteScratchDirectory(scratchLayout(currentWorktree), scratchDirectory);
    }

    @SuppressWarnings("NullableProblems")
    private static void deleteScratchDirectory(ScratchLayout expectedLayout, Path expectedScratchDirectory) throws IOException {
        ScratchLayout layout = scratchLayout(expectedLayout.workspaceBoundary());
        if (!layout.equals(expectedLayout)) {
            throw new IllegalStateException("Scratch layout changed before cleanup: " + expectedLayout.scratchDirectory());
        }
        Path scratchDirectory = requireScratchInstance(layout, expectedScratchDirectory, true);
        if (!Files.exists(scratchDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path realScratch = scratchDirectory.toRealPath();
        Path realScratchRoot = layout.scratchDirectory().toRealPath();
        BasicFileAttributes attributes = Files.readAttributes(scratchDirectory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isDirectory() || !realScratch.getParent().equals(realScratchRoot)) {
            throw new IllegalStateException("Refusing to delete redirected scratch instance: " + scratchDirectory);
        }
        Files.walkFileTree(scratchDirectory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Path requireScratchInstance(ScratchLayout layout, Path scratchDirectory, boolean allowMissing) throws IOException {
        Path normalized = Objects.requireNonNull(scratchDirectory, "scratchDirectory").toAbsolutePath().normalize();
        if (!normalized.startsWith(layout.scratchDirectory()) || !normalized.getParent().equals(layout.scratchDirectory())) {
            throw new IllegalStateException("Refusing to use scratch outside " + layout.scratchDirectory() + ": " + normalized);
        }
        if (!allowMissing || Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            rejectLinkedAncestors(layout.scratchDirectory(), normalized);
        }
        return normalized;
    }

    private static void deleteScratchAfterFailure(ScratchLayout layout, Path scratchDirectory, Throwable failure) {
        if (scratchDirectory == null) {
            return;
        }
        try {
            deleteScratchDirectory(layout, scratchDirectory);
        } catch (IOException | RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static void rejectLinkedAncestors(Path workspaceBoundary, Path target) throws IOException {
        Path candidate = workspaceBoundary;
        for (Path segment : workspaceBoundary.relativize(target)) {
            candidate = candidate.resolve(segment);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            BasicFileAttributes attributes = Files.readAttributes(candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Path canonical = candidate.toRealPath();
            if (attributes.isSymbolicLink() || attributes.isOther() || !canonical.equals(candidate)) {
                throw new IllegalStateException("Refusing to use linked or redirected scratch ancestor: " + candidate);
            }
        }
    }

    private static Map<String, String> npmEnvironment(Path npmUserConfig) {
        Map<String, String> environment = new LinkedHashMap<>(System.getenv());
        environment.entrySet().removeIf(entry -> entry.getKey().equalsIgnoreCase("npm_config_allow_scripts") || entry.getKey().equalsIgnoreCase("npm_config_userconfig"));
        environment.put("NPM_CONFIG_USERCONFIG", npmUserConfig.toAbsolutePath().normalize().toString());
        return environment;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Node oracle materializer is closed");
        }
    }

    private static String npmExecutable() {
        return isWindows() ? "npm.cmd" : "npm";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static byte[] run(Path directory, List<String> command, Map<String, String> environment) throws IOException, InterruptedException {
        return run(directory, command, environment, COMMAND_TIMEOUT);
    }

    static byte[] run(Path directory, List<String> command, Map<String, String> environment, Duration timeout) throws IOException, InterruptedException {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(environment, "environment");
        validatePositiveTimeout(timeout);
        long timeoutNanos = timeout.toNanos();
        if (command.isEmpty()) {
            throw new IllegalArgumentException("Command must not be empty");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        if (!environment.isEmpty()) {
            builder.environment().clear();
            builder.environment().putAll(environment);
        }
        Process process = builder.start();
        ProcessTreeTracker processTree = new ProcessTreeTracker(process.toHandle());
        OutputCapture output = new OutputCapture(process.getInputStream());
        Thread outputPump = Thread.ofVirtual().name("node-oracle-command-output").start(output::pump);
        Throwable failure = null;
        try {
            long remainingNanos = timeoutNanos;
            long deadline = System.nanoTime() + remainingNanos;
            while (!process.waitFor(Math.min(remainingNanos, OUTPUT_PUMP_POLL_INTERVAL.toNanos()), TimeUnit.NANOSECONDS)) {
                processTree.capture();
                IOException outputFailure = output.failure();
                if (outputFailure != null) {
                    throw new IOException("Could not read command output: " + String.join(" ", command), outputFailure);
                }
                remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new IOException("Command timed out after " + timeout + ": " + String.join(" ", command));
                }
            }
            IOException cleanupFailure = processTree.terminateAndAwait();
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
            awaitOutputPump(outputPump, command);
            if (output.failure() != null) {
                throw new IOException("Could not read command output: " + String.join(" ", command), output.failure());
            }
            byte[] captured = output.bytes();
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IOException("Command failed (" + exitCode + "): " + String.join(" ", command) + "\n" + decodeUtf8(captured, String.join(" ", command)));
            }
            return captured;
        } catch (InterruptedException exception) {
            failure = exception;
            appendCleanupFailure(exception, processTree.terminateAndAwait());
            awaitOutputPumpAfterFailure(outputPump);
            Thread.currentThread().interrupt();
            throw exception;
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            appendCleanupFailure(exception, processTree.terminateAndAwait());
            awaitOutputPumpAfterFailure(outputPump);
            throw exception;
        } finally {
            IOException trackerFailure = processTree.stop();
            if (trackerFailure != null) {
                if (failure != null) {
                    failure.addSuppressed(trackerFailure);
                }
                else {
                    throw trackerFailure;
                }
            }
        }
    }

    private static void validatePositiveTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    private static void awaitOutputPump(Thread outputPump, List<String> command) throws IOException, InterruptedException {
        if (!outputPump.join(PROCESS_STOP_TIMEOUT)) {
            outputPump.interrupt();
            throw new IOException("Command output pump did not stop: " + String.join(" ", command));
        }
    }

    private static void awaitOutputPumpAfterFailure(Thread outputPump) {
        boolean interrupted = Thread.interrupted();
        try {
            if (!outputPump.join(PROCESS_STOP_TIMEOUT)) {
                outputPump.interrupt();
            }
        } catch (InterruptedException exception) {
            interrupted = true;
            outputPump.interrupt();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void appendCleanupFailure(Throwable failure, IOException cleanupFailure) {
        if (cleanupFailure != null) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static void verifyOriginalUnchanged(OracleSnapshot snapshot, Throwable failure) {
        try {
            snapshot.assertUnchanged();
        } catch (RuntimeException guardFailure) {
            failure.addSuppressed(guardFailure);
        }
    }

    private static String decodeUtf8(byte[] bytes, String source) {
        try {
            CharBuffer characters = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return characters.toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Invalid UTF-8 from " + source, exception);
        }
    }

    record Worktree(Path path, String head, String branch) {
        Worktree {
            Objects.requireNonNull(path, "path");
        }
    }

    record ScratchLayout(Path workspaceBoundary, Path parityDirectory, Path scratchDirectory) {
    }

    private record OracleContract(String branch, String commit) {
    }

    private record OracleSnapshot(Path path, String branch, String head, byte[] status) {
        static OracleSnapshot capture(Path path) throws IOException, InterruptedException {
            String branch = decodeUtf8(run(path, List.of("git", "symbolic-ref", "--quiet", "HEAD"), Map.of()), "git symbolic-ref --quiet HEAD").strip();
            String head = decodeUtf8(run(path, List.of("git", "rev-parse", "HEAD"), Map.of()), "git rev-parse HEAD").strip();
            byte[] status = run(path, List.of("git", "status", "--porcelain=v1", "--untracked-files=all"), Map.of());
            return new OracleSnapshot(path, branch, head, status);
        }

        void assertUnchanged() {
            try {
                OracleSnapshot after = capture(path);
                if (!branch.equals(after.branch) || !head.equals(after.head) || !Arrays.equals(status, after.status)) {
                    throw new IllegalStateException("Oracle worktree changed during parity execution: " + path);
                }
            } catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("Unable to verify oracle worktree: " + path, exception);
            }
        }
    }

    private static final class OutputCapture {
        private final InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final AtomicReference<IOException> failure = new AtomicReference<>();

        private OutputCapture(InputStream input) {
            this.input = input;
        }

        private void pump() {
            try (input) {
                input.transferTo(output);
            } catch (IOException exception) {
                failure.set(exception);
            }
        }

        private byte[] bytes() {
            return output.toByteArray();
        }

        private IOException failure() {
            return failure.get();
        }
    }

    private static final class ProcessTreeTracker {
        private static final Duration POLL_INTERVAL = Duration.ofMillis(1);

        private final ProcessHandle root;
        private final Map<Long, ProcessHandle> observed = new ConcurrentHashMap<>();
        private final AtomicBoolean tracking = new AtomicBoolean(true);
        private final Thread monitor;

        private ProcessTreeTracker(ProcessHandle root) {
            this.root = root;
            observed.put(root.pid(), root);
            capture();
            monitor = Thread.ofVirtual().name("node-oracle-process-tree").start(this::monitor);
        }

        private void monitor() {
            while (tracking.get()) {
                capture();
                try {
                    Thread.sleep(POLL_INTERVAL);
                } catch (InterruptedException exception) {
                    return;
                }
            }
        }

        private void capture() {
            int previousSize;
            do {
                previousSize = observed.size();
                List<ProcessHandle> known = List.copyOf(observed.values());
                for (ProcessHandle process : known) {
                    process.descendants().forEach(descendant -> observed.putIfAbsent(descendant.pid(), descendant));
                }
            } while (observed.size() != previousSize);
        }

        private IOException terminateAndAwait() {
            boolean interrupted = Thread.interrupted();
            long deadline = System.nanoTime() + PROCESS_STOP_TIMEOUT.toNanos();
            try {
                while (true) {
                    capture();
                    List<ProcessHandle> alive = observed.values().stream().filter(ProcessHandle::isAlive).toList();
                    if (alive.isEmpty()) {
                        return null;
                    }
                    alive.stream().filter(handle -> handle.pid() != root.pid()).forEach(ProcessHandle::destroyForcibly);
                    if (root.isAlive()) {
                        root.destroyForcibly();
                    }
                    long remainingNanos = deadline - System.nanoTime();
                    if (remainingNanos <= 0) {
                        String pids = observed.values().stream().filter(ProcessHandle::isAlive).map(handle -> Long.toString(handle.pid())).sorted().reduce((left, right) -> left + ", " + right).orElse("unknown");
                        return new IOException("Command process tree remained alive after cleanup: " + pids);
                    }
                    try {
                        TimeUnit.NANOSECONDS.sleep(Math.min(remainingNanos, POLL_INTERVAL.toNanos()));
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private IOException stop() {
            boolean interrupted = Thread.interrupted();
            tracking.set(false);
            monitor.interrupt();
            long deadline = System.nanoTime() + PROCESS_STOP_TIMEOUT.toNanos();
            try {
                while (monitor.isAlive()) {
                    long remainingNanos = deadline - System.nanoTime();
                    if (remainingNanos <= 0) {
                        return new IOException("Command process-tree monitor did not stop");
                    }
                    try {
                        monitor.join(Duration.ofNanos(remainingNanos));
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
                return null;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}

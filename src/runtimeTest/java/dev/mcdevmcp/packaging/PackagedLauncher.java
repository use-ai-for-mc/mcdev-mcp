package dev.mcdevmcp.packaging;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import reactor.util.annotation.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Drives the launcher of an unpacked installation the way an MCP client does: no JDK on the
 * command line and no flags of its own. The first session must answer on a clean standard output
 * and start the recording of the startup cache; once the cache is published a second session must
 * still answer.
 */
final class PackagedLauncher {
    static final String APPLICATION = "mcdev-mcp";
    private static final String CACHE = APPLICATION + ".aot";
    private static final Duration SESSION_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration CACHE_TIMEOUT = Duration.ofSeconds(180);
    private static final McpJsonMapper JSON = McpJsonDefaults.getMapper();
    private static final TypeRef<Map<String, Object>> MESSAGE = new TypeRef<>() {
    };
    private static final List<String> EXCHANGE = List.of("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"packaged-launcher-smoke\",\"version\":\"1\"}}}", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");

    private PackagedLauncher() {
    }

    /**
     * Copies what was packaged into the work directory, so that the startup cache is recorded
     * beside the copy and the package itself stays as it was built.
     */
    static Path install(Path packaged, Path work) throws IOException {
        deleteTree(work);
        Path installation = work.resolve("installation");
        copyTree(packaged, installation);
        return installation;
    }

    static Sessions coldThenCached(Path work, Path installation, List<String> command, Map<String, String> environment) throws Exception {
        Path cache = applicationDirectory(installation).resolve(CACHE);
        require(!Files.exists(cache), "The package must not ship a startup cache: " + cache);
        try (var watcher = cache.getFileSystem().newWatchService()) {
            cache.getParent().register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            Exchange cold = session(work, command, environment, "first");
            long deadline = System.nanoTime() + CACHE_TIMEOUT.toNanos();
            Path identity = cache.resolveSibling(CACHE + ".id");
            while (!Files.isRegularFile(cache) || !Files.isRegularFile(identity)) {
                long remaining = deadline - System.nanoTime();
                require(remaining > 0, "No startup cache was recorded within " + CACHE_TIMEOUT.toSeconds() + " seconds");
                var key = watcher.poll(remaining, TimeUnit.NANOSECONDS);
                if (key != null) {
                    key.pollEvents();
                    key.reset();
                }
            }
            return new Sessions(cold, session(work, command, environment, "second"));
        }
    }

    /**
     * A launch that neither inherits JVM options from the machine running the check nor touches
     * that machine's own mcdev-mcp data.
     */
    static ProcessBuilder command(Path work, List<String> command) {
        var builder = new ProcessBuilder(command);
        var environment = builder.environment();
        environment.remove("JAVA_TOOL_OPTIONS");
        environment.remove("JDK_JAVA_OPTIONS");
        environment.put("LOCALAPPDATA", work.resolve("user").toString());
        environment.put("XDG_CACHE_HOME", work.resolve("user").toString());
        return builder;
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static Path applicationDirectory(Path installation) throws IOException {
        try (Stream<Path> files = Files.walk(installation)) {
            return files.filter(file -> file.getFileName().toString().equals(APPLICATION + ".cfg")).findFirst().orElseThrow(() -> new IllegalStateException("No launcher configuration found in " + installation)).getParent();
        }
    }

    @SuppressWarnings("preview")
    private static Exchange session(Path work, List<String> command, Map<String, String> environment, String label) throws Exception {
        Path errors = work.resolve(label + "-stderr.txt");
        ProcessBuilder builder = command(work, command).redirectError(errors.toFile());
        builder.environment().putAll(environment);
        long started = System.nanoTime();
        Exchange exchanged;
        try (Process process = builder.start()) {
            try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<Exchange>awaitAllSuccessfulOrThrow(), config -> config.withTimeout(SESSION_TIMEOUT))) {
                var exchange = scope.fork(() -> converse(process, started));
                scope.join();
                exchanged = exchange.get();
            } finally {
                if (process.isAlive() && !process.waitFor(SESSION_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
            require(process.exitValue() == 0, "The " + label + " session ended with exit code " + process.exitValue());
        }
        String reported = Files.readString(errors, StandardCharsets.UTF_8);
        require(reported.isBlank(), "The " + label + " session wrote to standard error: " + reported);
        return exchanged;
    }

    private static Exchange converse(Process process, long started) throws IOException {
        try (Writer writer = process.outputWriter(StandardCharsets.UTF_8); BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
            for (String message : EXCHANGE) {
                writer.write(message);
                writer.write('\n');
            }
            writer.flush();
            Map<String, Object> initialized = JSON.readValue(requireLine(reader), MESSAGE);
            long answered = Duration.ofNanos(System.nanoTime() - started).toMillis();
            require(initialized.get("result") instanceof Map<?, ?> result && result.get("serverInfo") instanceof Map<?, ?>, "initialize did not succeed: " + initialized);
            Map<String, Object> listed = JSON.readValue(requireLine(reader), MESSAGE);
            List<String> tools = toolNames(listed);
            require(!tools.isEmpty(), "tools/list returned no tools: " + listed);
            return new Exchange(answered, serverField(initialized, "name"), serverField(initialized, "version"), tools);
        }
    }

    private static String serverField(Map<String, Object> initialized, String field) {
        return initialized.get("result") instanceof Map<?, ?> result && result.get("serverInfo") instanceof Map<?, ?> server && server.get(field) instanceof String value ? value : "";
    }

    private static List<String> toolNames(Map<String, Object> listed) {
        if (!(listed.get("result") instanceof Map<?, ?> result) || !(result.get("tools") instanceof List<?> tools)) {
            return List.of();
        }
        return tools.stream().map(tool -> tool instanceof Map<?, ?> described && described.get("name") instanceof String name ? name : "").sorted().toList();
    }

    private static String requireLine(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        require(line != null && line.startsWith("{"), "Standard output carried something other than a JSON-RPC message: " + line);
        return line;
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.toList()) {
                Path copy = target.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(copy);
                } else {
                    Files.copy(file, copy, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            @NonNull
            public FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            @NonNull
            public FileVisitResult postVisitDirectory(@NonNull Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * What one session reported: how long the launcher took to answer {@code initialize}, the
     * server it identified itself as, and the sorted names of the tools it listed.
     */
    value record Exchange(long answeredMillis, String serverName, String serverVersion, List<String> tools) {
        Exchange {
            tools = List.copyOf(tools);
        }
    }

    value record Sessions(Exchange cold, Exchange cached) {
    }
}

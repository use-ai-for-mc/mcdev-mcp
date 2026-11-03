package dev.mcdevmcp.app;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import jdk.management.HotSpotAOTCacheMXBean;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.Pipe;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.StructuredTaskScope;

/**
 * The process {@link StartupCache} starts to record an ahead-of-time cache: it runs the real
 * {@code serve} path against in-process pipes, performs the exchange every MCP client opens with,
 * ends the recording and publishes the cache next to its identity.
 */
final class StartupCacheTrainer {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final McpJsonMapper JSON = McpJsonDefaults.getMapper();
    private static final TypeRef<Map<String, Object>> MESSAGE = new TypeRef<>() {
    };
    private static final List<String> EXCHANGE = List.of("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"startup-cache\",\"version\":\"1\"}}}", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}", "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/list\",\"params\":{}}");
    private static final int LAST_REQUEST = 3;

    private StartupCacheTrainer() {
    }

    static void main(String[] arguments) {
        System.exit(run(Path.of(arguments[0])));
    }

    private static int run(Path cache) {
        try (FileChannel channel = FileChannel.open(StartupCache.lockFile(cache), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock != null) {
                record(cache);
            }
            return 0;
        } catch (Exception _) {
            return 1;
        }
    }

    private static void record(Path cache) throws Exception {
        String identity = StartupCache.identity(System.getProperty("java.class.path"));
        serveOneSession();
        if (!ManagementFactory.getPlatformMXBean(HotSpotAOTCacheMXBean.class).endRecording()) {
            throw new IllegalStateException("The JVM was not recording an ahead-of-time cache");
        }
        Files.move(StartupCache.recording(cache), cache, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(StartupCache.identityFile(cache), identity, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("preview")
    private static void serveOneSession() throws Exception {
        Pipe requests = Pipe.open();
        Pipe responses = Pipe.open();
        System.setIn(Channels.newInputStream(requests.source()));
        System.setOut(new PrintStream(Channels.newOutputStream(responses.sink()), true, StandardCharsets.UTF_8));
        var discarded = new PrintWriter(OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8);
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<Integer>awaitAllSuccessfulOrThrow(), config -> config.withName("startup-cache").withTimeout(TIMEOUT))) {
            var served = scope.fork(() -> Main.execute(new String[]{"serve"}, Runtime.version().feature(), discarded, discarded));
            scope.fork(() -> converse(requests, responses));
            scope.join();
            if (served.get() != 0) {
                throw new IllegalStateException("The recorded session ended with exit code " + served.get());
            }
        }
    }

    private static Integer converse(Pipe requests, Pipe responses) throws IOException {
        try (Writer writer = Channels.newWriter(requests.sink(), StandardCharsets.UTF_8);
             BufferedReader reader = new BufferedReader(new InputStreamReader(Channels.newInputStream(responses.source()), StandardCharsets.UTF_8))) {
            for (String message : EXCHANGE) {
                writer.write(message);
                writer.write('\n');
            }
            writer.flush();
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (JSON.readValue(line, MESSAGE).get("id") instanceof Number id && id.intValue() == LAST_REQUEST) {
                    return 0;
                }
            }
            throw new IOException("The recorded session closed before answering every request");
        }
    }
}

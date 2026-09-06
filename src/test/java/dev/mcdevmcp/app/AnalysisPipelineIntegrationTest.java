package dev.mcdevmcp.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mcdevmcp.analysis.callgraph.CallgraphScanner;
import dev.mcdevmcp.analysis.callgraph.CallgraphSummary;
import dev.mcdevmcp.analysis.decompile.*;
import dev.mcdevmcp.analysis.index.IndexSummary;
import dev.mcdevmcp.analysis.index.SourceIndexer;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.callgraph.CallgraphManifest;
import dev.mcdevmcp.storage.callgraph.CallgraphPointer;
import dev.mcdevmcp.storage.callgraph.CallgraphRepository;
import dev.mcdevmcp.storage.h2.VersionStateRepository;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.VersionState;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.*;

final class AnalysisPipelineIntegrationTest {
    @TempDir
    Path temporaryDirectory;

    private static AnalysisPipeline pipeline(PlatformPaths paths, HttpServer server) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        URI manifest = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/manifest");
        return new AnalysisPipeline(paths, new VersionManifestClient(http, McpJsonDefaults.getMapper(), manifest, Duration.ofSeconds(2)), new DownloadService(http, Duration.ofSeconds(2)), new MappingConverter(), new MinecraftRemapper(1), new MinecraftDecompiler(), new SourceIndexer(), new CallgraphScanner(), 1);
    }

    private static HttpServer server() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        return server;
    }

    private static byte[] compileJar(Path root, String binaryName, String sourceText) throws IOException {
        Path source = root.resolve("source").resolve(binaryName.replace('.', '/') + ".java");
        Path classes = root.resolve("classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, sourceText, StandardCharsets.UTF_8);
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-g:none", "-d", classes.toString(), source.toString());
        if (result != 0) {
            throw new IOException("Fixture compilation failed with exit code " + result);
        }
        var bytes = new ByteArrayOutputStream();
        try (var output = new JarOutputStream(bytes); var compiled = Files.walk(classes)) {
            for (Path file : compiled.filter(Files::isRegularFile).sorted().toList()) {
                String name = classes.relativize(file).toString().replace('\\', '/');
                output.putNextEntry(new JarEntry(name));
                Files.copy(file, output);
                output.closeEntry();
            }
            output.putNextEntry(new JarEntry("assets/fixture.txt"));
            output.write("resource".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static byte[] corruptClassJarBytes() throws IOException {
        byte[] content = "corruptible-class-data".getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(content);
        var bytes = new ByteArrayOutputStream();
        try (var output = new JarOutputStream(bytes)) {
            JarEntry entry = new JarEntry("sample/Example.class");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(content.length);
            entry.setCompressedSize(content.length);
            entry.setCrc(crc.getValue());
            output.putNextEntry(entry);
            output.write(content);
            output.closeEntry();
        }
        byte[] corrupt = bytes.toByteArray();
        for (int index = 0; index <= corrupt.length - content.length; index++) {
            if (Arrays.equals(corrupt, index, index + content.length, content, 0, content.length)) {
                corrupt[index] ^= 1;
                return corrupt;
            }
        }
        throw new AssertionError("stored ZIP fixture did not contain its entry bytes");
    }

    private static void respond(HttpExchange exchange, byte[] body) throws IOException {
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String sha1(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static CallgraphManifest publishedManifest(Path bundle) throws Exception {
        var mapper = McpJsonDefaults.getMapper();
        CallgraphPointer pointer = mapper.readValue(Files.readAllBytes(bundle.resolve("current.json")), CallgraphPointer.class);
        Path manifest = bundle.resolve("generations").resolve(pointer.generation()).resolve("manifest.json");
        return mapper.readValue(Files.readAllBytes(manifest), CallgraphManifest.class);
    }

    private static void assertOrdered(List<String> progress, List<String> expected) {
        int prior = -1;
        for (String prefix : expected) {
            int index = -1;
            for (int candidate = prior + 1; candidate < progress.size(); candidate++) {
                if (progress.get(candidate).startsWith(prefix)) {
                    index = candidate;
                    break;
                }
            }
            assertTrue(index > prior, () -> "Missing ordered progress " + prefix + " in " + progress);
            prior = index;
        }
    }

    @Test
    void preparesMappedLayersOnceThenRebuildsIndexAndCallgraphOffline() throws Exception {
        MinecraftVersion version = new MinecraftVersion("1.21.5");
        byte[] clientJar = compileJar(temporaryDirectory.resolve("mapped-fixture"), "a", "public class a { public int a(int left, int right) { return Math.addExact(left, right); } }");
        byte[] mapping = """
                         sample.Example -> a:
                             int add(int,int) -> a
                         """.getBytes(StandardCharsets.UTF_8);
        AtomicInteger clientRequests = new AtomicInteger();
        AtomicInteger mappingRequests = new AtomicInteger();
        HttpServer server = server();
        PlatformPaths paths = new PlatformPaths(temporaryDirectory.resolve("cache-root"));
        List<String> progress = new ArrayList<>();
        PreparedSources prepared;
        String clientSha1 = sha1(clientJar);
        String mappingSha1 = sha1(mapping);
        try {
            int port = server.getAddress().getPort();
            server.createContext("/manifest", exchange -> respond(exchange, """
                                                                            {"versions":[{"id":"1.21.5","url":"http://127.0.0.1:%d/version"}]}
                                                                            """.formatted(port).getBytes(StandardCharsets.UTF_8)));
            server.createContext("/version", exchange -> respond(exchange, """
                                                                           {"downloads":{
                                                                             "client":{"url":"http://127.0.0.1:%d/client","sha1":"%s","size":%d},
                                                                             "client_mappings":{"url":"http://127.0.0.1:%d/mappings","sha1":"%s","size":%d}
                                                                           }}
                                                                           """.formatted(port, clientSha1, clientJar.length, port, mappingSha1, mapping.length).getBytes(StandardCharsets.UTF_8)));
            server.createContext("/client", exchange -> {
                clientRequests.incrementAndGet();
                respond(exchange, clientJar);
            });
            server.createContext("/mappings", exchange -> {
                mappingRequests.incrementAndGet();
                respond(exchange, mapping);
            });

            AnalysisPipeline pipeline = pipeline(paths, server);
            prepared = pipeline.prepareSources(version, (stage, percent, _) -> progress.add(stage + ":" + percent), Cancellation.none());

            Path remapped = paths.remappedJar(version).toAbsolutePath().normalize();
            assertEquals(remapped, prepared.unobfuscatedJar());
            assertEquals(remapped, prepared.remappedJar());
            assertNotEquals(prepared.obfuscatedJar(), prepared.remappedJar());
            try (JarFile jar = new JarFile(remapped.toFile())) {
                assertNotNull(jar.getEntry("sample/Example.class"));
                assertNotNull(jar.getEntry("assets/fixture.txt"));
            }
            Path sourceMarker = prepared.sourceRoots().getFirst().path().resolve("cache-hit.marker");
            Files.writeString(sourceMarker, "preserve");

            PreparedSources cached = pipeline.prepareSources(version, (_, _, _) -> {
            }, Cancellation.none());
            assertEquals(prepared, cached);
            assertTrue(Files.exists(sourceMarker));
            assertEquals(1, clientRequests.get());
            assertEquals(1, mappingRequests.get());

            byte[] completeRemapped = Files.readAllBytes(remapped);
            Files.write(remapped, corruptClassJarBytes());
            try (JarFile centralDirectoryOnly = new JarFile(remapped.toFile())) {
                assertNotNull(centralDirectoryOnly.getEntry("sample/Example.class"));
            }
            IllegalStateException rebuildFailure = assertThrows(IllegalStateException.class, () -> pipeline.rebuildIndex(version, (_, _, _) -> {
            }, Cancellation.none()));
            assertTrue(rebuildFailure.getMessage().contains("No prepared remapped JAR cache"), rebuildFailure.getMessage());
            PreparedSources repaired = pipeline.prepareSources(version, (_, _, _) -> {
            }, Cancellation.none());
            assertEquals(prepared, repaired);
            assertArrayEquals(completeRemapped, Files.readAllBytes(remapped));
            assertEquals(1, clientRequests.get());
            assertEquals(1, mappingRequests.get());

            server.stop(0);
            Files.delete(prepared.obfuscatedJar());
            Files.deleteIfExists(paths.versionCache(version).resolve("jars/client.txt"));
            Files.deleteIfExists(paths.versionCache(version).resolve("jars/client.tiny"));

            IndexSummary index = pipeline.rebuildIndex(version, (stage, percent, _) -> progress.add(stage + ":" + percent), Cancellation.none());
            CallgraphSummary callgraph = pipeline.rebuildCallgraph(version, (stage, percent, _) -> progress.add(stage + ":" + percent), Cancellation.none());

            assertTrue(index.types() >= 1);
            assertEquals(1, callgraph.classes());
            assertTrue(callgraph.edges() >= 1);
            assertEquals(VersionState.READY, new VersionStateRepository(paths).state(version));
            assertTrue(CallgraphRepository.isPublished(paths.callgraphBundle(version)));
            assertEquals(sha256(remapped), publishedManifest(paths.callgraphBundle(version)).remappedJarSha256());
            assertOrdered(progress, List.of("metadata:0", "mapping:0", "remap:0", "decompile:0", "index:0", "callgraph:0"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usesOfficialUnobfuscatedManifestArtifactAndPublishesStableRemappedCopy() throws Exception {
        MinecraftVersion version = new MinecraftVersion("1.21.11");
        byte[] obfuscatedJar = compileJar(temporaryDirectory.resolve("official-obfuscated-fixture"), "a", "public class a { public String a() { return \"obfuscated\"; } }");
        byte[] unobfuscatedJar = compileJar(temporaryDirectory.resolve("official-unobfuscated-fixture"), "sample.Example", "package sample; public class Example { public String value() { return \"unobfuscated\"; } }");
        AtomicInteger obfuscatedRequests = new AtomicInteger();
        AtomicInteger unobfuscatedRequests = new AtomicInteger();
        HttpServer server = server();
        PlatformPaths paths = new PlatformPaths(temporaryDirectory.resolve("official-cache-root"));
        String obfuscatedSha1 = sha1(obfuscatedJar);
        String unobfuscatedSha1 = sha1(unobfuscatedJar);
        try {
            int port = server.getAddress().getPort();
            server.createContext("/manifest", exchange -> respond(exchange, """
                                                                            {"versions":[
                                                                              {"id":"1.21.11","url":"http://127.0.0.1:%1$d/version"},
                                                                              {"id":"1.21.11_unobfuscated","url":"http://127.0.0.1:%1$d/unobfuscated-version"}
                                                                            ]}
                                                                            """.formatted(port).getBytes(StandardCharsets.UTF_8)));
            server.createContext("/version", exchange -> respond(exchange, """
                                                                           {"downloads":{"client":{
                                                                             "url":"http://127.0.0.1:%d/client","sha1":"%s","size":%d
                                                                           }}}
                                                                           """.formatted(port, obfuscatedSha1, obfuscatedJar.length).getBytes(StandardCharsets.UTF_8)));
            server.createContext("/unobfuscated-version", exchange -> respond(exchange, """
                                                                                        {"downloads":{"client":{
                                                                                          "url":"http://127.0.0.1:%d/unobfuscated-client","sha1":"%s","size":%d
                                                                                        }}}
                                                                                        """.formatted(port, unobfuscatedSha1, unobfuscatedJar.length).getBytes(StandardCharsets.UTF_8)));
            server.createContext("/client", exchange -> {
                obfuscatedRequests.incrementAndGet();
                respond(exchange, obfuscatedJar);
            });
            server.createContext("/unobfuscated-client", exchange -> {
                unobfuscatedRequests.incrementAndGet();
                respond(exchange, unobfuscatedJar);
            });

            AnalysisPipeline pipeline = pipeline(paths, server);
            PreparedSources prepared = pipeline.prepareSources(version, (_, _, _) -> {
            }, Cancellation.none());

            Path stableRemapped = paths.remappedJar(version).toAbsolutePath().normalize();
            Path officialDownload = paths.versionCache(version).resolve("jars/client-unobfuscated.jar").toAbsolutePath().normalize();
            assertEquals(officialDownload, prepared.unobfuscatedJar());
            assertEquals(stableRemapped, prepared.remappedJar());
            assertNotEquals(prepared.obfuscatedJar(), prepared.unobfuscatedJar());
            assertNotEquals(prepared.unobfuscatedJar(), prepared.remappedJar());
            assertArrayEquals(obfuscatedJar, Files.readAllBytes(prepared.obfuscatedJar()));
            assertArrayEquals(unobfuscatedJar, Files.readAllBytes(prepared.unobfuscatedJar()));
            assertArrayEquals(unobfuscatedJar, Files.readAllBytes(prepared.remappedJar()));
            assertTrue(Files.isRegularFile(prepared.sourceRoots().getFirst().path().resolve("sample/Example.java")));

            PreparedSources cached = pipeline.prepareSources(version, (_, _, _) -> {
            }, Cancellation.none());
            assertEquals(prepared, cached);
            assertEquals(1, obfuscatedRequests.get());
            assertEquals(1, unobfuscatedRequests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void publishesModernUnobfuscatedClientAsDistinctStableRemappedArtifact() throws Exception {
        MinecraftVersion version = new MinecraftVersion("26.1");
        byte[] clientJar = compileJar(temporaryDirectory.resolve("modern-fixture"), "sample.Example", "package sample; public class Example { public String value() { return \"modern\"; } }");
        AtomicInteger clientRequests = new AtomicInteger();
        HttpServer server = server();
        PlatformPaths paths = new PlatformPaths(temporaryDirectory.resolve("modern-cache-root"));
        String clientSha1 = sha1(clientJar);
        try {
            int port = server.getAddress().getPort();
            server.createContext("/manifest", exchange -> respond(exchange, """
                                                                            {"versions":[{"id":"26.1","url":"http://127.0.0.1:%d/version"}]}
                                                                            """.formatted(port).getBytes(StandardCharsets.UTF_8)));
            server.createContext("/version", exchange -> respond(exchange, """
                                                                           {"downloads":{
                                                                             "client":{"url":"http://127.0.0.1:%d/client","sha1":"%s","size":%d}
                                                                           }}
                                                                           """.formatted(port, clientSha1, clientJar.length).getBytes(StandardCharsets.UTF_8)));
            server.createContext("/client", exchange -> {
                clientRequests.incrementAndGet();
                respond(exchange, clientJar);
            });

            AnalysisPipeline pipeline = pipeline(paths, server);
            PreparedSources prepared = pipeline.prepareSources(version, (_, _, _) -> {
            }, Cancellation.none());

            assertEquals(prepared.obfuscatedJar(), prepared.unobfuscatedJar());
            assertNotEquals(prepared.unobfuscatedJar(), prepared.remappedJar());
            assertEquals(paths.remappedJar(version).toAbsolutePath().normalize(), prepared.remappedJar());
            assertArrayEquals(Files.readAllBytes(prepared.unobfuscatedJar()), Files.readAllBytes(prepared.remappedJar()));
            assertTrue(Files.isRegularFile(prepared.sourceRoots().getFirst().path().resolve("sample/Example.java")));

            pipeline.prepareSources(version, (_, _, _) -> {
            }, Cancellation.none());
            assertEquals(1, clientRequests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preparedSourcesNormalizesPathsAndDefensivelyCopiesRoots() throws Exception {
        Path source = Files.createDirectories(temporaryDirectory.resolve("prepared/source"));
        Files.writeString(source.resolve("Example.java"), "class Example {}");
        var roots = new ArrayList<dev.mcdevmcp.analysis.index.SourceRoot>();
        roots.add(new dev.mcdevmcp.analysis.index.SourceRoot(dev.mcdevmcp.storage.model.SourceNamespace.MINECRAFT, java.util.Optional.empty(), source));

        PreparedSources prepared = new PreparedSources(new MinecraftVersion("1.21.5"), roots, Path.of("client.jar"), Path.of("unobfuscated.jar"), Path.of("remapped.jar"));
        roots.clear();

        assertEquals(1, prepared.sourceRoots().size());
        assertTrue(prepared.obfuscatedJar().isAbsolute());
        assertTrue(prepared.unobfuscatedJar().isAbsolute());
        assertTrue(prepared.remappedJar().isAbsolute());
    }
}

package dev.mcdevmcp.app;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mcdevmcp.analysis.callgraph.CallgraphScanner;
import dev.mcdevmcp.analysis.callgraph.CallgraphSummary;
import dev.mcdevmcp.analysis.decompile.*;
import dev.mcdevmcp.analysis.index.IndexSummary;
import dev.mcdevmcp.analysis.index.SourceIndexer;
import dev.mcdevmcp.analysis.index.SourceRoot;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.callgraph.CallgraphManifest;
import dev.mcdevmcp.storage.callgraph.CallgraphPointer;
import dev.mcdevmcp.storage.callgraph.CallgraphRepository;
import dev.mcdevmcp.storage.symbols.SymbolIndexFiles;
import dev.mcdevmcp.storage.symbols.SymbolRepository;
import dev.mcdevmcp.storage.symbols.VersionStateRepository;
import dev.mcdevmcp.storage.migration.DirectoryAliasFixture;
import dev.mcdevmcp.storage.migration.SourceOwnership;
import dev.mcdevmcp.storage.migration.SourceProvenance;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.SourceNamespace;
import dev.mcdevmcp.storage.model.VersionState;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

    static AnalysisPipeline pipeline(PlatformPaths paths, HttpServer server) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        URI manifest = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/manifest");
        return new AnalysisPipeline(paths, new VersionManifestClient(http, McpJsonDefaults.getMapper(), manifest, Duration.ofSeconds(2)), new DownloadService(http, Duration.ofSeconds(2)), new MappingConverter(), new MinecraftRemapper(1), new MinecraftDecompiler(), new SourceIndexer(), new CallgraphScanner(), 1);
    }

    static HttpServer server() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        return server;
    }

    static byte[] compileJar(Path root, String binaryName, String sourceText) throws IOException {
        Path source = root.resolve("source").resolve(binaryName.replace('.', '/') + ".java");
        Path classes = root.resolve("classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, sourceText, StandardCharsets.UTF_8);
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-g:source", "-d", classes.toString(), source.toString());
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

    static void respond(HttpExchange exchange, byte[] body) throws IOException {
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    static String sha1(byte[] bytes) throws Exception {
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preparesMappedLayersOnceThenRebuildsIndexAndCallgraphOffline(boolean ancestorAlias) throws Exception {
        MinecraftVersion version = new MinecraftVersion("1.21.5");
        byte[] clientJar = compileJar(temporaryDirectory.resolve("mapped-fixture"), "a", "public class a { public int a(int left, int right) { return Math.addExact(left, right); } }");
        byte[] mapping = """
                         sample.Example -> a:
                             int add(int,int) -> a
                         """.getBytes(StandardCharsets.UTF_8);
        AtomicInteger clientRequests = new AtomicInteger();
        AtomicInteger mappingRequests = new AtomicInteger();
        HttpServer server = server();
        Path physicalParent = Files.createDirectory(temporaryDirectory.resolve("physical-parent"));
        Path alias = temporaryDirectory.resolve("alias-parent");
        if (ancestorAlias) DirectoryAliasFixture.create(alias, physicalParent);
        PlatformPaths paths = new PlatformPaths((ancestorAlias ? alias : physicalParent).resolve("cache-root"));
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
            prepared = pipeline.initialize(version, SourceRefreshPolicy.NORMAL, (stage, percent, _) -> progress.add(stage + ":" + percent), Cancellation.none()).sources();

            Path remapped = paths.remappedJar(version).toRealPath();
            assertEquals(remapped, prepared.unobfuscatedJar());
            assertEquals(remapped, prepared.remappedJar());
            assertNotEquals(prepared.obfuscatedJar(), prepared.remappedJar());
            try (JarFile jar = new JarFile(remapped.toFile())) {
                assertNotNull(jar.getEntry("sample/Example.class"));
                assertNotNull(jar.getEntry("assets/fixture.txt"));
            }
            Path sourceMarker = prepared.sourceRoots().getFirst().path().resolve("cache-hit.marker");
            Files.writeString(sourceMarker, "preserve");

            PreparedSources cached = pipeline.initialize(version, SourceRefreshPolicy.NORMAL, (_, _, _) -> {
            }, Cancellation.none()).sources();
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
            PreparedSources repaired = pipeline.initialize(version, SourceRefreshPolicy.NORMAL, (_, _, _) -> {
            }, Cancellation.none()).sources();
            assertEquals(prepared, repaired);
            assertArrayEquals(completeRemapped, Files.readAllBytes(remapped));
            assertEquals(1, clientRequests.get());
            assertEquals(1, mappingRequests.get());

            server.stop(0);
            Files.delete(prepared.obfuscatedJar());
            Files.deleteIfExists(paths.versionCache(version).resolve("jars/client.txt"));
            Files.deleteIfExists(paths.versionCache(version).resolve("jars/client.tiny"));
            Path supersededDatabase = paths.indexRoot(version).resolve("symbols.mv.db");
            Files.writeString(supersededDatabase, "database from an earlier development build");

            IndexSummary index = pipeline.rebuildIndex(version, (stage, percent, _) -> progress.add(stage + ":" + percent), Cancellation.none());
            CallgraphSummary callgraph = pipeline.rebuildCallgraph(version, (stage, percent, _) -> progress.add(stage + ":" + percent), Cancellation.none());

            assertTrue(index.types() >= 1);
            assertFalse(Files.exists(supersededDatabase));
            assertEquals(1, callgraph.classes());
            assertTrue(callgraph.edges() >= 1);
            assertEquals(VersionState.READY, new VersionStateRepository(paths).state(version));
            assertTrue(CallgraphRepository.isPublished(paths.callgraphBundle(version)));
            assertEquals(sha256(remapped), publishedManifest(paths.callgraphBundle(version)).remappedJarSha256());
            assertOrdered(progress, List.of("metadata:0", "mapping:0", "remap:0", "decompile:0", "index:0", "callgraph:0"));
        } finally {
            server.stop(0);
            if (ancestorAlias) DirectoryAliasFixture.remove(alias);
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
            PreparedSources prepared = pipeline.initialize(version, SourceRefreshPolicy.NORMAL, (_, _, _) -> {
            }, Cancellation.none()).sources();

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

            PreparedSources cached = pipeline.initialize(version, SourceRefreshPolicy.NORMAL, (_, _, _) -> {
            }, Cancellation.none()).sources();
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
            PreparedSources prepared = pipeline.initialize(version, SourceRefreshPolicy.NORMAL, (_, _, _) -> {
            }, Cancellation.none()).sources();

            assertEquals(prepared.obfuscatedJar(), prepared.unobfuscatedJar());
            assertNotEquals(prepared.unobfuscatedJar(), prepared.remappedJar());
            assertEquals(paths.remappedJar(version).toAbsolutePath().normalize(), prepared.remappedJar());
            assertArrayEquals(Files.readAllBytes(prepared.unobfuscatedJar()), Files.readAllBytes(prepared.remappedJar()));
            assertTrue(Files.isRegularFile(prepared.sourceRoots().getFirst().path().resolve("sample/Example.java")));

            pipeline.initialize(version, SourceRefreshPolicy.NORMAL, (_, _, _) -> {
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
        var roots = new ArrayList<SourceRoot>();
        roots.add(new SourceRoot(SourceNamespace.MINECRAFT, Optional.empty(), source));

        PreparedSources prepared = new PreparedSources(new MinecraftVersion("1.21.5"), roots, Path.of("client.jar"), Path.of("unobfuscated.jar"), Path.of("remapped.jar"));
        roots.clear();

        assertEquals(1, prepared.sourceRoots().size());
        assertTrue(prepared.obfuscatedJar().isAbsolute());
        assertTrue(prepared.unobfuscatedJar().isAbsolute());
        assertTrue(prepared.remappedJar().isAbsolute());
    }

    @Test
    void explicitlyRepairsLegacyMalformedSourceWithoutLosingOriginalCacheBytes() throws Exception {
        MinecraftVersion version = new MinecraftVersion("26.1");
        String binaryName = "net.minecraft.client.gui.font.PlayerGlyphProvider";
        String generated = "package net.minecraft.client.gui.font; public class PlayerGlyphProvider { public String value() { return \"generated\"; } }";
        byte[] jar = compileJar(temporaryDirectory.resolve("legacy-fixture"), binaryName, generated);
        PlatformPaths paths = new PlatformPaths(temporaryDirectory.resolve("legacy-cache"));
        Path source = paths.sourceRoot(version).resolve(binaryName.replace('.', '/') + ".java");
        Files.createDirectories(source.getParent());
        String malformed = "package net.minecraft.client.gui.font; public class PlayerGlyphProvider { void value(<VAR_NAMELESS_ENCLOSURE>) { } }";
        Files.writeString(source, malformed);
        Path marker = paths.sourceRoot(version).resolve("user-cache-marker.txt");
        Files.writeString(marker, "original user content");
        Path legacyManifest = paths.indexRoot(version).resolve("manifest.json");
        Files.createDirectories(legacyManifest.getParent());
        Files.writeString(legacyManifest, "{\"indexerVersion\":\"ast\"}");
        Path legacyGraph = paths.remappedCallgraphJar(version).resolveSibling("callgraph.db");
        Files.createDirectories(legacyGraph.getParent());
        Files.writeString(legacyGraph, "original Node database sentinel");
        Files.createDirectories(paths.remappedJar(version).getParent());
        Files.write(paths.remappedJar(version), jar);
        HttpServer server = server();
        String jarSha1 = sha1(jar);
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            server.createContext("/manifest", exchange -> respond(exchange, McpJsonDefaults.getMapper().writeValueAsBytes(Map.of("versions", List.of(Map.of("id", version.value(), "url", base + "/version"))))));
            server.createContext("/version", exchange -> respond(exchange, McpJsonDefaults.getMapper().writeValueAsBytes(Map.of("downloads", Map.of("client", Map.of("url", base + "/client", "sha1", jarSha1, "size", jar.length))))));
            server.createContext("/client", exchange -> respond(exchange, jar));
            AnalysisPipeline pipeline = pipeline(paths, server);
            IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> pipeline.initialize(version, SourceRefreshPolicy.NORMAL, (_, _, _) -> {
            }, Cancellation.none()));
            assertTrue(refusal.getMessage().contains("--refresh-sources"), refusal.getMessage());
            assertEquals(malformed, Files.readString(source));
            assertEquals("original user content", Files.readString(marker));
            assertFalse(Files.exists(paths.symbolIndex(version)));

            InitializationResult initialized = pipeline.initialize(version, SourceRefreshPolicy.EXPLICIT_REFRESH, (_, _, _) -> {
            }, Cancellation.none());
            Path retained = initialized.retainedMigration().orElseThrow();
            assertEquals(malformed, Files.readString(retained.resolve("old/client").resolve(paths.sourceRoot(version).relativize(source))));
            assertEquals("original user content", Files.readString(retained.resolve("old/client/user-cache-marker.txt")));
            assertEquals("{\"indexerVersion\":\"ast\"}", Files.readString(legacyManifest));
            assertEquals("original Node database sentinel", Files.readString(legacyGraph));
            assertArrayEquals(jar, Files.readAllBytes(paths.remappedJar(version)));
            assertFalse(Files.readString(source).contains("VAR_NAMELESS_ENCLOSURE"));
            assertEquals(VersionState.READY, new VersionStateRepository(paths).state(version));
            assertEquals(paths.sourceRoot(version).toAbsolutePath().normalize().toString(), SymbolIndexFiles.readHeader(paths.symbolIndex(version)).sourceRoot());
            assertEquals(SourceOwnership.GENERATED, SourceProvenance.read(paths.versionCache(version).resolve("source-preparation.json")).orElseThrow().ownership());

            Path editedMarker = paths.sourceRoot(version).resolve("new-user-marker.txt");
            Files.writeString(editedMarker, "keep valid external edits");
            byte[] validSource = Files.readAllBytes(source);
            List<String> stages = new ArrayList<>();
            pipeline.initialize(version, SourceRefreshPolicy.NORMAL, (stage, _, _) -> stages.add(stage), Cancellation.none());
            assertArrayEquals(validSource, Files.readAllBytes(source));
            assertEquals("keep valid external edits", Files.readString(editedMarker));
            assertFalse(stages.contains("decompile"));
            assertEquals(SourceOwnership.VALIDATED_EXTERNAL, SourceProvenance.read(paths.versionCache(version).resolve("source-preparation.json")).orElseThrow().ownership());
            Files.writeString(source, "package net.minecraft.client.gui.font; public class PlayerGlyphProvider { public void oldOnly() {} public String value() { return \"edited\"; } }");
            CountDownLatch parsedOldSources = new CountDownLatch(1);
            CountDownLatch releaseOldRebuild = new CountDownLatch(1);
            CountDownLatch refreshMetadataStarted = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                try {
                    var rebuilding = executor.submit(() -> pipeline.rebuildIndex(version, (stage, percent, _) -> {
                        if (stage.equals("index") && percent == 75) {
                            parsedOldSources.countDown();
                            try {
                                if (!releaseOldRebuild.await(10, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("Rebuild fixture release timed out");
                                }
                            } catch (InterruptedException interruption) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(interruption);
                            }
                        }
                    }, Cancellation.none()));
                    assertTrue(parsedOldSources.await(10, TimeUnit.SECONDS));
                    var refreshing = executor.submit(() -> pipeline.initialize(version, SourceRefreshPolicy.EXPLICIT_REFRESH, (stage, _, _) -> {
                        if (stage.equals("metadata")) refreshMetadataStarted.countDown();
                    }, Cancellation.none()));
                    assertFalse(refreshMetadataStarted.await(150, TimeUnit.MILLISECONDS));
                    assertFalse(refreshing.isDone());
                    releaseOldRebuild.countDown();
                    rebuilding.get(10, TimeUnit.SECONDS);
                    InitializationResult refreshed = refreshing.get(20, TimeUnit.SECONDS);
                    assertTrue(Files.readString(refreshed.retainedMigration().orElseThrow().resolve("old/client").resolve(paths.sourceRoot(version).relativize(source))).contains("oldOnly"));
                    SymbolRepository repository = new SymbolRepository(paths.symbolIndex(version));
                    var owner = repository.classByName(binaryName);
                    assertNotNull(owner);
                    assertEquals(List.of(), repository.methodsNamed(owner.id(), "oldOnly"));
                    assertFalse(Files.readString(source).contains("oldOnly"));
                } finally {
                    releaseOldRebuild.countDown();
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
                }
            }
            server.stop(0);
            assertEquals(1, pipeline.rebuildIndex(version, (_, _, _) -> {
            }, Cancellation.none()).types());
        } finally {
            server.stop(0);
        }
    }
}
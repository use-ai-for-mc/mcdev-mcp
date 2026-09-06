package dev.mcdevmcp.analysis.decompile;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings({"HttpUrlsUsage", "UnusedReturnValue"})
final class VersionManifestClientTest {
    private static final String SHA1 = "0000000000000000000000000000000000000000";

    private static MinecraftDownloads resolve(HttpServer server, String path, String version) throws IOException {
        return client(server, path, Duration.ofSeconds(2)).resolve(new MinecraftVersion(version));
    }

    private static VersionManifestClient client(HttpServer server, String path, Duration timeout) {
        return new VersionManifestClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), McpJsonDefaults.getMapper(), uri(server, path), timeout);
    }

    private static URI uri(HttpServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private static HttpServer server() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        return server;
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void resolvesMappedDownloadsThroughRelativeRedirects() throws Exception {
        HttpServer server = server();
        try {
            int port = server.getAddress().getPort();
            server.createContext("/manifest", exchange -> redirect(exchange, "/manifest-redirect"));
            server.createContext("/manifest-redirect", exchange -> redirect(exchange, "/manifest-body"));
            server.createContext("/manifest-body", exchange -> respond(exchange, """
                                                                                 {"versions":[{"id":"1.21.11","url":"http://127.0.0.1:%d/detail"}]}
                                                                                 """.formatted(port)));
            server.createContext("/detail", exchange -> redirect(exchange, "/detail-body"));
            server.createContext("/detail-body", exchange -> respond(exchange, """
                                                                               {"downloads":{
                                                                                 "client":{"url":"http://127.0.0.1:%1$d/client.jar","sha1":"%2$s","size":123},
                                                                                 "client_mappings":{"url":"http://127.0.0.1:%1$d/client.txt","sha1":"%2$s","size":45}
                                                                               },
                                                                               "libraries":[
                                                                                 {"downloads":{"artifact":{"url":"http://127.0.0.1:%1$d/lib.jar","sha1":"%2$s","size":789}},"name":"test:lib:1.0"},
                                                                                 {"downloads":{"artifact":{"url":"http://127.0.0.1:%1$d/lib-1.0-natives-linux.jar","sha1":"%2$s","size":456}},"name":"test:lib:1.0:natives-linux"}
                                                                               ]}
                                                                               """.formatted(port, SHA1)));

            MinecraftDownloads downloads = client(server, "/manifest", Duration.ofSeconds(2)).resolve(new MinecraftVersion("1.21.11"));

            assertEquals(URI.create("http://127.0.0.1:" + port + "/client.jar"), downloads.client().uri());
            assertEquals(123, downloads.client().byteLength());
            assertEquals(ArtifactKind.JAR, downloads.client().kind());
            assertEquals(URI.create("http://127.0.0.1:" + port + "/client.txt"), downloads.clientMappings().uri());
            assertEquals(45, downloads.clientMappings().byteLength());
            assertEquals(ArtifactKind.MAPPING, downloads.clientMappings().kind());
            assertNull(downloads.officialUnobfuscatedClient());
            assertEquals(1, downloads.libraries().size());
            assertEquals(URI.create("http://127.0.0.1:" + port + "/lib.jar"), downloads.libraries().getFirst().uri());
            assertEquals(789, downloads.libraries().getFirst().byteLength());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolvesTypedOfficialUnobfuscatedSiblingOnlyWhenManifestListsIt() throws Exception {
        HttpServer server = server();
        try {
            int port = server.getAddress().getPort();
            AtomicInteger unobfuscatedDetails = new AtomicInteger();
            server.createContext("/manifest", exchange -> respond(exchange, """
                                                                            {"versions":[
                                                                              {"id":"1.21.11","url":"http://127.0.0.1:%1$d/present"},
                                                                              {"id":"1.21.11_unobfuscated","url":"http://127.0.0.1:%1$d/unobfuscated"},
                                                                              {"id":"1.21.10","url":"http://127.0.0.1:%1$d/absent"}
                                                                            ]}
                                                                            """.formatted(port)));
            server.createContext("/present", exchange -> respond(exchange, """
                                                                           {"downloads":{"client":{
                                                                             "url":"http://127.0.0.1:%d/client.jar","sha1":"%s","size":123
                                                                           }}}
                                                                           """.formatted(port, SHA1)));
            server.createContext("/unobfuscated", exchange -> {
                unobfuscatedDetails.incrementAndGet();
                respond(exchange, """
                                  {"downloads":{"client":{
                                    "url":"http://127.0.0.1:%d/client-unobfuscated.jar","sha1":"%s","size":456
                                  }}}
                                  """.formatted(port, SHA1));
            });
            server.createContext("/absent", exchange -> respond(exchange, """
                                                                          {"downloads":{
                                                                            "client":{"url":"http://127.0.0.1:%1$d/older-client.jar","sha1":"%2$s","size":12},
                                                                            "client_mappings":{"url":"http://127.0.0.1:%1$d/older-client.txt","sha1":"%2$s","size":34}
                                                                          }}
                                                                          """.formatted(port, SHA1)));

            MinecraftDownloads present = resolve(server, "/manifest", "1.21.11");
            OfficialUnobfuscatedClient official = present.officialUnobfuscatedClient();
            assertNotNull(official);
            assertEquals("1.21.11_unobfuscated", official.manifestEntry().id());
            assertEquals(uri(server, "/unobfuscated"), official.manifestEntry().url());
            assertEquals(uri(server, "/client-unobfuscated.jar"), official.artifact().uri());
            assertEquals(456, official.artifact().byteLength());
            assertEquals(ArtifactKind.JAR, official.artifact().kind());

            MinecraftDownloads absent = resolve(server, "/manifest", "1.21.10");
            assertNull(absent.officialUnobfuscatedClient());
            assertEquals(1, unobfuscatedDetails.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void requiresMappingsForLegacyVersionsButNotModernVersions() throws Exception {
        HttpServer server = server();
        try {
            int port = server.getAddress().getPort();
            server.createContext("/manifest", exchange -> respond(exchange, """
                                                                            {"versions":[
                                                                              {"id":"1.21.11","url":"http://127.0.0.1:%1$d/legacy"},
                                                                              {"id":"26.1-snapshot-10","url":"http://127.0.0.1:%1$d/modern"}
                                                                            ]}
                                                                            """.formatted(port)));
            String clientOnly = """
                                {"downloads":{"client":{
                                  "url":"http://127.0.0.1:%d/client.jar","sha1":"%s","size":1
                                }}}
                                """.formatted(port, SHA1);
            server.createContext("/legacy", exchange -> respond(exchange, clientOnly));
            server.createContext("/modern", exchange -> respond(exchange, clientOnly));

            IOException legacy = assertThrows(IOException.class, () -> client(server, "/manifest", Duration.ofSeconds(2)).resolve(new MinecraftVersion("1.21.11")));
            assertTrue(legacy.getMessage().contains("client mappings"));
            MinecraftDownloads modern = client(server, "/manifest", Duration.ofSeconds(2)).resolve(new MinecraftVersion("26.1-snapshot-10"));
            assertNull(modern.clientMappings());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsUnsafeRedirectsLoopsAndExcessHops() throws Exception {
        HttpServer server = server();
        try {
            server.createContext("/missing-location", exchange -> {
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            server.createContext("/loop-a", exchange -> redirect(exchange, "/loop-b"));
            server.createContext("/loop-b", exchange -> redirect(exchange, "/loop-a"));
            for (int hop = 0; hop < 6; hop++) {
                int next = hop + 1;
                server.createContext("/hop-" + hop, exchange -> redirect(exchange, "/hop-" + next));
            }
            server.createContext("/hop-6", exchange -> respond(exchange, "{\"versions\":[]}"));
            server.createContext("/remote", exchange -> redirect(exchange, "http://example.com/manifest"));

            assertThrows(IOException.class, () -> resolve(server, "/missing-location", "1.21.11"));
            assertThrows(IOException.class, () -> resolve(server, "/loop-a", "1.21.11"));
            IOException limit = assertThrows(IOException.class, () -> resolve(server, "/hop-0", "1.21.11"));
            assertTrue(limit.getMessage().contains("redirect limit"));
            assertThrows(IOException.class, () -> resolve(server, "/remote", "1.21.11"));
            assertThrows(IOException.class, () -> VersionManifestClient.resolveRedirect(URI.create("https://piston-meta.mojang.com/manifest"), "http://127.0.0.1/manifest"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsHttpMalformedOversizedIncompleteAndInvalidArtifactMetadata() throws Exception {
        HttpServer server = server();
        try {
            int port = server.getAddress().getPort();
            server.createContext("/unavailable", exchange -> {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
            });
            server.createContext("/malformed", exchange -> respond(exchange, "{not-json"));
            server.createContext("/oversized", exchange -> respond(exchange, "x".repeat(2 * 1024 * 1024 + 1)));
            server.createContext("/missing-version", exchange -> respond(exchange, "{\"versions\":[]}"));
            server.createContext("/missing-client", exchange -> respond(exchange, """
                                                                                  {"versions":[{"id":"1.21.11","url":"http://127.0.0.1:%d/empty-detail"}]}
                                                                                  """.formatted(port)));
            server.createContext("/empty-detail", exchange -> respond(exchange, "{\"downloads\":{}}"));
            server.createContext("/invalid-artifact", exchange -> respond(exchange, """
                                                                                    {"versions":[{"id":"26.1","url":"http://127.0.0.1:%d/bad-detail"}]}
                                                                                    """.formatted(port)));
            server.createContext("/bad-detail", exchange -> respond(exchange, """
                                                                              {"downloads":{"client":{
                                                                                "url":"http://127.0.0.1:%d/client.jar","sha1":"bad","size":-1
                                                                              }}}
                                                                              """.formatted(port)));

            assertThrows(IOException.class, () -> resolve(server, "/unavailable", "1.21.11"));
            assertThrows(IOException.class, () -> resolve(server, "/malformed", "1.21.11"));
            assertThrows(IOException.class, () -> resolve(server, "/oversized", "1.21.11"));
            assertThrows(IOException.class, () -> resolve(server, "/missing-version", "1.21.11"));
            assertThrows(IOException.class, () -> resolve(server, "/missing-client", "1.21.11"));
            assertThrows(IOException.class, () -> resolve(server, "/invalid-artifact", "26.1"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void enforcesTimeoutAndPreservesInterruption() throws Exception {
        CountDownLatch blockedRequest = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        HttpServer server = server();
        try {
            server.createContext("/slow", exchange -> {
                sleep(Duration.ofSeconds(1));
                respond(exchange, "{\"versions\":[]}");
            });
            server.createContext("/blocked", exchange -> {
                blockedRequest.countDown();
                await(releaseResponse);
                respond(exchange, "{\"versions\":[]}");
            });
            assertThrows(IOException.class, () -> client(server, "/slow", Duration.ofMillis(50)).resolve(new MinecraftVersion("1.21.11")));

            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean interrupted = new AtomicBoolean();
            Thread worker = Thread.ofPlatform().start(() -> {
                try {
                    client(server, "/blocked", Duration.ofSeconds(5)).resolve(new MinecraftVersion("1.21.11"));
                } catch (Throwable throwable) {
                    failure.set(throwable);
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            blockedRequest.await();
            worker.interrupt();
            worker.join(Duration.ofSeconds(2));
            releaseResponse.countDown();

            assertInstanceOf(IOException.class, failure.get());
            assertTrue(interrupted.get());
        } finally {
            releaseResponse.countDown();
            server.stop(0);
        }
    }

    @Test
    void rejectsNonLoopbackHttpMetadataAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new VersionManifestClient(HttpClient.newHttpClient(), McpJsonDefaults.getMapper(), URI.create("http://example.com/manifest"), Duration.ofSeconds(1)));
    }
}

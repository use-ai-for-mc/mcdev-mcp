package dev.mcdevmcp.analysis.decompile;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.mcdevmcp.support.Cancellation;
import dev.mcdevmcp.support.AppVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("HttpUrlsUsage")
final class DownloadServiceTest {
    @TempDir
    Path temporaryDirectory;

    private static DownloadService service(Duration timeout) {
        return new DownloadService(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(), timeout);
    }

    private static DownloadArtifact artifact(HttpServer server, String path, byte[] body, ArtifactKind kind) {
        return new DownloadArtifact(uri(server, path), sha1(body), body.length, kind);
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

    private static void respond(HttpExchange exchange, byte[] body) throws IOException {
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
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

    private static byte[] jarBytes() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("example.txt"));
            zip.write("content".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static byte[] corruptJarBytes() throws IOException {
        byte[] content = "content".getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(content);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            ZipEntry entry = new ZipEntry("example.txt");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(content.length);
            entry.setCompressedSize(content.length);
            entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);
            zip.write(content);
            zip.closeEntry();
        }
        byte[] corrupt = bytes.toByteArray();
        for (int index = 0; index <= corrupt.length - content.length; index++) {
            if (java.util.Arrays.equals(corrupt, index, index + content.length, content, 0, content.length)) {
                corrupt[index] ^= 1;
                return corrupt;
            }
        }
        throw new AssertionError("stored ZIP fixture did not contain its entry bytes");
    }

    private static String sha1(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(body));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void assertNoOwnedTemporaryFiles(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void downloadsJarThroughRedirectsAndReusesVerifiedCache() throws Exception {
        byte[] body = jarBytes();
        AtomicInteger artifactRequests = new AtomicInteger();
        HttpServer server = server();
        try {
            server.createContext("/first", exchange -> redirect(exchange, "/second"));
            server.createContext("/second", exchange -> redirect(exchange, "/artifact"));
            server.createContext("/artifact", exchange -> {
                artifactRequests.incrementAndGet();
                respond(exchange, body);
            });
            DownloadArtifact artifact = artifact(server, "/first", body, ArtifactKind.JAR);
            Path target = temporaryDirectory.resolve("client.jar");
            DownloadService service = service(Duration.ofSeconds(2));

            assertEquals(target.toAbsolutePath(), service.download(artifact, target, (_, _, _) -> {
            }, Cancellation.none()));
            assertEquals(target.toAbsolutePath(), service.download(artifact, target, (_, _, _) -> {
            }, Cancellation.none()));

            assertArrayEquals(body, Files.readAllBytes(target));
            assertEquals(1, artifactRequests.get());
            assertNoOwnedTemporaryFiles(temporaryDirectory);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsUnsafeRedirectsLoopsAndExcessHops() throws Exception {
        byte[] expected = "late".getBytes(StandardCharsets.UTF_8);
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
            server.createContext("/hop-6", exchange -> respond(exchange, expected));
            server.createContext("/remote", exchange -> redirect(exchange, "http://example.com/artifact"));

            assertThrows(IOException.class, () -> download(server, "/missing-location", expected, temporaryDirectory.resolve("missing.txt")));
            assertThrows(IOException.class, () -> download(server, "/loop-a", expected, temporaryDirectory.resolve("loop.txt")));
            IOException limit = assertThrows(IOException.class, () -> download(server, "/hop-0", expected, temporaryDirectory.resolve("limit.txt")));
            assertTrue(limit.getMessage().contains("redirect limit"));
            assertThrows(IOException.class, () -> download(server, "/remote", expected, temporaryDirectory.resolve("remote.txt")));
            assertThrows(IOException.class, () -> DownloadService.resolveRedirect(URI.create("https://piston-meta.mojang.com/client.jar"), "http://127.0.0.1/client.jar"));
            assertNoOwnedTemporaryFiles(temporaryDirectory);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsTimeoutTruncationOverflowWrongHashAndHttpFailure() throws Exception {
        byte[] expected = "expected".getBytes(StandardCharsets.UTF_8);
        HttpServer server = server();
        try {
            server.createContext("/timeout", exchange -> {
                sleep(Duration.ofSeconds(1));
                respond(exchange, expected);
            });
            server.createContext("/truncated", exchange -> {
                exchange.sendResponseHeaders(200, expected.length + 5L);
                exchange.getResponseBody().write(expected);
                exchange.close();
            });
            server.createContext("/overflow", exchange -> respond(exchange, "expected-and-more".getBytes(StandardCharsets.UTF_8)));
            server.createContext("/wrong-hash", exchange -> respond(exchange, expected));
            server.createContext("/unavailable", exchange -> {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
            });

            assertThrows(IOException.class, () -> service(Duration.ofMillis(50)).download(artifact(server, "/timeout", expected, ArtifactKind.MAPPING), temporaryDirectory.resolve("timeout.txt"), (_, _, _) -> {
            }, Cancellation.none()));
            assertThrows(IOException.class, () -> download(server, "/truncated", "expected-plus".getBytes(StandardCharsets.UTF_8), temporaryDirectory.resolve("truncated.txt")));
            assertThrows(IOException.class, () -> download(server, "/overflow", expected, temporaryDirectory.resolve("overflow.txt")));
            DownloadArtifact wrongHash = new DownloadArtifact(uri(server, "/wrong-hash"), "0000000000000000000000000000000000000000", expected.length, ArtifactKind.MAPPING);
            assertThrows(IOException.class, () -> service(Duration.ofSeconds(2)).download(wrongHash, temporaryDirectory.resolve("wrong-hash.txt"), (_, _, _) -> {
            }, Cancellation.none()));
            assertThrows(IOException.class, () -> download(server, "/unavailable", expected, temporaryDirectory.resolve("unavailable.txt")));

            assertFalse(Files.exists(temporaryDirectory.resolve("timeout.txt")));
            assertFalse(Files.exists(temporaryDirectory.resolve("truncated.txt")));
            assertFalse(Files.exists(temporaryDirectory.resolve("overflow.txt")));
            assertFalse(Files.exists(temporaryDirectory.resolve("wrong-hash.txt")));
            assertFalse(Files.exists(temporaryDirectory.resolve("unavailable.txt")));
            assertNoOwnedTemporaryFiles(temporaryDirectory);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesPriorTargetOnIntegrityZipCancellationAndPromotionFailures() throws Exception {
        byte[] replacement = "replacement".getBytes(StandardCharsets.UTF_8);
        byte[] old = "old".getBytes(StandardCharsets.UTF_8);
        byte[] large = new byte[256 * 1024];
        byte[] corruptJar = corruptJarBytes();
        HttpServer server = server();
        try {
            server.createContext("/replacement", exchange -> respond(exchange, replacement));
            server.createContext("/large", exchange -> respond(exchange, large));
            server.createContext("/corrupt-jar", exchange -> respond(exchange, corruptJar));
            DownloadService service = service(Duration.ofSeconds(2));

            Path hashTarget = temporaryDirectory.resolve("hash.txt");
            Files.write(hashTarget, old);
            DownloadArtifact wrongHash = new DownloadArtifact(uri(server, "/replacement"), "0000000000000000000000000000000000000000", replacement.length, ArtifactKind.MAPPING);
            assertThrows(IOException.class, () -> service.download(wrongHash, hashTarget, (_, _, _) -> {
            }, Cancellation.none()));
            assertArrayEquals(old, Files.readAllBytes(hashTarget));

            Path jarTarget = temporaryDirectory.resolve("client.jar");
            Files.write(jarTarget, old);
            assertThrows(IOException.class, () -> service.download(artifact(server, "/corrupt-jar", corruptJar, ArtifactKind.JAR), jarTarget, (_, _, _) -> {
            }, Cancellation.none()));
            assertArrayEquals(old, Files.readAllBytes(jarTarget));

            Path cancellationTarget = temporaryDirectory.resolve("cancel.txt");
            Files.write(cancellationTarget, old);
            AtomicBoolean cancel = new AtomicBoolean();
            IOException cancellationFailure = assertThrows(IOException.class, () -> service.download(artifact(server, "/large", large, ArtifactKind.MAPPING), cancellationTarget, (_, _, message) -> cancel.compareAndSet(false, message.startsWith("Downloaded ")), cancel::get));
            assertTrue(cancellationFailure.getMessage().contains(AppVersion.executableJarName()));
            assertTrue(Thread.interrupted(), "cancellation must preserve the interrupt signal");
            assertArrayEquals(old, Files.readAllBytes(cancellationTarget));

            Path directoryTarget = temporaryDirectory.resolve("directory-target");
            Files.createDirectory(directoryTarget);
            assertThrows(IOException.class, () -> service.download(artifact(server, "/replacement", replacement, ArtifactKind.MAPPING), directoryTarget, (_, _, _) -> {
            }, Cancellation.none()));
            assertTrue(Files.isDirectory(directoryTarget));
            assertNoOwnedTemporaryFiles(temporaryDirectory);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void redownloadsInvalidCacheAndPreservesInterruption() throws Exception {
        byte[] body = "replacement".getBytes(StandardCharsets.UTF_8);
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch blockedRequest = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        HttpServer server = server();
        try {
            server.createContext("/body", exchange -> {
                requests.incrementAndGet();
                respond(exchange, body);
            });
            server.createContext("/blocked", exchange -> {
                blockedRequest.countDown();
                await(releaseResponse);
                respond(exchange, body);
            });
            Path target = temporaryDirectory.resolve("cached.txt");
            Files.writeString(target, "corrupt");
            service(Duration.ofSeconds(2)).download(artifact(server, "/body", body, ArtifactKind.MAPPING), target, (_, _, _) -> {
            }, Cancellation.none());
            assertArrayEquals(body, Files.readAllBytes(target));
            assertEquals(1, requests.get());

            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean interrupted = new AtomicBoolean();
            Thread worker = Thread.ofPlatform().start(() -> {
                try {
                    service(Duration.ofSeconds(5)).download(artifact(server, "/blocked", body, ArtifactKind.MAPPING), temporaryDirectory.resolve("interrupted.txt"), (_, _, _) -> {
                    }, Cancellation.none());
                } catch (Throwable throwable) {
                    failure.set(throwable);
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            blockedRequest.await();
            worker.interrupt();
            worker.join(Duration.ofSeconds(2));
            releaseResponse.countDown();

            assertFalse(worker.isAlive());
            assertInstanceOf(IOException.class, failure.get());
            assertTrue(interrupted.get());
            assertFalse(Files.exists(temporaryDirectory.resolve("interrupted.txt")));
            assertNoOwnedTemporaryFiles(temporaryDirectory);
        } finally {
            releaseResponse.countDown();
            server.stop(0);
        }
    }

    @Test
    void rejectsRemoteCleartextBeforeMakingARequest() {
        byte[] body = "body".getBytes(StandardCharsets.UTF_8);
        DownloadArtifact remote = new DownloadArtifact(URI.create("http://example.com/client.jar"), sha1(body), body.length, ArtifactKind.MAPPING);

        assertThrows(IOException.class, () -> service(Duration.ofSeconds(2)).download(remote, temporaryDirectory.resolve("remote.txt"), (_, _, _) -> {
        }, Cancellation.none()));
    }

    private void download(HttpServer server, String path, byte[] expected, Path target) throws IOException {
        service(Duration.ofSeconds(2)).download(artifact(server, path, expected, ArtifactKind.MAPPING), target, (_, _, _) -> {
        }, Cancellation.none());
    }
}

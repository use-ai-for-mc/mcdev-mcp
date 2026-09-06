package dev.mcdevmcp.analysis.decompile;

import dev.mcdevmcp.support.AppVersion;
import dev.mcdevmcp.support.Cancellation;
import dev.mcdevmcp.support.ProgressSink;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;

/**
 * Downloads verified JAR artifacts without exposing partially written cache entries.
 */
public final class DownloadService {
    private static final int MAXIMUM_REDIRECTS = 5;
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final int MAXIMUM_ERROR_URI_CHARACTERS = 512;
    private static final long MAXIMUM_ARTIFACT_BYTES = 1024L * 1024L * 1024L;
    private final HttpClient client;
    private final Duration requestTimeout;

    public DownloadService(HttpClient client, Duration requestTimeout) {
        this.client = Objects.requireNonNull(client, "client");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
    }

    public static DownloadService production() {
        return new DownloadService(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build(), Duration.ofSeconds(60));
    }

    private static void checkCancelled(Cancellation cancellation) throws IOException {
        try {
            cancellation.throwIfCancelled();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("download cancelled; retry with java -jar " + AppVersion.executableJarName() + " init", exception);
        }
    }

    private static MessageDigest sha1() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-1 is unavailable", exception);
        }
    }

    private static void verify(long actualLength, String actualSha1, DownloadArtifact artifact, Path target) throws IOException {
        if (actualLength != artifact.byteLength()) {
            throw new IOException("download size mismatch for " + target + ": expected " + artifact.byteLength() + ", got " + actualLength);
        }
        if (!artifact.sha1().equalsIgnoreCase(actualSha1)) {
            throw new IOException("download SHA-1 mismatch for " + target + ": expected " + artifact.sha1() + ", got " + actualSha1);
        }
    }

    private static boolean valid(Path target, DownloadArtifact artifact, boolean requireZip, Cancellation cancellation) throws IOException {
        if (!Files.isRegularFile(target)) {
            return false;
        }
        if (Files.size(target) != artifact.byteLength()) {
            return false;
        }
        try {
            MessageDigest digest = sha1();
            long bytes = 0;
            try (InputStream input = Files.newInputStream(target)) {
                byte[] buffer = new byte[BUFFER_BYTES];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkCancelled(cancellation);
                    digest.update(buffer, 0, read);
                    bytes += read;
                }
            }
            verify(bytes, HexFormat.of().formatHex(digest.digest()), artifact, target);
            if (requireZip) {
                JarArtifactValidator.validate(target, cancellation);
            }
            return true;
        } catch (IOException exception) {
            if (Thread.currentThread().isInterrupted() || exception.getCause() instanceof InterruptedException) {
                throw exception;
            }
            return false;
        }
    }

    private static URI requireAllowedUri(URI uri) throws IOException {
        Objects.requireNonNull(uri, "uri");
        URI normalized = uri.normalize();
        if (!normalized.isAbsolute() || normalized.isOpaque() || normalized.getHost() == null || normalized.getRawUserInfo() != null || normalized.getRawFragment() != null) {
            throw new IOException("download URI must be an absolute HTTP(S) URI without credentials or fragments: " + displayUri(normalized));
        }
        if ("https".equalsIgnoreCase(normalized.getScheme())) {
            return normalized;
        }
        if (!"http".equalsIgnoreCase(normalized.getScheme())) {
            throw new IOException("download URI must use HTTPS (or loopback HTTP in tests): " + displayUri(normalized));
        }
        String host = normalized.getHost();
        if ("localhost".equalsIgnoreCase(host) || isLiteralLoopback(host)) {
            return normalized;
        }
        throw new IOException("download URI must use HTTPS (or loopback HTTP in tests): " + displayUri(normalized));
    }

    static URI resolveRedirect(URI current, String location) throws IOException {
        Objects.requireNonNull(current, "current");
        if (location == null || location.isBlank()) {
            throw new IOException("download redirect has an empty Location for " + displayUri(current));
        }
        URI redirected;
        try {
            redirected = current.resolve(location);
        } catch (IllegalArgumentException exception) {
            throw new IOException("download redirect has an invalid Location for " + displayUri(current), exception);
        }
        if ("https".equalsIgnoreCase(current.getScheme()) && !"https".equalsIgnoreCase(redirected.getScheme())) {
            throw new IOException("download redirect must not downgrade HTTPS: " + displayUri(current) + " -> " + displayUri(redirected));
        }
        return requireAllowedUri(redirected);
    }

    private static void validateContentLength(HttpResponse<InputStream> response, DownloadArtifact artifact, URI uri) throws IOException {
        long contentLength;
        try {
            contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        } catch (NumberFormatException exception) {
            response.body().close();
            throw new IOException("download has an invalid Content-Length for " + displayUri(uri), exception);
        }
        if (contentLength >= 0 && contentLength != artifact.byteLength()) {
            response.body().close();
            throw new IOException("download Content-Length mismatch for " + displayUri(uri) + ": expected " + artifact.byteLength() + ", got " + contentLength);
        }
    }

    private static boolean isLiteralLoopback(String host) {
        try {
            return InetAddress.ofLiteral(host).isLoopbackAddress();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String displayUri(URI uri) {
        String value = String.valueOf(uri);
        if (value.length() <= MAXIMUM_ERROR_URI_CHARACTERS) {
            return value;
        }
        return value.substring(0, MAXIMUM_ERROR_URI_CHARACTERS) + "...";
    }

    public Path download(DownloadArtifact artifact, Path target, ProgressSink progress, Cancellation cancellation) throws IOException {
        Objects.requireNonNull(artifact, "artifact");
        return download(artifact, target, artifact.kind() == ArtifactKind.JAR, progress, cancellation);
    }

    private Path download(DownloadArtifact artifact, Path target, boolean requireZip, ProgressSink progress, Cancellation cancellation) throws IOException {
        Path normalizedTarget = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(cancellation, "cancellation");
        if (artifact.byteLength() > MAXIMUM_ARTIFACT_BYTES) {
            throw new IOException("download metadata size exceeds " + MAXIMUM_ARTIFACT_BYTES + " bytes for " + displayUri(artifact.uri()));
        }
        if (normalizedTarget.getParent() == null || normalizedTarget.getFileName() == null) {
            throw new IOException("download target must be a file path: " + normalizedTarget);
        }
        if (valid(normalizedTarget, artifact, requireZip, cancellation)) {
            progress.report("download", 100, "Using verified cached " + normalizedTarget.getFileName());
            return normalizedTarget;
        }
        Files.createDirectories(normalizedTarget.getParent());
        Path temporary = normalizedTarget.resolveSibling(normalizedTarget.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            URI current = requireAllowedUri(artifact.uri());
            HttpResponse<InputStream> response = null;
            Set<URI> redirectsSeen = new HashSet<>();
            for (int redirects = 0; redirects <= MAXIMUM_REDIRECTS; redirects++) {
                checkCancelled(cancellation);
                if (!redirectsSeen.add(current)) {
                    throw new IOException("download redirect loop for " + displayUri(current));
                }
                HttpRequest request = HttpRequest.newBuilder(current).timeout(requestTimeout).GET().build();
                try {
                    response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("download interrupted for " + displayUri(current), exception);
                }
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    String location = response.headers().firstValue("Location").orElse(null);
                    response.body().close();
                    if (location == null) {
                        throw new IOException("download redirect missing Location for " + displayUri(current));
                    }
                    current = resolveRedirect(current, location);
                    continue;
                }
                if (status != 200) {
                    response.body().close();
                    throw new IOException("download HTTP " + status + " for " + displayUri(current));
                }
                validateContentLength(response, artifact, current);
                break;
            }
            if (response.statusCode() != 200) {
                throw new IOException("download redirect limit exceeded for " + displayUri(artifact.uri()));
            }
            progress.report("download", 0, "Downloading " + normalizedTarget.getFileName());
            try (InputStream input = response.body();
                 FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                MessageDigest digest = sha1();
                byte[] buffer = new byte[BUFFER_BYTES];
                long written = 0;
                int lastReportedPercent = -1;
                while (true) {
                    checkCancelled(cancellation);
                    int read = input.read(buffer);
                    if (read == -1) {
                        break;
                    }
                    if (read > artifact.byteLength() - written) {
                        throw new IOException("download size exceeds expected length for " + normalizedTarget + ": expected " + artifact.byteLength() + ", received at least " + (written + read));
                    }
                    ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, read);
                    while (bytes.hasRemaining()) {
                        if (output.write(bytes) == 0) {
                            Thread.onSpinWait();
                        }
                    }
                    digest.update(buffer, 0, read);
                    written += read;
                    int percent = artifact.byteLength() == 0 ? 0 : (int) Math.min(99, written * 100 / artifact.byteLength());
                    int reportPercent = percent / 5 * 5;
                    if (reportPercent != lastReportedPercent) {
                        lastReportedPercent = reportPercent;
                        progress.report("download", percent, "Downloaded " + written + " bytes");
                    }
                }
                output.force(true);
                verify(written, HexFormat.of().formatHex(digest.digest()), artifact, normalizedTarget);
            }
            if (requireZip) {
                JarArtifactValidator.validate(temporary, cancellation);
            }
            Files.move(temporary, normalizedTarget, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            progress.report("download", 100, "Verified " + normalizedTarget.getFileName());
            return normalizedTarget;
        } catch (IOException | RuntimeException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

}
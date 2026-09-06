package dev.mcdevmcp.analysis.decompile;

import dev.mcdevmcp.storage.model.MinecraftVersion;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves Mojang's typed version metadata through the SDK JSON mapper.
 */
public final class VersionManifestClient {
    public static final URI PRODUCTION_MANIFEST = URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
    private static final int MAXIMUM_METADATA_BYTES = 2 * 1024 * 1024;
    private static final int MAXIMUM_REDIRECTS = 5;
    private static final int MAXIMUM_ERROR_URI_CHARACTERS = 512;
    private final HttpClient client;
    private final McpJsonMapper mapper;
    private final URI manifestUri;
    private final Duration requestTimeout;

    public VersionManifestClient(HttpClient client, McpJsonMapper mapper, URI manifestUri, Duration requestTimeout) {
        this.client = Objects.requireNonNull(client, "client");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.manifestUri = requireConfiguredMetadataUri(manifestUri);
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
    }

    public static VersionManifestClient production() {
        return new VersionManifestClient(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build(), McpJsonDefaults.getMapper(), PRODUCTION_MANIFEST, Duration.ofSeconds(30));
    }

    private static byte[] readBounded(InputStream body, URI uri) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        try (var output = new java.io.ByteArrayOutputStream()) {
            int read;
            while (true) {
                checkInterrupted(uri);
                read = body.read(buffer);
                if (read == -1) {
                    break;
                }
                if (output.size() + read > MAXIMUM_METADATA_BYTES) {
                    throw new IOException("metadata response exceeds " + MAXIMUM_METADATA_BYTES + " bytes for " + displayUri(uri));
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    static URI resolveRedirect(URI current, String location) throws IOException {
        Objects.requireNonNull(current, "current");
        if (location == null || location.isBlank()) {
            throw new IOException("metadata redirect has an empty Location for " + displayUri(current));
        }
        URI redirected;
        try {
            redirected = current.resolve(location);
        } catch (IllegalArgumentException exception) {
            throw new IOException("metadata redirect has an invalid Location for " + displayUri(current), exception);
        }
        if ("https".equalsIgnoreCase(current.getScheme()) && !"https".equalsIgnoreCase(redirected.getScheme())) {
            throw new IOException("metadata redirect must not downgrade HTTPS: " + displayUri(current) + " -> " + displayUri(redirected));
        }
        return requireAllowedMetadataUri(redirected);
    }

    private static URI requireConfiguredMetadataUri(URI uri) {
        try {
            return requireAllowedMetadataUri(uri);
        } catch (IOException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
    }

    private static URI requireAllowedMetadataUri(URI uri) throws IOException {
        Objects.requireNonNull(uri, "uri");
        URI normalized = uri.normalize();
        if (!normalized.isAbsolute() || normalized.isOpaque() || normalized.getHost() == null || normalized.getRawUserInfo() != null || normalized.getRawFragment() != null) {
            throw new IOException("Metadata URI must be an absolute HTTP(S) URI without credentials or fragments: " + displayUri(normalized));
        }
        if ("https".equalsIgnoreCase(normalized.getScheme())) {
            return normalized;
        }
        if ("http".equalsIgnoreCase(normalized.getScheme()) && isLoopback(normalized.getHost())) {
            return normalized;
        }
        throw new IOException("Metadata URI must use HTTPS (or loopback HTTP in tests): " + displayUri(normalized));
    }

    private static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        try {
            return InetAddress.ofLiteral(host).isLoopbackAddress();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static void validateContentLength(HttpResponse<InputStream> response, URI uri) throws IOException {
        long contentLength;
        try {
            contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        } catch (NumberFormatException exception) {
            throw new IOException("metadata has an invalid Content-Length for " + displayUri(uri), exception);
        }
        if (contentLength > MAXIMUM_METADATA_BYTES) {
            throw new IOException("metadata response exceeds " + MAXIMUM_METADATA_BYTES + " bytes for " + displayUri(uri));
        }
    }

    private static void checkInterrupted(URI uri) throws IOException {
        if (!Thread.currentThread().isInterrupted()) {
            return;
        }
        InterruptedException interruption = new InterruptedException("metadata read interrupted");
        throw new IOException("metadata request interrupted for " + displayUri(uri), interruption);
    }

    private static boolean requiresMappings(MinecraftVersion version) {
        String value = version.value();
        int end = 0;
        while (end < value.length() && Character.isDigit(value.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return true;
        }
        try {
            return Integer.parseInt(value.substring(0, end)) < 26;
        } catch (NumberFormatException ignored) {
            return true;
        }
    }

    private static String displayUri(URI uri) {
        String value = String.valueOf(uri);
        if (value.length() <= MAXIMUM_ERROR_URI_CHARACTERS) {
            return value;
        }
        return value.substring(0, MAXIMUM_ERROR_URI_CHARACTERS) + "...";
    }

    private static boolean compilerClasspathLibrary(LibraryEntry library) {
        String[] coordinate = library.name().split(":", -1);
        return coordinate.length < 4 || !coordinate[3].startsWith("natives-");
    }

    public MinecraftDownloads resolve(MinecraftVersion version) throws IOException {
        Objects.requireNonNull(version, "version");
        GlobalManifest manifest = read(manifestUri, GlobalManifest.class);
        VersionEntry versionEntry = manifest.versions().stream().filter(entry -> version.value().equals(entry.id())).findFirst().orElseThrow(() -> new IOException("Mojang metadata has no version " + version.value()));
        VersionDetail detailBody = read(versionEntry.url(), VersionDetail.class);
        DownloadWire clientDownload = detailBody.downloads().get("client");
        DownloadWire mappingDownload = detailBody.downloads().get("client_mappings");
        if (clientDownload == null) {
            throw new IOException("Mojang metadata lacks client for " + version.value());
        }

        String unobfuscatedVersion = version.value() + "_unobfuscated";
        VersionEntry unobfuscatedEntry = manifest.versions().stream().filter(entry -> unobfuscatedVersion.equals(entry.id())).findFirst().orElse(null);
        DownloadWire unobfuscatedDownload = null;
        if (unobfuscatedEntry != null) {
            unobfuscatedDownload = read(unobfuscatedEntry.url(), VersionDetail.class).downloads().get("client");
            if (unobfuscatedDownload == null) {
                throw new IOException("Mojang metadata lacks client for " + unobfuscatedVersion);
            }
        }
        if (mappingDownload == null && unobfuscatedDownload == null && requiresMappings(version)) {
            throw new IOException("Mojang metadata lacks client mappings for mapped version " + version.value());
        }
        List<DownloadArtifact> libraryArtifacts = new java.util.ArrayList<>();
        for (LibraryEntry library : detailBody.libraries()) {
            if (compilerClasspathLibrary(library) && library.downloads() != null && library.downloads().artifact() != null && library.downloads().artifact().url() != null) {
                try {
                    libraryArtifacts.add(library.downloads().artifact().toArtifact(ArtifactKind.JAR));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        try {
            OfficialUnobfuscatedClient officialUnobfuscatedClient = unobfuscatedEntry == null ? null : new OfficialUnobfuscatedClient(unobfuscatedEntry, unobfuscatedDownload.toArtifact(ArtifactKind.JAR));
            return new MinecraftDownloads(clientDownload.toArtifact(ArtifactKind.JAR), mappingDownload == null ? null : mappingDownload.toArtifact(ArtifactKind.MAPPING), officialUnobfuscatedClient, libraryArtifacts);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Mojang metadata has invalid download integrity for " + version.value(), exception);
        }
    }

    private <T> T read(URI uri, Class<T> type) throws IOException {
        URI current = requireAllowedMetadataUri(uri);
        Set<URI> visited = new HashSet<>();
        for (int redirect = 0; redirect <= MAXIMUM_REDIRECTS; redirect++) {
            if (!visited.add(current)) {
                throw new IOException("metadata redirect loop for " + displayUri(current));
            }
            HttpRequest request = HttpRequest.newBuilder(current).timeout(requestTimeout).GET().build();
            try {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    String location = response.headers().firstValue("Location").orElse(null);
                    response.body().close();
                    if (location == null) {
                        throw new IOException("metadata redirect missing Location for " + displayUri(current));
                    }
                    current = resolveRedirect(current, location);
                    continue;
                }
                if (status != 200) {
                    response.body().close();
                    throw new IOException("metadata HTTP " + status + " for " + displayUri(current));
                }
                try (InputStream body = response.body()) {
                    validateContentLength(response, current);
                    byte[] bytes = readBounded(body, current);
                    try {
                        return mapper.readValue(bytes, type);
                    } catch (RuntimeException exception) {
                        throw new IOException("invalid metadata JSON for " + displayUri(current), exception);
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("metadata request interrupted for " + displayUri(current), exception);
            }
        }
        throw new IOException("metadata redirect limit exceeded for " + displayUri(uri));
    }
}
package dev.mcdevmcp.benchmark;

import dev.mcdevmcp.storage.model.MinecraftVersion;
import io.modelcontextprotocol.json.McpJsonDefaults;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Explicit compiler inputs; artifact order is the selected official metadata library order.
 */
public record CorpusClasspathManifest(int schemaVersion, CorpusClasspathKind kind, MinecraftVersion minecraftVersion, String metadataSha256, CorpusClasspathMetadata metadata, List<CorpusClasspathArtifact> artifacts) {
    public static final String SYNTHETIC_METADATA_SHA256 = "0".repeat(64);

    public CorpusClasspathManifest {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("Unsupported corpus classpath manifest schema " + schemaVersion);
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        metadataSha256 = CorpusExpectation.requireSha256(metadataSha256, "metadata SHA-256");
        artifacts = List.copyOf(artifacts);
        if (kind == CorpusClasspathKind.MOJANG && (metadata == null || artifacts.isEmpty())) {
            throw new IllegalArgumentException("MOJANG manifests require official metadata and a complete nonempty classpath");
        }
        if (kind == CorpusClasspathKind.SYNTHETIC && (metadata != null || !SYNTHETIC_METADATA_SHA256.equals(metadataSha256))) {
            throw new IllegalArgumentException("SYNTHETIC manifests must explicitly omit official metadata");
        }
    }

    public String identity() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String field : List.of("mcdev-corpus-classpath-v1", kind.name(), minecraftVersion.value(), metadataSha256)) {
            add(digest, field);
        }
        for (CorpusClasspathArtifact artifact : artifacts) {
            add(digest, artifact.relativePath());
            add(digest, Long.toString(artifact.size()));
            add(digest, artifact.sha256());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static VerifiedCorpusClasspath verify(Path manifestPath, MinecraftVersion version, List<Path> outputRoots) throws Exception {
        Path manifest = manifestPath.toRealPath();
        if (!Files.isRegularFile(manifest)) throw new IOException("Classpath manifest must be a regular file");
        Path root = manifest.getParent();
        byte[] bytes = Files.readAllBytes(manifest);
        CorpusClasspathManifest decoded = McpJsonDefaults.getMapper().readValue(bytes, CorpusClasspathManifest.class);
        if (!decoded.minecraftVersion().equals(version)) {
            throw new IllegalArgumentException("Classpath Minecraft version does not match request");
        }
        List<Path> immutable = new ArrayList<>();
        immutable.add(manifest);
        List<Path> libraries = new ArrayList<>();
        for (CorpusClasspathArtifact artifact : decoded.artifacts()) {
            Path path = resolve(root, artifact.relativePath());
            addDistinct(immutable, path);
            if (Files.size(path) != artifact.size() || !AnalysisBenchmarkMain.sha256(path).equals(artifact.sha256())) {
                throw new IOException("Classpath artifact integrity mismatch: " + artifact.relativePath());
            }
            libraries.add(path);
        }
        if (decoded.kind() == CorpusClasspathKind.MOJANG) decoded.verifyOfficial(root, libraries, immutable);
        for (Path output : outputRoots) {
            Path canonical = prospective(output);
            for (Path input : immutable) {
                if (canonical.startsWith(input) || input.startsWith(canonical)) {
                    throw new IllegalArgumentException("Corpus output overlaps immutable classpath input: " + input);
                }
            }
        }
        String rawHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return new VerifiedCorpusClasspath(manifest, version, libraries, new CorpusClasspathEvidence(decoded.kind(), decoded.identity(), rawHash, decoded.metadataSha256(), decoded.artifacts()));
    }

    private void verifyOfficial(Path root, List<Path> paths, List<Path> immutable) throws Exception {
        Path global = resolve(root, metadata.globalManifestPath());
        Path detail = resolve(root, metadata.versionManifestPath());
        addDistinct(immutable, global);
        addDistinct(immutable, detail);
        if (!AnalysisBenchmarkMain.sha256(global).equals(metadata.globalManifestSha256()) || !AnalysisBenchmarkMain.sha256(detail).equals(metadataSha256) || !sha1(detail).equals(metadata.versionManifestSha1())) {
            throw new IOException("Official metadata integrity mismatch");
        }
        CorpusGlobalMetadata globalMetadata = McpJsonDefaults.getMapper().readValue(Files.readAllBytes(global), CorpusGlobalMetadata.class);
        List<CorpusOfficialVersion> matches = globalMetadata.versions().stream().filter(entry -> minecraftVersion.equals(entry.id())).toList();
        if (matches.size() != 1 || !metadata.versionManifestUrl().equals(matches.getFirst().url()) || !metadata.versionManifestSha1().equals(matches.getFirst().sha1())) {
            throw new IOException("Official global manifest version/URL/SHA-1 linkage mismatch");
        }
        CorpusVersionMetadata versionMetadata = McpJsonDefaults.getMapper().readValue(Files.readAllBytes(detail), CorpusVersionMetadata.class);
        if (!minecraftVersion.equals(versionMetadata.id()) || versionMetadata.libraries() == null) {
            throw new IOException("Requested version metadata mismatch");
        }
        List<CorpusOfficialArtifact> selected = new ArrayList<>();
        for (CorpusOfficialLibrary library : versionMetadata.libraries()) {
            if (library.name() == null) throw new IOException("Malformed library coordinate");
            String[] coordinate = library.name().split(":", -1);
            if (coordinate.length >= 4 && coordinate[3].startsWith("natives-")) continue;
            if (coordinate.length < 3 || java.util.Arrays.stream(coordinate).anyMatch(String::isBlank) || library.downloads() == null || library.downloads().artifact() == null) {
                throw new IOException("Malformed selected library: " + library.name());
            }
            CorpusOfficialArtifact artifact = library.downloads().artifact();
            portablePath(artifact.path());
            requireSha1(artifact.sha1());
            if (artifact.size() == null || artifact.size() <= 0 || artifact.url() == null || !"https".equals(artifact.url().getScheme()) || artifact.url().getHost() == null || artifact.url().getUserInfo() != null || artifact.url().getFragment() != null) {
                throw new IOException("Malformed selected artifact: " + library.name());
            }
            selected.add(artifact);
        }
        if (selected.size() != artifacts.size()) {
            throw new IOException("Classpath does not enumerate the complete official selected dependency set");
        }
        for (int i = 0; i < selected.size(); i++) {
            CorpusOfficialArtifact selectedArtifact = selected.get(i);
            CorpusClasspathArtifact listed = artifacts.get(i);
            if (!selectedArtifact.path().equals(listed.relativePath()) || selectedArtifact.size() != listed.size() || !selectedArtifact.sha1().equals(sha1(paths.get(i)))) {
                throw new IOException("Classpath differs from official metadata selection/order/integrity at " + i);
            }
        }
    }

    static Path portablePath(String value) {
        Objects.requireNonNull(value, "relativePath");
        if (value.isEmpty() || value.contains("\\") || value.contains(":")) {
            throw new IllegalArgumentException("Expected portable relative path: " + value);
        }
        Path path = Path.of(value);
        if (path.isAbsolute() || !path.normalize().equals(path)) {
            throw new IllegalArgumentException("Escaping or noncanonical relative path: " + value);
        }
        for (String part : value.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("Invalid relative path: " + value);
            }
        }
        return path;
    }

    private static Path resolve(Path root, String relative) throws IOException {
        Path result = root.resolve(portablePath(relative)).toRealPath();
        if (!result.startsWith(root) || !Files.isRegularFile(result)) {
            throw new IOException("Classpath input escapes manifest root or is not a regular file: " + relative);
        }
        return result;
    }

    private static void addDistinct(List<Path> paths, Path candidate) throws IOException {
        for (Path existing : paths) {
            if (Files.isSameFile(existing, candidate)) {
                throw new IOException("Duplicate underlying classpath input: " + candidate);
            }
        }
        paths.add(candidate);
    }

    private static Path prospective(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        Path ancestor = normalized;
        while (Files.notExists(ancestor)) ancestor = ancestor.getParent();
        return ancestor.toRealPath().resolve(ancestor.relativize(normalized));
    }

    static void requireSha1(String value) {
        if (value == null || value.length() != 40 || value.chars().anyMatch(c -> !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f'))) {
            throw new IllegalArgumentException("Expected lowercase SHA-1");
        }
    }

    private static String sha1(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) != -1; ) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void add(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

}
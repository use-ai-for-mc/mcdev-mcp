package dev.mcdevmcp.benchmark;

import dev.mcdevmcp.storage.model.MinecraftVersion;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CorpusClasspathManifestTest {
    @TempDir
    Path root;

    @Test
    void identityHasFixedPortableOrderedFraming() throws Exception {
        var first = new CorpusClasspathArtifact("a/lib.jar", 3, "a".repeat(64));
        var second = new CorpusClasspathArtifact("b/lib.jar", 7, "b".repeat(64));
        var manifest = synthetic(List.of(first, second));
        assertEquals("0935c9acb4d1bae846221e295e6db87bce4c946985376d2f57be19c63f8d8e76", manifest.identity());
        assertNotEquals(manifest.identity(), synthetic(List.of(second, first)).identity());
        assertNotEquals(manifest.identity(), synthetic(List.of(first)).identity());
        assertNotEquals(manifest.identity(), synthetic(List.of(first, new CorpusClasspathArtifact("b/lib.jar", 8, second.sha256()))).identity());
        assertNotEquals(manifest.identity(), new CorpusClasspathManifest(1, CorpusClasspathKind.SYNTHETIC, new MinecraftVersion("26.1"), CorpusClasspathManifest.SYNTHETIC_METADATA_SHA256, null, manifest.artifacts()).identity());
    }

    @Test
    void selectsOnlyListedPathsAndPreservesSameBasenameLibraries() throws Exception {
        var first = artifact("one/lib.jar", new byte[]{1});
        var second = artifact("two/lib.jar", new byte[]{2});
        artifact("unlisted.jar", new byte[]{3});
        Path manifest = write(synthetic(List.of(first, second)));
        VerifiedCorpusClasspath verified = verify(manifest);
        assertEquals(List.of(root.resolve("one/lib.jar").toRealPath(), root.resolve("two/lib.jar").toRealPath()), verified.paths());
        assertEquals(List.of(first, second), verified.evidence().artifacts());
        assertEquals(CorpusClasspathKind.SYNTHETIC, verified.evidence().kind());
        verified.verifyUnchanged(List.of(root.resolve("output")));
    }

    @Test
    void rejectsEscapeDuplicateMissingCorruptAndOutputOverlap() throws Exception {
        for (String invalid : List.of("../outside.jar", "/absolute.jar", "a/../b.jar", "a\\b.jar", "C:/foo.jar", "a//b.jar", "./x.jar")) {
            assertThrows(IllegalArgumentException.class, () -> new CorpusClasspathArtifact(invalid, 1, "a".repeat(64)), invalid);
        }
        var artifact = artifact("lib.jar", new byte[]{1, 2});
        Path manifest = write(synthetic(List.of(artifact, artifact)));
        assertThrows(Exception.class, () -> verify(manifest));
        write(synthetic(List.of(artifact)));
        assertThrows(IllegalArgumentException.class, () -> CorpusClasspathManifest.verify(manifest, ClasspathFixtures.VERSION, List.of(root)));
        Files.write(root.resolve("lib.jar"), new byte[]{9, 9});
        assertThrows(Exception.class, () -> verify(manifest));
        Files.delete(root.resolve("lib.jar"));
        assertThrows(Exception.class, () -> verify(manifest));
    }

    @Test
    void rejectsUnderlyingFileAliases() throws Exception {
        var artifact = artifact("one.jar", new byte[]{1});
        Files.createLink(root.resolve("two.jar"), root.resolve("one.jar"));
        Path manifest = write(synthetic(List.of(artifact, new CorpusClasspathArtifact("two.jar", artifact.size(), artifact.sha256()))));
        assertThrows(Exception.class, () -> verify(manifest));
    }

    @Test
    void detectsLibraryAndRawManifestMutation() throws Exception {
        var artifact = artifact("lib.jar", new byte[]{1});
        Path manifest = write(synthetic(List.of(artifact)));
        VerifiedCorpusClasspath verified = verify(manifest);
        Files.writeString(manifest, Files.readString(manifest) + "\n");
        assertThrows(Exception.class, () -> verified.verifyUnchanged(List.of()));
        write(synthetic(List.of(artifact)));
        Files.write(root.resolve("lib.jar"), new byte[]{2});
        assertThrows(Exception.class, () -> verified.verifyUnchanged(List.of()));
    }

    @Test
    void realMetadataBindsRequestedVersionAndCompleteOrderedSelection() throws Exception {
        CorpusClasspathManifest manifest = official();
        Path path = write(manifest);
        assertEquals(CorpusClasspathKind.MOJANG, verify(path).evidence().kind());
        assertThrows(Exception.class, () -> CorpusClasspathManifest.verify(path, new MinecraftVersion("26.1"), List.of()));
        write(new CorpusClasspathManifest(1, manifest.kind(), manifest.minecraftVersion(), manifest.metadataSha256(), manifest.metadata(), manifest.artifacts().subList(0, 1)));
        assertThrows(Exception.class, () -> verify(path));
        write(new CorpusClasspathManifest(1, manifest.kind(), manifest.minecraftVersion(), manifest.metadataSha256(), manifest.metadata(), manifest.artifacts().reversed()));
        assertThrows(Exception.class, () -> verify(path));
        assertThrows(IllegalArgumentException.class, () -> new CorpusClasspathManifest(1, CorpusClasspathKind.MOJANG, ClasspathFixtures.VERSION, manifest.metadataSha256(), manifest.metadata(), List.of()));
        var wrongLink = new CorpusClasspathMetadata("global.json", manifest.metadata().globalManifestSha256(), "version.json", URI.create("https://example.test/other.json"), manifest.metadata().versionManifestSha1());
        write(new CorpusClasspathManifest(1, manifest.kind(), manifest.minecraftVersion(), manifest.metadataSha256(), wrongLink, manifest.artifacts()));
        assertThrows(Exception.class, () -> verify(path));
    }

    @Test
    void rejectsZeroSizeAndNonHttpsSelectedOfficialArtifacts() throws Exception {
        CorpusClasspathManifest valid = official();
        CorpusClasspathArtifact artifact = valid.artifacts().getFirst();
        for (Map<String, Object> invalid : List.of(Map.<String, Object>of("path", artifact.relativePath(), "size", 0, "sha1", "a".repeat(40), "url", "https://example.test/lib.jar"), Map.<String, Object>of("path", artifact.relativePath(), "size", 1, "sha1", "a".repeat(40), "url", "file:///lib.jar"), Map.<String, Object>of("path", artifact.relativePath(), "size", 1, "sha1", "a".repeat(40), "url", "https:lib.jar"))) {
            byte[] detail = McpJsonDefaults.getMapper().writeValueAsBytes(Map.of("id", "1.21.11", "libraries", List.of(Map.of("name", "g:a:1", "downloads", Map.of("artifact", invalid)))));
            Files.write(root.resolve("version.json"), detail);
            CorpusClasspathMetadata metadata = metadataFor(detail);
            Path path = write(new CorpusClasspathManifest(1, CorpusClasspathKind.MOJANG, ClasspathFixtures.VERSION, sha(detail, "SHA-256"), metadata, List.of(artifact)));
            Exception failure = assertThrows(Exception.class, () -> verify(path));
            assertTrue(failure.getMessage().contains("Malformed selected artifact"));
        }
    }

    @Test
    void rejectsMalformedSelectedMetadataAndOfficialDigestMismatch() throws Exception {
        CorpusClasspathManifest manifest = official();
        byte[] malformed = McpJsonDefaults.getMapper().writeValueAsBytes(Map.of("id", "1.21.11", "libraries", List.of(Map.of("name", "missing:artifact:1"))));
        Files.write(root.resolve("version.json"), malformed);
        Path path = write(manifest);
        assertThrows(Exception.class, () -> verify(path));
        var rebound = metadataFor(malformed);
        write(new CorpusClasspathManifest(1, CorpusClasspathKind.MOJANG, ClasspathFixtures.VERSION, sha(malformed, "SHA-256"), rebound, manifest.artifacts()));
        Exception failure = assertThrows(Exception.class, () -> verify(path));
        assertTrue(failure.getMessage().contains("Malformed selected library"));
    }

    private CorpusClasspathManifest official() throws Exception {
        var a = artifact("g/a/1/a.jar", new byte[]{1});
        var b = artifact("g/b/1/b.jar", new byte[]{2});
        byte[] detail = McpJsonDefaults.getMapper().writeValueAsBytes(Map.of("id", "1.21.11", "libraries", List.of(library("g:a:1", a), Map.of("name", "g:n:1:natives-windows"), library("g:b:1", b))));
        Files.write(root.resolve("version.json"), detail);
        return new CorpusClasspathManifest(1, CorpusClasspathKind.MOJANG, ClasspathFixtures.VERSION, sha(detail, "SHA-256"), metadataFor(detail), List.of(a, b));
    }

    private CorpusClasspathMetadata metadataFor(byte[] detail) throws Exception {
        String sha1 = sha(detail, "SHA-1");
        byte[] global = McpJsonDefaults.getMapper().writeValueAsBytes(Map.of("versions", List.of(Map.of("id", "1.21.11", "url", "https://example.test/version.json", "sha1", sha1))));
        Files.write(root.resolve("global.json"), global);
        return new CorpusClasspathMetadata("global.json", sha(global, "SHA-256"), "version.json", URI.create("https://example.test/version.json"), sha1);
    }

    private Map<String, Object> library(String name, CorpusClasspathArtifact artifact) throws Exception {
        return Map.of("name", name, "downloads", Map.of("artifact", Map.of("path", artifact.relativePath(), "size", artifact.size(), "sha1", sha(Files.readAllBytes(root.resolve(artifact.relativePath())), "SHA-1"), "url", "https://example.test/" + artifact.relativePath())));
    }

    private static String sha(byte[] bytes, String algorithm) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    }

    private CorpusClasspathArtifact artifact(String relative, byte[] bytes) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
        return new CorpusClasspathArtifact(relative, bytes.length, AnalysisBenchmarkMain.sha256(path));
    }

    private static CorpusClasspathManifest synthetic(List<CorpusClasspathArtifact> artifacts) {
        return new CorpusClasspathManifest(1, CorpusClasspathKind.SYNTHETIC, ClasspathFixtures.VERSION, CorpusClasspathManifest.SYNTHETIC_METADATA_SHA256, null, artifacts);
    }

    private Path write(CorpusClasspathManifest manifest) throws Exception {
        Path path = root.resolve("classpath.json");
        Files.write(path, McpJsonDefaults.getMapper().writeValueAsBytes(manifest));
        return path;
    }

    private VerifiedCorpusClasspath verify(Path path) throws Exception {
        return CorpusClasspathManifest.verify(path, ClasspathFixtures.VERSION, List.of(root.resolve("output")));
    }
}
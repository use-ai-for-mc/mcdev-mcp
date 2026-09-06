package dev.mcdevmcp.benchmark;

import dev.mcdevmcp.storage.model.MinecraftVersion;
import io.modelcontextprotocol.json.McpJsonDefaults;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

final class ClasspathFixtures {
    static final MinecraftVersion VERSION = new MinecraftVersion("1.21.11");
    static final CorpusClasspathManifest EMPTY = new CorpusClasspathManifest(1, CorpusClasspathKind.SYNTHETIC, VERSION, CorpusClasspathManifest.SYNTHETIC_METADATA_SHA256, null, List.of());
    static final String IDENTITY;
    static final String RAW_HASH;
    private static final byte[] BYTES;

    static {
        try {
            IDENTITY = EMPTY.identity();
            BYTES = McpJsonDefaults.getMapper().writeValueAsBytes(EMPTY);
            RAW_HASH = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BYTES));
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private ClasspathFixtures() {
    }

    static Path empty(Path root) {
        try {
            Files.createDirectories(root);
            Path manifest = root.resolve("synthetic-classpath.json");
            if (Files.notExists(manifest)) Files.write(manifest, BYTES);
            return manifest;
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
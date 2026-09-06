package dev.mcdevmcp.storage;

import dev.mcdevmcp.storage.model.MinecraftVersion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class CacheCleanerTest {
    private static void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }
    }

    @Test
    void cleansOneVersionWithoutTouchingSibling() throws Exception {
        PlatformPaths paths = new PlatformPaths(Files.createTempDirectory("cache-cleaner"));
        MinecraftVersion one = new MinecraftVersion("1.21.11");
        MinecraftVersion two = new MinecraftVersion("26.1");
        Files.createDirectories(paths.versionCache(one));
        Files.createDirectories(paths.versionCache(two));
        Files.writeString(paths.versionCache(one).resolve("client.txt"), "one");
        Files.writeString(paths.versionCache(two).resolve("client.txt"), "two");
        new CacheCleaner(paths).clean(one);
        assertFalse(Files.exists(paths.versionCache(one)));
        assertTrue(Files.exists(paths.versionCache(two).resolve("client.txt")));
    }

    @Test
    void cacheOnlyPreservesH2AndCallgraphIndexes() throws Exception {
        PlatformPaths paths = new PlatformPaths(Files.createTempDirectory("cache-only"));
        MinecraftVersion version = new MinecraftVersion("1.21.11");
        Path client = paths.versionCache(version).resolve("client/source.java");
        Path callgraph = paths.callgraphBundle(version).resolve("publication.lock");
        Path database = paths.symbolDatabase(version);
        Files.createDirectories(client.getParent());
        Files.createDirectories(callgraph.getParent());
        Files.createDirectories(database.getParent());
        Files.writeString(client, "source");
        Files.writeString(callgraph, "lock");
        Files.writeString(database, "h2");

        new CacheCleaner(paths).cleanCache(version);

        assertFalse(Files.exists(client));
        assertTrue(Files.exists(callgraph));
        assertEquals("h2", Files.readString(database));
    }

    @Test
    void enumeratesTheUnionOfCacheAndIndexVersionsInStableOrder() throws Exception {
        PlatformPaths paths = new PlatformPaths(Files.createTempDirectory("cache-versions"));
        Files.createDirectories(paths.versionCache(new MinecraftVersion("26.1")));
        Files.createDirectories(paths.indexRoot(new MinecraftVersion("1.21.11")));
        Files.createDirectories(paths.indexRoot(new MinecraftVersion("26.1")));
        Files.writeString(paths.cacheRoot().resolve("cache/not-a-directory"), "ignore");

        assertEquals(List.of("1.21.11", "26.1"), new CacheCleaner(paths).cachedVersions().stream().map(MinecraftVersion::value).toList());
    }

    @Test
    void rejectsLinkedCacheArtifactsBeforeDeletingAnything() throws Exception {
        PlatformPaths paths = new PlatformPaths(Files.createTempDirectory("cache-linked"));
        MinecraftVersion version = new MinecraftVersion("1.21.11");
        Path outside = Files.createTempDirectory("cache-linked-outside");
        Path sentinel = outside.resolve("sentinel.txt");
        Path ordinary = paths.versionCache(version).resolve("ordinary.txt");
        Files.createDirectories(ordinary.getParent());
        Files.writeString(sentinel, "keep");
        Files.writeString(ordinary, "keep");
        createSymbolicLinkOrSkip(paths.versionCache(version).resolve("linked"), outside);

        assertThrows(IOException.class, () -> new CacheCleaner(paths).cleanCache(version));
        assertEquals("keep", Files.readString(sentinel));
        assertEquals("keep", Files.readString(ordinary));
    }

    @Test
    void allCleanupPreflightsCacheBeforeDeletingTheH2Index() throws Exception {
        PlatformPaths paths = new PlatformPaths(Files.createTempDirectory("cache-all-linked"));
        MinecraftVersion version = new MinecraftVersion("1.21.11");
        Path outside = Files.createTempDirectory("cache-all-linked-outside");
        Path database = paths.symbolDatabase(version);
        Files.createDirectories(paths.versionCache(version));
        Files.createDirectories(database.getParent());
        Files.writeString(database, "h2");
        createSymbolicLinkOrSkip(paths.versionCache(version).resolve("linked"), outside);

        assertThrows(IOException.class, () -> new CacheCleaner(paths).cleanAll(version));
        assertEquals("h2", Files.readString(database));
    }
}

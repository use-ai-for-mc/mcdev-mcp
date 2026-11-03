package dev.mcdevmcp.storage;

import dev.mcdevmcp.storage.symbols.IndexLock;
import dev.mcdevmcp.storage.symbols.IndexCleaner;
import dev.mcdevmcp.storage.symbols.VersionStateRepository;
import dev.mcdevmcp.storage.model.FabricApiVersion;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.VersionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class PlatformPathsTest {
    private static final MinecraftVersion VERSION = new MinecraftVersion("1.21.5");
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesMacOsLinuxAndWindowsCacheRootsWithoutAPrivateEnvironmentOverride() {
        Path home = Path.of("/Users/alex");
        Path linuxHome = Path.of("/home/alex");

        assertEquals(Path.of("/Users/alex/Library/Caches/mcdev-mcp"), PlatformPaths.forEnvironment("Mac OS X", Map.of(), home).cacheRoot());
        assertEquals(Path.of("/home/alex/.cache/mcdev-mcp"), PlatformPaths.forEnvironment("Linux", Map.of(), linuxHome).cacheRoot());
        assertEquals(Path.of("/var/cache/alex/mcdev-mcp"), PlatformPaths.forEnvironment("Linux", Map.of("XDG_CACHE_HOME", "/var/cache/alex"), linuxHome).cacheRoot());
        assertEquals(Path.of("C:/Users/alex/AppData/Local/mcdev-mcp/Cache"), PlatformPaths.forEnvironment("Windows 11", Map.of("LOCALAPPDATA", "C:/Users/alex/AppData/Local"), Path.of("C:/Users/alex")).cacheRoot());
    }

    @Test
    void preservesTheVersionedCacheAndIndexLayout() {
        var paths = new PlatformPaths(Path.of("/cache/mcdev-mcp"));

        assertEquals(Path.of("/cache/mcdev-mcp/cache/1.21.5"), paths.versionCache(VERSION));
        assertEquals(Path.of("/cache/mcdev-mcp/cache/1.21.5/client"), paths.sourceRoot(VERSION));
        assertEquals(Path.of("/cache/mcdev-mcp/cache/1.21.5/jars/1.21.5_unobfuscated.jar"), paths.remappedJar(VERSION));
        assertEquals(Path.of("/cache/mcdev-mcp/cache/1.21.5/callgraph/client-remapped.jar"), paths.remappedCallgraphJar(VERSION));
        assertEquals(Path.of("/cache/mcdev-mcp/cache/1.21.5/indexes/callgraph"), paths.callgraphBundle(VERSION));
        assertEquals(Path.of("/cache/mcdev-mcp/cache/fabric-api-0.120.0"), paths.fabricSourceRoot(new FabricApiVersion("0.120.0")));
        assertEquals(Path.of("/cache/mcdev-mcp/index/1.21.5/symbols.jsonl"), paths.symbolIndex(VERSION));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.21.5", "0.120.0+1.21.5", "1.21.5-pre1", "1_21_5", "версия"})
    void acceptedVersionsRemainContainedByTheirCacheAndIndexRoots(String value) {
        var paths = new PlatformPaths(temporaryDirectory.resolve("mcdev-mcp"));
        var minecraftVersion = new MinecraftVersion(value);
        var fabricVersion = new FabricApiVersion(value);

        assertTrue(paths.versionCache(minecraftVersion).startsWith(paths.cacheRoot()));
        assertTrue(paths.sourceRoot(minecraftVersion).startsWith(paths.versionCache(minecraftVersion)));
        assertTrue(paths.remappedJar(minecraftVersion).startsWith(paths.versionCache(minecraftVersion)));
        assertTrue(paths.remappedCallgraphJar(minecraftVersion).startsWith(paths.versionCache(minecraftVersion)));
        assertTrue(paths.callgraphBundle(minecraftVersion).startsWith(paths.versionCache(minecraftVersion)));
        assertTrue(paths.indexRoot(minecraftVersion).startsWith(paths.cacheRoot()));
        assertTrue(paths.symbolIndex(minecraftVersion).startsWith(paths.indexRoot(minecraftVersion)));
        assertTrue(paths.fabricSourceRoot(fabricVersion).startsWith(paths.cacheRoot()));
    }

    @Test
    void reportsReadyLegacySourceOnlyAndAbsentVersions() throws Exception {
        var paths = new PlatformPaths(temporaryDirectory);
        var states = new VersionStateRepository(paths);

        assertEquals(VersionState.ABSENT, states.state(VERSION));
        assertTrue(states.isAbsent(VERSION));
        Files.createDirectories(paths.sourceRoot(VERSION));
        assertTrue(states.isSourceOnly(VERSION));
        Files.createDirectories(paths.symbolIndex(VERSION).getParent());
        Files.writeString(paths.symbolIndex(VERSION), "not-a-database");
        assertFalse(states.isReady(VERSION));
        assertTrue(states.needsRebuild(VERSION), "an index this release cannot read has to be rebuilt");
        Files.delete(paths.symbolIndex(VERSION));
        Files.writeString(paths.indexRoot(VERSION).resolve("manifest.json"), "{}");
        assertTrue(states.needsRebuild(VERSION));
        Files.delete(paths.indexRoot(VERSION).resolve("manifest.json"));
        assertTrue(states.isSourceOnly(VERSION));
        Files.writeString(paths.indexRoot(VERSION).resolve("symbols.mv.db"), "index from an earlier release");
        assertTrue(states.needsRebuild(VERSION));
    }

    @Test
    void cleansOnlyIndexFilesContainedByTheVersionRoot() throws Exception {
        var paths = new PlatformPaths(temporaryDirectory);
        Path indexRoot = paths.indexRoot(VERSION);
        Path outside = temporaryDirectory.resolve("outside.json");
        Files.createDirectories(indexRoot.resolve("minecraft/net/minecraft"));
        Files.writeString(paths.symbolIndex(VERSION), "db");
        Files.writeString(paths.symbolIndex(VERSION).resolveSibling("symbols.lock"), "lock");
        Files.writeString(paths.symbolIndex(VERSION).resolveSibling("symbols.jsonl.99.tmp"), "tmp");
        Files.writeString(paths.symbolIndex(VERSION).resolveSibling("symbols.jsonl.bak"), "backup");
        Files.writeString(indexRoot.resolve("manifest.json"), "{}");
        Files.writeString(indexRoot.resolve("minecraft/net/minecraft/world.json"), "{}");
        Files.writeString(outside, "keep");

        new IndexCleaner(paths).cleanIndex(VERSION);

        assertTrue(Files.exists(paths.symbolIndex(VERSION).resolveSibling("symbols.jsonl.lock")));
        assertFalse(Files.exists(paths.symbolIndex(VERSION)));
        assertFalse(Files.exists(indexRoot.resolve("manifest.json")));
        assertTrue(Files.exists(outside));
    }

    @Test
    void waitsForAnActiveReaderBeforeDeletingIndexArtifacts() throws Exception {
        var paths = new PlatformPaths(temporaryDirectory);
        Path database = paths.symbolIndex(VERSION);
        Files.createDirectories(database.getParent());
        Files.writeString(database, "database");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Void> clean;
            try (var reader = IndexLock.read(database, Duration.ofSeconds(1))) {
                assertTrue(reader.isHeld());
                clean = executor.submit(() -> {
                    new IndexCleaner(paths).cleanIndex(VERSION);
                    return null;
                });

                assertThrows(TimeoutException.class, () -> clean.get(100, TimeUnit.MILLISECONDS));
            }
            clean.get(2, TimeUnit.SECONDS);
        }
        assertFalse(Files.exists(database));
    }

    @Test
    void waitsForAnActiveWriterBeforeDeletingIndexArtifacts() throws Exception {
        var paths = new PlatformPaths(temporaryDirectory);
        Path database = paths.symbolIndex(VERSION);
        Files.createDirectories(database.getParent());
        Files.writeString(database, "database");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Void> clean;
            try (var writer = IndexLock.write(database, Duration.ofSeconds(1))) {
                assertTrue(writer.isHeld());
                clean = executor.submit(() -> {
                    new IndexCleaner(paths).cleanIndex(VERSION);
                    return null;
                });

                assertThrows(TimeoutException.class, () -> clean.get(100, TimeUnit.MILLISECONDS));
            }
            clean.get(2, TimeUnit.SECONDS);
        }
        assertFalse(Files.exists(database));
    }

}

package dev.mcdevmcp.storage.symbols;

import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class IndexCleanerTest {
    private static final MinecraftVersion VERSION = new MinecraftVersion("1.21.5");
    @TempDir
    Path temporaryDirectory;

    private static void createIndex(Path database) throws IOException {
        SymbolIndexFixture.writeEmpty(database, VERSION, Path.of("client"), "a".repeat(64), Instant.EPOCH);
    }

    @Test
    void removesEveryIndexArtifactExceptTheApplicationLock() throws Exception {
        var paths = new PlatformPaths(temporaryDirectory);
        Path database = paths.symbolIndex(VERSION);
        Path root = paths.indexRoot(VERSION);
        createIndex(database);
        Files.writeString(root.resolve("symbols.mv.db"), "index from an earlier release");
        Files.writeString(Files.createDirectories(root.resolve("minecraft")).resolve("legacy.json"), "legacy");

        new IndexCleaner(paths).cleanIndex(VERSION);

        try (var remaining = Files.list(root)) {
            assertEquals(List.of("symbols.jsonl.lock"), remaining.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void deletesTheSupersededDatabaseAndNothingElse() throws Exception {
        var paths = new PlatformPaths(temporaryDirectory);
        Path root = paths.indexRoot(VERSION);
        createIndex(paths.symbolIndex(VERSION));
        for (String name : List.of("symbols.mv.db", "symbols.mv.db.bak", "symbols.mv.db.lock", "symbols.trace.db")) {
            Files.writeString(root.resolve(name), "database from an earlier development build");
        }
        Files.writeString(root.resolve("manifest.json"), "index from the Node release");

        new IndexCleaner(paths).deleteSupersededDatabase(VERSION);

        try (var remaining = Files.list(root)) {
            assertEquals(List.of("manifest.json", "symbols.jsonl"), remaining.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void leavesAVersionWithoutAnIndexRootAlone() throws Exception {
        var paths = new PlatformPaths(temporaryDirectory);

        new IndexCleaner(paths).deleteSupersededDatabase(VERSION);

        assertFalse(Files.exists(paths.indexRoot(VERSION)));
    }

    @Test
    void excludesReadersWhileDeletionIsInProgress() throws Exception {
        var paths = new PlatformPaths(temporaryDirectory);
        Path database = paths.symbolIndex(VERSION);
        createIndex(database);
        Files.writeString(paths.indexRoot(VERSION).resolve("legacy.json"), "delete");
        var files = new PausingIndexFileOperations();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var clean = executor.submit(() -> {
                new IndexCleaner(paths, files).cleanIndex(VERSION);
                return null;
            });
            try {
                assertTrue(files.awaitDeletion());
                assertThrows(IOException.class, () -> {
                    try (var unexpected = IndexLock.read(database, Duration.ofMillis(100))) {
                        assertTrue(unexpected.isHeld(), "reader acquired while the cleaner held the exclusive lock");
                        throw new AssertionError("reader acquired while the cleaner held the exclusive lock");
                    }
                });
            } finally {
                files.continueDeletion();
            }
            clean.get(5, TimeUnit.SECONDS);
        }
        assertFalse(Files.exists(database));
    }

    @Test
    void keepsTheSymbolIndexWhenAnotherArtifactCannotBeDeleted() throws Exception {
        var paths = new PlatformPaths(temporaryDirectory);
        Path database = paths.symbolIndex(VERSION);
        Path legacy = paths.indexRoot(VERSION).resolve("legacy.json");
        createIndex(database);
        Files.writeString(legacy, "undeletable");
        var files = new ForcedFallbackMoveStrategy(0, ForcedFallbackMoveStrategy.FailureTiming.BEFORE_SIDE_EFFECT, ForcedFallbackMoveStrategy.DeleteFailure.ANY);

        IOException failure = assertThrows(IOException.class, () -> new IndexCleaner(paths, files).cleanIndex(VERSION));

        assertTrue(failure.getMessage().contains("forced delete failure"));
        assertTrue(Files.exists(database));
        assertTrue(Files.exists(legacy));
    }

    @Test
    void cleansAnIndexRootWithoutASymbolIndex() throws Exception {
        var paths = new PlatformPaths(temporaryDirectory);
        Path database = paths.symbolIndex(VERSION);
        Files.createDirectories(database.getParent());
        Files.writeString(database.resolveSibling("legacy.json"), "delete");

        new IndexCleaner(paths).cleanIndex(VERSION);

        assertFalse(Files.exists(database));
        assertFalse(Files.exists(database.resolveSibling("legacy.json")));
        assertTrue(Files.exists(database.resolveSibling("symbols.jsonl.lock")));
    }

    @Test
    void rejectsSymlinkedVersionIndexRootBeforeCreatingTheApplicationLock() throws Exception {
        var paths = new PlatformPaths(temporaryDirectory.resolve("cache-root"));
        Path root = paths.indexRoot(VERSION);
        Path outside = temporaryDirectory.resolve("outside-index");
        Files.createDirectories(root.getParent());
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("keep.json"), "keep");
        Files.createSymbolicLink(root, outside);

        IOException failure = assertThrows(IOException.class, () -> new IndexCleaner(paths).cleanIndex(VERSION));

        assertTrue(failure.getMessage().contains("symbolic link"));
        assertEquals("keep", Files.readString(outside.resolve("keep.json")));
        assertFalse(Files.exists(outside.resolve("symbols.jsonl.lock")));
    }

    @Test
    void rejectsSymlinkedSymbolDatabaseBeforeCreatingTheApplicationLock() throws Exception {
        var paths = new PlatformPaths(temporaryDirectory.resolve("cache-root"));
        Path database = paths.symbolIndex(VERSION);
        Path outside = temporaryDirectory.resolve("outside.jsonl");
        Files.createDirectories(database.getParent());
        Files.writeString(outside, "keep");
        Files.writeString(database.resolveSibling("legacy.json"), "keep");
        Files.createSymbolicLink(database, outside);

        IOException failure = assertThrows(IOException.class, () -> new IndexCleaner(paths).cleanIndex(VERSION));

        assertTrue(failure.getMessage().contains("symbolic link"));
        assertEquals("keep", Files.readString(outside));
        assertEquals("keep", Files.readString(database.resolveSibling("legacy.json")));
        assertFalse(Files.exists(database.resolveSibling("symbols.jsonl.lock")));
    }
}

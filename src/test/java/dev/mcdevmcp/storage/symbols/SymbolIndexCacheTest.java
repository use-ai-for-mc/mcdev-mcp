package dev.mcdevmcp.storage.symbols;

import dev.mcdevmcp.storage.model.MinecraftVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SymbolIndexCacheTest {
    private static final MinecraftVersion VERSION = new MinecraftVersion("1.21.5");
    @TempDir
    Path temporaryDirectory;

    private Path index(String name, String typeName) throws IOException {
        Path file = temporaryDirectory.resolve(name);
        SymbolIndexFixture.write(file, VERSION, Path.of("client"), "0".repeat(64), Instant.EPOCH, List.of(SymbolIndexFixture.minecraftPackage(1, "alpha")), List.of(SymbolIndexFixture.minecraftType(1, 1, "alpha." + typeName, typeName, "class", null, List.of(), "alpha/" + typeName + ".java", 1, 1)), List.of(), List.of(), List.of());
        return file;
    }

    @Test
    void keepsALoadedIndexUntilItsFileIsReplaced() throws Exception {
        var cache = new SymbolIndexCache(2);
        Path file = index("symbols.jsonl", "Before");

        SymbolIndex first = cache.load(file);
        assertSame(first, cache.load(file));
        assertSame(first, cache.load(temporaryDirectory.resolve("nested/../symbols.jsonl")));
        assertNotNull(first.classByName("alpha.Before"));

        index("symbols.jsonl", "After");
        SymbolIndex replaced = cache.load(file);
        assertNotSame(first, replaced);
        assertNull(replaced.classByName("alpha.Before"));
        assertNotNull(replaced.classByName("alpha.After"));
    }

    @Test
    void evictsTheLeastRecentlyUsedIndex() throws Exception {
        var cache = new SymbolIndexCache(2);
        Path one = index("one.jsonl", "One");
        Path two = index("two.jsonl", "Two");
        Path three = index("three.jsonl", "Three");

        SymbolIndex firstOne = cache.load(one);
        SymbolIndex firstTwo = cache.load(two);
        assertSame(firstOne, cache.load(one));
        cache.load(three);

        assertSame(firstOne, cache.load(one));
        assertNotSame(firstTwo, cache.load(two));
    }

    @Test
    void concurrentFirstLoadsShareOneIndex() throws Exception {
        var cache = new SymbolIndexCache(1);
        Path file = index("symbols.jsonl", "Shared");
        List<Future<SymbolIndex>> loads = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int task = 0; task < 16; task++) {
                loads.add(executor.submit(() -> cache.load(file)));
            }
            SymbolIndex expected = loads.getFirst().get(10, TimeUnit.SECONDS);
            for (Future<SymbolIndex> load : loads) {
                assertSame(expected, load.get(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void reportsUnreadableAndUnresolvableFilesAsStorageFailures() throws Exception {
        var cache = new SymbolIndexCache(1);
        assertThrows(IOException.class, () -> cache.load(temporaryDirectory.resolve("missing.jsonl")));

        Path file = temporaryDirectory.resolve("unknown-kind.jsonl");
        SymbolIndexFixture.write(file, VERSION, Path.of("client"), "0".repeat(64), Instant.EPOCH, List.of(SymbolIndexFixture.minecraftPackage(1, "alpha")), List.of(SymbolIndexFixture.minecraftType(1, 1, "alpha.Odd", "Odd", "hologram", null, List.of(), "alpha/Odd.java", 1, 1)), List.of(), List.of(), List.of());
        IOException failure = assertThrows(IOException.class, () -> cache.load(file));
        assertTrue(failure.getMessage().contains("invalid rows"));
        assertThrows(IllegalArgumentException.class, () -> new SymbolIndexCache(0));
    }
}

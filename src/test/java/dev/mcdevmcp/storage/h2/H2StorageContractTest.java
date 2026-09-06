package dev.mcdevmcp.storage.h2;

import dev.mcdevmcp.storage.PlatformPaths;

import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.SourceNamespace;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class H2StorageContractTest {
    @Test
    void usesTypedVersionsAndExactH2DatabasePaths() {
        var version = new MinecraftVersion("1.21.5");
        var paths = new PlatformPaths(Path.of("/cache/mcdev-mcp"));

        assertEquals(Path.of("/cache/mcdev-mcp/index/1.21.5/symbols.mv.db"), paths.symbolDatabase(version));
        assertEquals(Path.of("/cache/mcdev-mcp/cache/1.21.5/indexes/callgraph"), paths.callgraphBundle(version));
        assertEquals("minecraft", SourceNamespace.MINECRAFT.wireName());
        assertEquals(SourceNamespace.FABRIC, SourceNamespace.fromWireName("fabric"));
        assertThrows(IllegalArgumentException.class, () -> new MinecraftVersion("../unsafe"));
    }

    @Test
    void treatsDarwinAsMacAndBlankEnvironmentRootsAsFallbacks() {
        Path home = Path.of("/Users/alex");

        assertEquals(Path.of("/Users/alex/Library/Caches/mcdev-mcp"), PlatformPaths.forEnvironment("Darwin", Map.of(), home).cacheRoot());
        assertEquals(Path.of("/Users/alex/AppData/Local/mcdev-mcp/Cache"), PlatformPaths.forEnvironment("Windows", Map.of("LOCALAPPDATA", "   "), home).cacheRoot());
        assertEquals(Path.of("/Users/alex/.cache/mcdev-mcp"), PlatformPaths.forEnvironment("Linux", Map.of("XDG_CACHE_HOME", ""), home).cacheRoot());
    }
}

package dev.mcdevmcp.analysis.index;

import dev.mcdevmcp.storage.model.FabricApiVersion;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.SourceNamespace;
import dev.mcdevmcp.support.Cancellation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class IndexRequestTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void sourceRootsEnforceTypedIdentityAndNormalizePaths() {
        Path relative = Path.of("sources", "..", "sources", "minecraft");
        var minecraft = new SourceRoot(SourceNamespace.MINECRAFT, Optional.empty(), relative);
        var fabric = new SourceRoot(SourceNamespace.FABRIC, Optional.of(new FabricApiVersion("0.120.0")), temporaryDirectory.resolve("fabric"));

        assertEquals(relative.toAbsolutePath().normalize(), minecraft.path());
        assertEquals(SourceNamespace.FABRIC, fabric.namespace());
        assertThrows(IllegalArgumentException.class, () -> new SourceRoot(SourceNamespace.MINECRAFT, Optional.of(new FabricApiVersion("0.120.0")), relative));
        assertThrows(IllegalArgumentException.class, () -> new SourceRoot(SourceNamespace.FABRIC, Optional.empty(), relative));
    }

    @Test
    void requestDeepCopiesPathsAndRejectsDuplicateOrInvalidState() {
        var roots = new ArrayList<>(List.of(new SourceRoot(SourceNamespace.MINECRAFT, Optional.empty(), temporaryDirectory.resolve("sources"))));
        var classpath = new ArrayList<>(List.of(temporaryDirectory.resolve("lib/../lib/dependency.jar")));
        var request = new IndexRequest(new MinecraftVersion("1.21.5"), roots, temporaryDirectory.resolve("game.jar"), classpath, temporaryDirectory.resolve("symbols.mv.db"), 4, (_, _, _) -> {
        }, Cancellation.none());

        roots.clear();
        classpath.clear();
        assertEquals(1, request.sourceRoots().size());
        assertEquals(temporaryDirectory.resolve("lib/dependency.jar").toAbsolutePath().normalize(), request.classpath().getFirst());
        assertThrows(UnsupportedOperationException.class, () -> request.classpath().add(Path.of("other.jar")));
        assertThrows(IllegalArgumentException.class, () -> new IndexRequest(request.minecraftVersion(), List.of(request.sourceRoots().getFirst(), request.sourceRoots().getFirst()), request.remappedJar(), List.of(), request.outputDatabase(), 1, request.progress(), request.cancellation()));
        SourceRoot duplicateIdentity = new SourceRoot(SourceNamespace.MINECRAFT, Optional.empty(), temporaryDirectory.resolve("other-sources"));
        assertThrows(IllegalArgumentException.class, () -> new IndexRequest(request.minecraftVersion(), List.of(request.sourceRoots().getFirst(), duplicateIdentity), request.remappedJar(), List.of(), request.outputDatabase(), 1, request.progress(), request.cancellation()));
        assertThrows(IllegalArgumentException.class, () -> new IndexRequest(request.minecraftVersion(), request.sourceRoots(), request.remappedJar(), List.of(Path.of("a.jar"), Path.of("a.jar")), request.outputDatabase(), 1, request.progress(), request.cancellation()));
        assertThrows(IllegalArgumentException.class, () -> new IndexRequest(request.minecraftVersion(), request.sourceRoots(), request.remappedJar(), List.of(request.remappedJar()), request.outputDatabase(), 1, request.progress(), request.cancellation()));
        assertThrows(IllegalArgumentException.class, () -> new IndexRequest(request.minecraftVersion(), request.sourceRoots(), request.remappedJar(), List.of(), request.outputDatabase(), 0, request.progress(), request.cancellation()));
    }

    @Test
    void parsesThreadEnvironmentAtTheTypedBoundary() {
        int available = Runtime.getRuntime().availableProcessors();

        assertEquals(available, IndexRequest.threadsFromEnvironment(Map.of()));
        assertEquals(1, IndexRequest.threadsFromEnvironment(Map.of("MCDEV_INDEX_THREADS", "1")));
        assertEquals(available, IndexRequest.threadsFromEnvironment(Map.of("MCDEV_INDEX_THREADS", Integer.toString(Integer.MAX_VALUE))));
        for (String invalid : List.of("", " ", "0", "-1", "1.5", "many")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> IndexRequest.threadsFromEnvironment(Map.of("MCDEV_INDEX_THREADS", invalid)));
            assertTrue(failure.getMessage().contains("MCDEV_INDEX_THREADS"));
            assertTrue(failure.getMessage().contains("'" + invalid + "'"));
        }
    }
}

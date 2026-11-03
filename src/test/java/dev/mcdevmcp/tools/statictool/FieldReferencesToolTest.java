package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.CompleteToolBindings;
import dev.mcdevmcp.mcp.tool.ToolCatalog;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.callgraph.CallgraphBundleTestSupport;
import dev.mcdevmcp.storage.callgraph.CallgraphDataRecord;
import dev.mcdevmcp.storage.callgraph.CallgraphEdgeKind;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.symbols.SymbolIndexData;
import dev.mcdevmcp.storage.symbols.SymbolIndexFixture;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The readers and writers of a field declared in a base class, inherited by one subclass and its own
 * subclass, and hidden by another subclass with a field of the same name.
 */
class FieldReferencesToolTest {
    private static final MinecraftVersion VERSION = new MinecraftVersion("1.21.5");
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    @TempDir
    Path temporaryDirectory;
    private ToolCatalog catalog;

    private static CallgraphDataRecord access(long id, CallgraphEdgeKind kind, String callerClass, String owner) {
        return new CallgraphDataRecord(id, kind, callerClass, "run", "()V", owner, "count", "I", (int) id);
    }

    private static SymbolIndexData.FieldRow field(long id, long typeId) {
        return new SymbolIndexData.FieldRow(id, typeId, 0, "count", "int", "protected", 0, 0, 1, 1);
    }

    @BeforeEach
    void createIndexAndCallgraph() throws Exception {
        PlatformPaths paths = new PlatformPaths(temporaryDirectory);
        Path sourceRoot = paths.sourceRoot(VERSION);
        Files.createDirectories(sourceRoot);
        List<SymbolIndexData.TypeRow> types = List.of(SymbolIndexFixture.minecraftType(1, 1, "alpha.Base", "Base", "class", "java.lang.Object", List.of(), "alpha/Base.java", 1, 3), SymbolIndexFixture.minecraftType(2, 1, "alpha.Child", "Child", "class", "alpha.Base", List.of(), "alpha/Child.java", 1, 2), SymbolIndexFixture.minecraftType(3, 1, "alpha.Grandchild", "Grandchild", "class", "alpha.Child", List.of(), "alpha/Grandchild.java", 1, 2), SymbolIndexFixture.minecraftType(4, 1, "alpha.Hider", "Hider", "class", "alpha.Base", List.of(), "alpha/Hider.java", 1, 3));
        SymbolIndexFixture.write(paths.symbolIndex(VERSION), VERSION, sourceRoot, "0".repeat(64), Instant.parse("2026-07-16T00:00:00Z"), List.of(SymbolIndexFixture.minecraftPackage(1, "alpha")), types, List.of(field(1, 1), field(2, 4)), List.of(), List.of());
        CallgraphBundleTestSupport.publish(paths.callgraphBundle(VERSION), VERSION, List.of(access(1, CallgraphEdgeKind.READ, "caller.One", "alpha.Base"), access(2, CallgraphEdgeKind.READ, "caller.Two", "alpha.Child"), access(3, CallgraphEdgeKind.READ, "caller.Three", "alpha.Hider"), access(4, CallgraphEdgeKind.WRITE, "caller.Four", "alpha.Grandchild"), access(5, CallgraphEdgeKind.READ, "caller.Five", "alpha.Grandchild")));
        catalog = ToolCatalog.load(new AppEnvironment(Map.of()), CompleteToolBindings.including(McpJsonDefaults.getMapper(), StaticToolModule.handlers(paths)), McpJsonDefaults.getMapper(), executor);
        text(Map.of("action", "set", "version", VERSION.value()), "mc_version");
    }

    @AfterEach
    void closeExecutor() {
        executor.close();
    }

    private String text(Map<String, Object> arguments, String tool) {
        ToolResult<?> result = catalog.dispatch(tool, arguments, Cancellation.none()).toCompletableFuture().join();
        return assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text();
    }

    private String refs(String className, String direction) {
        return text(Map.of("className", className, "methodName", "count", "direction", direction), "mc_find_refs");
    }

    @Test
    void findsTheAccessesThroughEverySubtypeThatInheritsTheField() {
        assertEquals("""
                     Found 3 readers:
                     caller.One.run()V (line 1)
                     caller.Two.run()V (line 2)
                     caller.Five.run()V (line 5)
                     Total: 3 readers
                     Includes accesses recorded under 2 subtypes that inherit the field.""", refs("alpha.Base", "readers"));
        assertEquals("""
                     Found 1 writers:
                     caller.Four.run()V (line 4)
                     Total: 1 writers
                     Includes accesses recorded under 1 subtype that inherits the field.""", refs("alpha.Base", "writers"));
    }

    @Test
    void namesTheDeclaringClassForASubtypeThatInheritsTheField() {
        assertEquals("""
                     Found 2 readers:
                     caller.Two.run()V (line 2)
                     caller.Five.run()V (line 5)
                     Total: 2 readers
                     alpha.Child declares no field named count; it inherits alpha.Base#count. Query alpha.Base to see the accesses through every type.
                     Includes accesses recorded under 1 subtype that inherits the field.""", refs("alpha.Child", "readers"));
    }

    @Test
    void keepsAHiddenFieldApartFromTheOneItHides() {
        assertEquals("Found 1 readers:\ncaller.Three.run()V (line 3)\nTotal: 1 readers", refs("alpha.Hider", "readers"));
        assertEquals("No writers found for alpha.Hider#count", refs("alpha.Hider", "writers"));
        assertEquals("No callers found for alpha.Base#count\nalpha.Base declares no method named count.", refs("alpha.Base", "callers"));
    }
}

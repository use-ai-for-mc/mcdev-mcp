package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.CompleteToolBindings;
import dev.mcdevmcp.mcp.tool.ToolCatalog;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.callgraph.CallgraphBundleTestSupport;
import dev.mcdevmcp.storage.callgraph.CallgraphDataRecord;
import dev.mcdevmcp.storage.h2.SymbolSchema;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.AppVersion;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class McFindRefsContractTest {
    private static String contentText(ToolResult<?> result) {
        return assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text();
    }

    private static final MinecraftVersion PRIMARY = new MinecraftVersion("1.21.5");
    private static final MinecraftVersion SECONDARY = new MinecraftVersion("1.21.6");
    private static final MinecraftVersion NO_GRAPH = new MinecraftVersion("1.21.nocg");
    private static final MinecraftVersion BAD_GRAPH = new MinecraftVersion("1.21.bad");
    private final List<ExecutorService> executors = new ArrayList<>();
    @TempDir
    Path temporaryDirectory;

    private static Map<String, Object> withLimit(Map<String, Object> base, Number limit) {
        var copy = new java.util.HashMap<>(base);
        copy.put("limit", limit);
        return Map.copyOf(copy);
    }

    private static String text(ToolCatalog catalog, String tool, Map<String, Object> arguments) {
        return contentText(result(catalog, arguments, tool));
    }

    private static ToolResult<?> result(ToolCatalog catalog, Map<String, Object> arguments) {
        return result(catalog, arguments, "mc_find_refs");
    }

    private static ToolResult<?> result(ToolCatalog catalog, Map<String, Object> arguments, String tool) {
        return catalog.dispatch(tool, arguments, Cancellation.none()).toCompletableFuture().join();
    }

    private static void createSymbolDatabase(PlatformPaths paths, MinecraftVersion version) throws Exception {
        Files.createDirectories(paths.sourceRoot(version));
        Files.createDirectories(paths.symbolDatabase(version).getParent());
        try (var connection = DriverManager.getConnection(writerUrl(paths.symbolDatabase(version)))) {
            SymbolSchema.create(connection, version, paths.sourceRoot(version), "0".repeat(64), Instant.parse("2026-07-16T00:00:00Z"));
            SymbolSchema.createIndexes(connection);
        }
    }

    private static void createPrimaryCallgraph(Path bundle) throws Exception {
        List<CallgraphDataRecord> records = new ArrayList<>();
        records.add(edge(1, "caller.Alpha", "entry", null, "target.Target", "hit", "()V", null));
        records.add(edge(2, "caller.Alpha", "entry", "", "target.Target", "hit", "(I)V", 0));
        records.add(edge(3, "caller.Alpha", "entry", "()V", "target.Target", "hit", "()V", -7));
        records.add(edge(4, "caller.Alpha", "entry", "()V", "target.Target", "hit", "(I)V", 11));
        records.add(edge(5, "caller.Alpha", "entry", "()V", "target.Target", "hit", "(I)V", 11));
        records.add(edge(6, "origin.Origin", "dispatch", "()V", "callee.Empty", "none", "", 0));
        records.add(edge(7, "origin.Origin", "dispatch", "(I)V", "callee.Overload", "work", null, null));
        records.add(edge(8, "origin.Origin", "dispatch", "()V", "callee.Overload", "work", "()V", 7));
        for (int index = 0; index <= 5000; index++) {
            records.add(edge(9L + index, "many.Caller%04d".formatted(index), "call", "()V", "many.Target", "hit", "()V", index + 1));
        }
        CallgraphBundleTestSupport.publish(bundle, PRIMARY, records);
    }

    private static void createSecondaryCallgraph(Path bundle) throws Exception {
        CallgraphBundleTestSupport.publish(bundle, SECONDARY, List.of(edge(1, "secondary.Caller", "run", "()V", "secondary.Target", "hit", "()V", 42)));
    }

    private static CallgraphDataRecord edge(long id, String callerClass, String callerMethod, String callerDescriptor, String calleeClass, String calleeMethod, String calleeDescriptor, Integer line) {
        return new CallgraphDataRecord(id, callerClass, callerMethod, callerDescriptor, calleeClass, calleeMethod, calleeDescriptor, line);
    }

    private static String writerUrl(Path database) {
        String path = database.toAbsolutePath().normalize().toString();
        return "jdbc:h2:file:" + path.substring(0, path.length() - ".mv.db".length()) + ";DB_CLOSE_ON_EXIT=FALSE";
    }

    @AfterEach
    void closeExecutors() {
        executors.forEach(ExecutorService::close);
    }

    @Test
    void usesTheFrozenLimitContract() {
        assertEquals(new NormalizedLimit(100, false, true), McFindRefsTool.LIMIT.normalize(null));
        assertEquals(new NormalizedLimit(5000, false, false), McFindRefsTool.LIMIT.normalize(5000));
        assertEquals(new NormalizedLimit(5000, true, false), McFindRefsTool.LIMIT.normalize(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> McFindRefsTool.LIMIT.normalize(0));
        assertThrows(IllegalArgumentException.class, () -> McFindRefsTool.LIMIT.normalize(-1));
    }

    @Test
    void rendersDescriptorsDuplicatesAndLegacyLinesInDeterministicOrder() throws Exception {
        ToolCatalog catalog = catalog(fixture());
        text(catalog, "mc_version", Map.of("action", "set", "version", PRIMARY.value()));

        assertEquals("""
                     Found 5 callers:
                     caller.Alpha.entry
                     caller.Alpha.entry
                     caller.Alpha.entry()V (line -7)
                     caller.Alpha.entry()V (line 11)
                     caller.Alpha.entry()V (line 11)
                     Total: 5 callers""", text(catalog, "mc_find_refs", Map.of("className", "target.Target", "methodName", "hit", "direction", "callers")));
        assertEquals("""
                     Found 3 callees:
                     callee.Empty.none
                     callee.Overload.work
                     callee.Overload.work()V (line 7)
                     Total: 3 callees""", text(catalog, "mc_find_refs", Map.of("className", "origin.Origin", "methodName", "dispatch", "direction", "callees")));
        assertEquals("Error executing mc_find_refs: 'direction' is required", text(catalog, "mc_find_refs", Map.of("className", "origin.Origin", "methodName", "dispatch")));
    }

    @Test
    void honorsDefaultIntegerExactAndCappedLimits() throws Exception {
        ToolCatalog catalog = catalog(fixture());
        text(catalog, "mc_version", Map.of("action", "set", "version", PRIMARY.value()));
        Map<String, Object> base = Map.of("className", "many.Target", "methodName", "hit", "direction", "callers");

        String defaulted = text(catalog, "mc_find_refs", base);
        assertTrue(defaulted.startsWith("Found 101 callers:\nmany.Caller0000.call()V (line 1)\n"));
        assertTrue(defaulted.endsWith("... and 1+ more callers (showing first 100; pass a larger `limit` to see more)"));

        assertEquals("Error executing mc_find_refs: 'limit' must be an integer", text(catalog, "mc_find_refs", withLimit(base, 3.9d)));
        assertEquals("Error executing mc_find_refs: 'limit' must not be below 1", text(catalog, "mc_find_refs", withLimit(base, 0)));
        assertEquals("Error executing mc_find_refs: 'limit' must not be below 1", text(catalog, "mc_find_refs", withLimit(base, -1)));

        String exact = text(catalog, "mc_find_refs", withLimit(base, 5000));
        assertTrue(exact.startsWith("Found 5001 callers:\n"));
        assertTrue(exact.contains("many.Caller4999.call()V (line 5000)"));
        assertFalse(exact.contains("many.Caller5000.call()V (line 5001)"));
        assertTrue(exact.endsWith("... and 1+ more callers (showing first 5000; pass a larger `limit` to see more)"));

        String capped = text(catalog, "mc_find_refs", withLimit(base, 5001));
        assertTrue(capped.endsWith("... and 1+ more callers (showing first 5000; pass a larger `limit` to see more)" + " (limit was capped to 5000 by the server)"));
        String maxValue = text(catalog, "mc_find_refs", withLimit(base, Integer.MAX_VALUE));
        assertTrue(maxValue.endsWith("... and 1+ more callers (showing first 5000; pass a larger `limit` to see more)" + " (limit was capped to 5000 by the server)"));
    }

    @Test
    void preservesVersionPrecedenceMissingGraphEmptyAndMalformedArgumentBehavior() throws Exception {
        ToolCatalog catalog = catalog(fixture());

        assertEquals("""
                     No Minecraft version is currently set.
                     
                     STOP and ask the USER which version they want to use, then call mc_version with action="set".
                     Or, provide a 'version' parameter in your tool call.
                     
                     To see available versions, call mc_version with action="list".""", text(catalog, "mc_find_refs", Map.of("className", "target.Target", "methodName", "hit", "direction", "callers")));
        text(catalog, "mc_version", Map.of("action", "set", "version", PRIMARY.value()));
        assertEquals("Found 1 callers:\nsecondary.Caller.run()V (line 42)\nTotal: 1 callers", text(catalog, "mc_find_refs", Map.of("className", "secondary.Target", "methodName", "hit", "direction", "callers", "version", SECONDARY.value())));
        assertTrue(text(catalog, "mc_find_refs", Map.of("className", "target.Target", "methodName", "hit", "direction", "callers")).startsWith("Found 5 callers:"));
        assertEquals("No callers found for target.Target#absent", text(catalog, "mc_find_refs", Map.of("className", "target.Target", "methodName", "absent", "direction", "callers")));
        assertEquals("""
                     Version 1.21.nocg does not have callgraph data.
                     
                     STOP and ask the USER to run this command in their terminal:
                       java -jar %s callgraph -v 1.21.nocg
                     
                     Or for full reinitialization:
                       java -jar %s init -v 1.21.nocg""".formatted(AppVersion.executableJarName(), AppVersion.executableJarName()), text(catalog, "mc_find_refs", Map.of("className", "x.Y", "methodName", "z", "direction", "callers", "version", NO_GRAPH.value())));

        ToolResult<?> missingClass = result(catalog, Map.of("methodName", "hit", "direction", "callers"));
        assertTrue(missingClass.isError());
        assertEquals("Error executing mc_find_refs: 'className' is required", contentText(missingClass));
        ToolResult<?> missingAll = result(catalog, Map.of());
        assertTrue(missingAll.isError());
        assertEquals("Error executing mc_find_refs: 'className' is required", contentText(missingAll));
        assertEquals("Error executing mc_find_refs: 'className' must be a string", text(catalog, "mc_find_refs", Map.of("className", 42, "methodName", "hit", "direction", "callers")));

        assertEquals("Active version set to 1.21.bad.\nIndexed: yes\nCallgraph: corrupt\n\nSTOP and ask the USER to run this command in their terminal:\n  java -jar " + AppVersion.executableJarName() + " callgraph -v 1.21.bad\n\nOr for full reinitialization:\n  java -jar " + AppVersion.executableJarName() + " init -v 1.21.bad", text(catalog, "mc_version", Map.of("action", "set", "version", BAD_GRAPH.value())));

        ToolResult<?> corrupt = result(catalog, Map.of("className", "x.Y", "methodName", "z", "direction", "callers", "version", BAD_GRAPH.value()));
        assertFalse(corrupt.isError());
        assertEquals("Version 1.21.bad has corrupt callgraph data.\n\nSTOP and ask the USER to run this command in their terminal:\n  java -jar " + AppVersion.executableJarName() + " callgraph -v 1.21.bad\n\nOr for full reinitialization:\n  java -jar " + AppVersion.executableJarName() + " init -v 1.21.bad", contentText(corrupt));
    }

    @Test
    void registersTheEighthHandlerForBlockingExecution() throws Exception {
        PlatformPaths paths = fixture();
        assertEquals(8, StaticToolModule.handlers(paths).size());
        assertTrue(StaticToolModule.handlers(paths).containsKey("mc_find_refs"));
        ToolCatalog catalog = catalog(paths);
        ToolResult<?> result = result(catalog, Map.of("className", "target.Target", "methodName", "hit", "direction", "callers", "version", PRIMARY.value()));
        assertFalse(result.isError());
        assertTrue(contentText(result).startsWith("Found 5 callers:"));
    }

    private ToolCatalog catalog(PlatformPaths paths) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        executors.add(executor);
        return ToolCatalog.load(new AppEnvironment(Map.of()), CompleteToolBindings.including(McpJsonDefaults.getMapper(), StaticToolModule.handlers(paths)), McpJsonDefaults.getMapper(), executor);
    }

    private PlatformPaths fixture() throws Exception {
        PlatformPaths paths = new PlatformPaths(temporaryDirectory);
        for (MinecraftVersion version : List.of(PRIMARY, SECONDARY, NO_GRAPH, BAD_GRAPH)) {
            createSymbolDatabase(paths, version);
        }
        createPrimaryCallgraph(paths.callgraphBundle(PRIMARY));
        createSecondaryCallgraph(paths.callgraphBundle(SECONDARY));
        Files.createDirectories(paths.callgraphBundle(BAD_GRAPH));
        Files.write(paths.callgraphBundle(BAD_GRAPH).resolve("current.json"), new byte[]{0, 1, 2, 3, 4});
        return paths;
    }
}

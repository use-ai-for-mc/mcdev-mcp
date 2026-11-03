package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.CompleteToolBindings;
import dev.mcdevmcp.mcp.tool.CountingMcpJsonMapper;
import dev.mcdevmcp.mcp.tool.ToolCatalog;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.symbols.SymbolIndexData;
import dev.mcdevmcp.storage.symbols.SymbolIndexFixture;
import dev.mcdevmcp.storage.migration.DirectoryAliasFixture;
import dev.mcdevmcp.storage.migration.VersionOperationLease;
import dev.mcdevmcp.storage.model.ClassSymbol;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.SourceNamespace;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.AppVersion;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.lang.model.element.ElementKind;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;


class StaticToolContractTest {
    private static final MinecraftVersion VERSION = new MinecraftVersion("1.21.5");
    private static final Map<String, String> PREVIEW_LAUNCH_GUIDANCE = Map.of("version_set_unknown", "Version 9.9.9 not initialized.\n\nSTOP and ask the USER to run this command in their terminal:\n  java -jar mcdev-mcp-3.0.0.jar init -v 9.9.9\n\nThis will download, decompile, and index Minecraft 9.9.9 sources.", "version_set_unindexed", "Version 1.21.4 not indexed.\n\nSTOP and ask the USER to run this command in their terminal:\n  java -jar mcdev-mcp-3.0.0.jar init -v 1.21.4\n\nThis will index Minecraft 1.21.4 sources.", "search_explicit_missing", "Version 9.9.9 not initialized. STOP and ask the USER to run this command in their terminal:\n  java -jar mcdev-mcp-3.0.0.jar init -v 9.9.9\n\nThis will download, decompile, and index Minecraft 9.9.9 sources (including callgraph).");
    private static final Set<String> CONSTRUCTOR_SIGNATURE_LABELS = Set.of("constructor_search", "constructor_class_summary", "constructor_class_methods", "constructor_get_method");
    private static final String NODE_CONSTRUCTOR_SIGNATURE = "undefined ConstructorOnly(";
    private static final Map<String, String> SIMILAR_CLASS_HINTS = Map.of("class_missing", "Classes with that simple name: alpha.Alpha");
    private static final Set<String> SCHEMA_INVALID_LABELS = Set.of("version_missing_action", "version_unknown_action", "search_missing_query", "search_malformed_query_number", "search_invalid_enum_wire", "search_fractional_limit", "search_subunit_fractional_limit", "search_max_plus_point_one_limit", "search_nonpositive_limit", "search_nonfinite_string_wire", "class_missing_class_name", "class_wrong_class_name_type", "class_invalid_enum_wire", "method_missing_class_name", "method_missing_method_name", "packages_invalid_enum_wire", "packages_wrong_namespace_type", "packages_fractional_truncation", "packages_subunit_fractional_limit", "packages_max_plus_point_one_limit", "classes_missing_package_path", "classes_subunit_fractional_limit", "classes_max_plus_point_one_limit", "hierarchy_missing_class_name", "hierarchy_missing_direction", "hierarchy_root_missing_direction", "hierarchy_root_sideways_direction", "hierarchy_subunit_fractional_limit", "hierarchy_max_plus_point_one_limit", "hierarchy_invalid_enum_wire", "classes_wrong_package_path_type");
    private final List<ExecutorService> catalogExecutors = new ArrayList<>();
    @TempDir
    Path temporaryDirectory;

    private static String contentText(ToolResult<?> result) {
        return assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text();
    }

    private static ClassSymbol symbol(Path sourcePath) {
        return new ClassSymbol(1, SourceNamespace.MINECRAFT, Optional.empty(), "alpha.Alpha", "alpha", "Alpha", ElementKind.CLASS, Optional.empty(), List.of(), sourcePath, 0, 0, 1, 1);
    }

    private static List<Map<String, Object>> jsonLines(String resource) throws Exception {
        try (var input = StaticToolContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            String contents = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
            List<Map<String, Object>> documents = new ArrayList<>();
            int start = -1;
            int depth = 0;
            boolean quoted = false;
            boolean escaped = false;
            for (int index = 0; index < contents.length(); index++) {
                char character = contents.charAt(index);
                if (start < 0) {
                    if (Character.isWhitespace(character)) {
                        continue;
                    }
                    if (character != '{') {
                        throw new IOException("Expected a JSON object at offset " + index + " in " + resource);
                    }
                    start = index;
                }
                if (quoted) {
                    if (escaped) {
                        escaped = false;
                    }
                    else if (character == '\\') {
                        escaped = true;
                    }
                    else if (character == '"') {
                        quoted = false;
                    }
                    continue;
                }
                if (character == '"') {
                    quoted = true;
                }
                else if (character == '{' || character == '[') {
                    depth++;
                }
                else if (character == '}' || character == ']') {
                    depth--;
                    if (depth == 0) {
                        documents.add(McpJsonDefaults.getMapper().readValue(contents.substring(start, index + 1), new TypeRef<>() {
                        }));
                        start = -1;
                    }
                    else if (depth < 0) {
                        throw new IOException("Unbalanced JSON at offset " + index + " in " + resource);
                    }
                }
            }
            if (start >= 0) {
                throw new IOException("Incomplete JSON document in " + resource);
            }
            return List.copyOf(documents);
        }
    }

    private static String text(ToolCatalog catalog, String name, Map<String, Object> arguments) {
        ToolResult<?> result = catalog.dispatch(name, arguments, Cancellation.none()).toCompletableFuture().join();
        return contentText(result);
    }

    private static void createPrimaryDatabase(PlatformPaths paths, Path sourceRoot) throws Exception {
        List<SymbolIndexData.TypeRow> types = new ArrayList<>();
        types.add(type(1, 1, "minecraft", null, "alpha.Alpha", "Alpha", "class", null, List.of(), "alpha/Alpha.java", 2, 5));
        types.add(type(2, 1, "minecraft", null, "alpha.ConstructorOnly", "ConstructorOnly", "class", null, List.of(), "alpha/ConstructorOnly.java", 2, 3));
        types.add(type(3, 2, "minecraft", null, "beta.Beta", "Beta", "class", "alpha.Alpha", List.of(), "beta/Beta.java", 2, 2));
        types.add(type(4, 3, "minecraft", null, "gamma.Gamma", "Gamma", "class", null, List.of(), "gamma/Gamma.java", 2, 2));
        long id = 5;
        for (int index = 0; index <= 5000; index++, id++) {
            types.add(type(id, 4, "minecraft", null, "bulk.Bulk%04d".formatted(index), "Bulk%04d".formatted(index), "class", null, List.of(), "alpha/Alpha.java", 1, 1));
        }
        types.add(type(id++, 5, "minecraft", null, "hierarchy.RootInterface", "RootInterface", "interface", null, List.of(), "alpha/Alpha.java", 1, 1));
        for (int index = 0; index <= 250; index++, id++) {
            types.add(type(id, 5, "minecraft", null, "hierarchy.Child%03d".formatted(index), "Child%03d".formatted(index), "class", "alpha.Alpha", List.of(), "alpha/Alpha.java", 1, 1));
        }
        for (int index = 0; index <= 250; index++, id++) {
            types.add(type(id, 5, "minecraft", null, "hierarchy.Impl%03d".formatted(index), "Impl%03d".formatted(index), "class", null, List.of("hierarchy.RootInterface"), "alpha/Alpha.java", 1, 1));
        }
        types.add(type(id, 6, "fabric", "0.102.0+1.21.5", "net.fabricmc.fabric.api.FabricThing", "FabricThing", "class", null, List.of(), "FabricThing.java", 1, 1));
        List<SymbolIndexData.PackageRow> packages = List.of(SymbolIndexFixture.minecraftPackage(1, "alpha"), SymbolIndexFixture.minecraftPackage(2, "beta"), SymbolIndexFixture.minecraftPackage(3, "gamma"), SymbolIndexFixture.minecraftPackage(4, "bulk"), SymbolIndexFixture.minecraftPackage(5, "hierarchy"), new SymbolIndexData.PackageRow(6, "fabric", "0.102.0+1.21.5", "net.fabricmc.fabric.api"));
        List<SymbolIndexData.MethodRow> methods = List.of(new SymbolIndexData.MethodRow(1, 1, 0, "needle", "(Ljava/lang/String;)V", "void", "public", false, 51, 85, 4, 4), new SymbolIndexData.MethodRow(2, 1, 1, "Needle", "()V", "void", "public", false, 90, 112, 5, 5), new SymbolIndexData.MethodRow(3, 2, 0, "ConstructorOnly", "()V", null, "public", true, 44, 70, 3, 3));
        SymbolIndexFixture.write(paths.symbolIndex(VERSION), VERSION, sourceRoot, "0".repeat(64), Instant.parse("2026-07-16T00:00:00Z"), packages, types, List.of(new SymbolIndexData.FieldRow(1, 1, 0, "Needle", "int", "private", 27, 46, 3, 3)), methods, List.of(new SymbolIndexData.ParameterRow(1, 1, 0, "arg", "String", false, 70, 80, 4, 4)));
    }

    private static void createOtherDatabase(PlatformPaths paths) throws Exception {
        MinecraftVersion otherVersion = new MinecraftVersion("1.21.6");
        SymbolIndexFixture.write(paths.symbolIndex(otherVersion), otherVersion, paths.sourceRoot(otherVersion), "0".repeat(64), Instant.parse("2026-07-16T00:00:00Z"), List.of(SymbolIndexFixture.minecraftPackage(1, "other")), List.of(new SymbolIndexData.TypeRow(1, 1, "minecraft", null, "other.Other", "Other", "class", null, List.of(), "other/Other.java", 0, 37, 2, 2)), List.of(), List.of(), List.of());
    }

    private static SymbolIndexData.TypeRow type(long id, long packageId, String namespace, String fabricVersion, String binaryName, String simpleName, String kind, String superclass, List<String> interfaces, String sourcePath, int startLine, int endLine) {
        return new SymbolIndexData.TypeRow(id, packageId, namespace, fabricVersion, binaryName, simpleName, kind, superclass, interfaces, sourcePath, 0, 0, startLine, endLine);
    }

    private static String previewLaunchCorpusText(String label, String frozenText) {
        String approved = PREVIEW_LAUNCH_GUIDANCE.get(label);
        if (approved == null) {
            return frozenText;
        }
        assertEquals(approved, frozenText, "Unreviewed frozen launcher-guidance change: " + label);
        return approved.replace("java -jar mcdev-mcp-3.0.0.jar", "java --enable-preview -jar mcdev-mcp-3.0.0.jar");
    }

    /**
     * Node printed the return type a constructor does not have as "undefined"; the reviewed responses
     * show the constructor as Java declares it.
     */
    private static String constructorCorpusText(String label, String frozenText) {
        if (!CONSTRUCTOR_SIGNATURE_LABELS.contains(label)) {
            return frozenText;
        }
        assertTrue(frozenText.contains(NODE_CONSTRUCTOR_SIGNATURE), "Unreviewed frozen constructor rendering: " + label);
        return frozenText.replace(NODE_CONSTRUCTOR_SIGNATURE, "ConstructorOnly(");
    }

    /**
     * Node stopped at "not found"; the reviewed responses go on to name the classes that share the simple name.
     */
    private static String similarClassCorpusText(String label, String frozenText) {
        String hint = SIMILAR_CLASS_HINTS.get(label);
        return hint == null ? frozenText : frozenText + "\n" + hint;
    }

    private static Map<String, Object> requestParams(Map<String, Object> request) {
        return McpJsonDefaults.getMapper().convertValue(McpJsonDefaults.getMapper().convertValue(request.get("request"), new TypeRef<Map<String, Object>>() {
        }).get("params"), new TypeRef<>() {
        });
    }

    private static Map<String, Object> requestArguments(Map<String, Object> params) {
        return McpJsonDefaults.getMapper().convertValue(params.get("arguments"), new TypeRef<>() {
        });
    }

    @AfterEach
    void closeCatalogExecutors() {
        catalogExecutors.forEach(ExecutorService::close);
    }

    @Test
    void preservesFrozenStaticToolTextsForSuccessEmptyLimitsAndVersions() throws Exception {
        ToolCatalog catalog = catalog(fixture());

        assertEquals("No Minecraft version is currently set.\n\nSTOP and ask the USER which version they want to use, then call mc_version with action=\"set\".\nOr, provide a 'version' parameter in your tool call.\n\nTo see available versions, call mc_version with action=\"list\".", text(catalog, "mc_search", Map.of("query", "needle")));
        assertEquals("Active version set to 1.21.5.\nIndexed: yes\nCallgraph: no", text(catalog, "mc_version", Map.of("action", "set", "version", "1.21.5")));
        assertEquals("Found 3 result(s):\n[field] alpha.Alpha#Needle: private int Needle\n[method] alpha.Alpha#needle: public void needle(String arg) (line 4)\n[method] alpha.Alpha#Needle: public void Needle() (line 5)\nTotal: 3 result(s)", text(catalog, "mc_search", Map.of("query", "needle")));
        assertTrue(text(catalog, "mc_search", Map.of("query", "alp", "type", "class")).contains("[class] alpha.Alpha (1 fields, 2 methods)"));
        assertEquals("Found 6 package(s):\nalpha\nbeta\n... and 4+ more package(s) (showing first 2; pass a larger `limit` to see more)", text(catalog, "mc_list_packages", Map.of("limit", 2)));
        assertEquals("Classes under \"ALPHA\":\nalpha.Alpha\n... and possibly more class(es) (showing first 1; pass a larger `limit` to see more)", text(catalog, "mc_list_classes", Map.of("packagePath", "ALPHA", "limit", 1L)));
        assertEquals("Subclasses of alpha.Alpha:\nbeta.Beta\n... and possibly more subclasses (showing first 1; pass a larger `limit` to see more)", text(catalog, "mc_find_hierarchy", Map.of("className", "alpha.Alpha", "direction", "subclasses", "limit", 1)));
        assertEquals("Method \"missing\" not found in class alpha.Alpha", text(catalog, "mc_get_method", Map.of("className", "alpha.Alpha", "methodName", "missing")));
        assertTrue(text(catalog, "mc_get_method", Map.of("className", "alpha.Alpha", "methodName", "NEEDLE")).startsWith("// Method: alpha.Alpha#needle\n"));
        assertTrue(text(catalog, "mc_get_class", Map.of("className", "alpha.Alpha", "view", "full")).endsWith("public void Needle() { }\n}\n"));
        assertEquals("Class not found: Alpha\nClasses with that simple name: alpha.Alpha", text(catalog, "mc_get_class", Map.of("className", "Alpha")));
        assertEquals("Class not found: alpha.Absent", text(catalog, "mc_get_class", Map.of("className", "alpha.Absent")));
        assertEquals("Method \"needle\" not found in class wrong.ALPHA\nClasses with that simple name: alpha.Alpha", text(catalog, "mc_get_method", Map.of("className", "wrong.ALPHA", "methodName", "needle")));
        String executableJar = AppVersion.executableJarName();
        assertEquals("Version 9.9.9 not initialized.\n\nSTOP and ask the USER to run this command in their terminal:\n  java --enable-preview -jar " + executableJar + " init -v 9.9.9\n\nThis will download, decompile, and index Minecraft 9.9.9 sources.", text(catalog, "mc_version", Map.of("action", "set", "version", "9.9.9")));
        assertEquals("Version 9.9.9 not initialized. STOP and ask the USER to run this command in their terminal:\n  java --enable-preview -jar " + executableJar + " init -v 9.9.9\n\nThis will download, decompile, and index Minecraft 9.9.9 sources (including callgraph).", text(catalog, "mc_search", Map.of("query", "needle", "version", "9.9.9")));
    }

    @Test
    void versionListIgnoresForeignIncompleteAndLinkedCacheDirectories() throws Exception {
        PlatformPaths paths = fixture();
        Path cache = paths.cacheRoot().resolve("cache");
        Files.createDirectories(cache.resolve("not-a-version/client"));
        Files.createDirectories(cache.resolve("fabric-api-0.102.0+1.21.5/client"));
        Files.createDirectories(paths.versionCache(new MinecraftVersion("1.21.7")));

        ToolCatalog catalog = catalog(paths);
        String expected = String.join("\n", "Available Minecraft versions:", "1.21.4: not decompiled, not indexed, no callgraph", "1.21.5: not decompiled, indexed, no callgraph", "1.21.6: not decompiled, indexed, no callgraph", "", "No active version set. Use mc_version with action=\"set\".");
        assertEquals(expected, text(catalog, "mc_version", Map.of("action", "list")));

        Path outside = temporaryDirectory.resolve("outside-version");
        Files.createDirectories(outside.resolve("client"));
        try {
            Files.createSymbolicLink(paths.versionCache(new MinecraftVersion("1.21.8")), outside);
            assertEquals(expected, text(catalog, "mc_version", Map.of("action", "list")));
        } catch (UnsupportedOperationException | IOException | SecurityException ignored) {
            // Windows developer-mode policy can disallow symlink creation in test environments.
        }
    }

    @Test
    void acceptsIntegerLimitsAndReportsCapping() throws Exception {
        ToolCatalog catalog = catalog(fixture());
        text(catalog, "mc_version", Map.of("action", "set", "version", "1.21.5"));

        assertEquals("Error executing mc_search: 'limit' must be an integer", text(catalog, "mc_search", Map.of("query", "needle", "limit", new BigDecimal("3.9"))));
        String capped = text(catalog, "mc_search", Map.of("query", "needle", "limit", Integer.MAX_VALUE));
        assertTrue(capped.endsWith("Total: 3 result(s)"));
        assertFalse(capped.contains("capped"));
        assertEquals("Error executing mc_search: 'limit' must not be below 1", text(catalog, "mc_search", Map.of("query", "absent", "limit", -1)));
        assertEquals("Error executing mc_search: 'limit' must not be below 1", text(catalog, "mc_search", Map.of("query", "absent", "limit", 0)));
    }

    @Test
    void exposesOnlyTheEightStaticToolsAndNormalizesLargeLimitsWithoutOverflow() throws Exception {
        assertEquals(Set.of("mc_version", "mc_search", "mc_get_class", "mc_get_method", "mc_list_classes", "mc_list_packages", "mc_find_hierarchy", "mc_find_refs"), StaticToolModule.handlers(fixture()).keySet());
        LimitSpec limits = new LimitSpec(50, 1000);
        assertEquals(new NormalizedLimit(50, false, true), limits.normalize(null));
        assertEquals(new NormalizedLimit(50, false, false), limits.normalize(50));
        assertEquals(new NormalizedLimit(1000, false, false), limits.normalize(1000));
        assertEquals(new NormalizedLimit(1000, true, false), limits.normalize(1001));
        assertEquals(new NormalizedLimit(1000, true, false), limits.normalize(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> limits.normalize(0));
        assertThrows(IllegalArgumentException.class, () -> limits.normalize(-1));
    }

    @Test
    void rejectsUnsafeIndexedSourcePathsBeforeReadingThem() throws Exception {
        PlatformPaths paths = fixture();
        StaticToolSupport support = new StaticToolSupport(paths);
        ClassSymbol valid = symbol(Path.of("alpha/Alpha.java"));
        assertTrue(support.fullSource(VERSION, valid).startsWith("package alpha;"));
        assertUnsafeSource(support, symbol(Path.of("..", "outside.java")));
        assertUnsafeSource(support, symbol(temporaryDirectory.resolve("outside.java").toAbsolutePath()));

        Path outside = temporaryDirectory.resolve("outside.java");
        Files.writeString(outside, "outside");
        Path link = paths.sourceRoot(VERSION).resolve("alpha/escape.java");
        try {
            Files.createSymbolicLink(link, outside);
            assertUnsafeSource(support, symbol(Path.of("alpha/escape.java")));
        } catch (UnsupportedOperationException | FileSystemException ignored) {
            // Windows developer-mode policy can disallow symlink creation in test environments.
        }
    }

    @Test
    void treatsMissingSourcesAsMissingSymbolsAndUnsafeIndexedPathsAsErrors() throws Exception {
        PlatformPaths paths = fixture();
        ToolCatalog catalog = catalog(paths);
        text(catalog, "mc_version", Map.of("action", "set", "version", "1.21.5"));
        Files.delete(paths.sourceRoot(VERSION).resolve("alpha/Alpha.java"));
        for (String view : List.of("summary", "fields", "methods", "full")) {
            assertEquals("Class not found: alpha.Alpha", text(catalog, "mc_get_class", Map.of("className", "alpha.Alpha", "view", view)));
        }
        assertEquals("Method \"needle\" not found in class alpha.Alpha", text(catalog, "mc_get_method", Map.of("className", "alpha.Alpha", "methodName", "needle")));

        Files.writeString(paths.sourceRoot(VERSION).resolve("alpha/Alpha.java"), "");
        assertEquals("Class not found: alpha.Alpha", text(catalog, "mc_get_class", Map.of("className", "alpha.Alpha")));
        assertEquals("Method \"needle\" not found in class alpha.Alpha", text(catalog, "mc_get_method", Map.of("className", "alpha.Alpha", "methodName", "needle")));

        Files.writeString(paths.sourceRoot(VERSION).resolve("alpha/Alpha.java"), "package alpha;\npublic class Alpha { }\n");
        SymbolIndexFixture.rewrite(paths.symbolIndex(VERSION), data -> new SymbolIndexData(data.header(), data.packages(), data.types().stream().map(row -> row.binaryName().equals("alpha.Alpha") ? new SymbolIndexData.TypeRow(row.id(), row.packageId(), row.namespace(), row.fabricApiVersion(), row.binaryName(), row.simpleName(), row.kind(), row.superclass(), row.interfaces(), "../escape.java", row.startOffset(), row.endOffset(), row.startLine(), row.endLine()) : row).toList(), data.fields(), data.methods(), data.parameters()));
        ToolResult<?> result = catalog.dispatch("mc_get_class", Map.of("className", "alpha.Alpha"), Cancellation.none()).toCompletableFuture().join();
        assertTrue(result.isError());
        assertEquals("Error executing mc_get_class: Unsafe indexed source path: " + Path.of("..", "escape.java"), contentText(result));
    }

    @Test
    void publishesActivatedVersionToConcurrentReaders() throws Exception {
        StaticToolSupport support = new StaticToolSupport(fixture());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            support.activate(VERSION);
            assertEquals(VERSION, executor.submit(() -> support.active().orElseThrow()).get());
        }
    }

    @Test
    void returnsUnexpectedStorageFailuresAsToolErrors() {
        StaticToolSupport support = new StaticToolSupport(new PlatformPaths(temporaryDirectory));

        ToolResult<?> ioFailure = support.execute("mc_get_class", () -> {
            throw new IOException("source denied");
        });

        assertTrue(ioFailure.isError());
        assertEquals("Error executing mc_get_class: source denied", contentText(ioFailure));
    }

    @Test
    void methodReadKeepsItsGenerationThroughSourceAndLaterSqlQueries() throws Exception {
        PlatformPaths paths = fixture();
        CountDownLatch queriedType = new CountDownLatch(1);
        CountDownLatch resumeSource = new CountDownLatch(1);
        CountDownLatch writerStarted = new CountDownLatch(1);
        StaticToolSupport support = new StaticToolSupport(paths, () -> {
            queriedType.countDown();
            try {
                assertTrue(resumeSource.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var read = McGetMethodTool.binding(support).withBlockingExecutor(executor).invoke(McpJsonDefaults.getMapper(), Map.of("className", "alpha.Alpha", "methodName", "needle", "version", VERSION.value()), Cancellation.none()).toCompletableFuture();
            assertTrue(queriedType.await(10, TimeUnit.SECONDS));
            var write = executor.submit(() -> {
                writerStarted.countDown();
                try (var lease = VersionOperationLease.write(paths, VERSION)) {
                    lease.require(paths, VERSION);
                    Files.writeString(paths.sourceRoot(VERSION).resolve("alpha/Alpha.java"), "class Replaced {}\n");
                }
                return true;
            });
            try {
                assertTrue(writerStarted.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> write.get(150, TimeUnit.MILLISECONDS));
            } finally {
                resumeSource.countDown();
            }
            assertEquals("// Method: alpha.Alpha#needle\n// Signature: void needle(String arg)\n// Modifiers: public\n// Lines: 4-4\n\npublic class Alpha {\n    private int Needle;\n    public void needle(String arg) { }\n    public void Needle() { }\n}\n", contentText(read.get(10, TimeUnit.SECONDS)));
            assertTrue(write.get(10, TimeUnit.SECONDS));
        } finally {
            resumeSource.countDown();
        }
    }

    private void assertUnsafeSource(StaticToolSupport support, ClassSymbol symbol) {
        StaticToolException exception = assertThrows(StaticToolException.class, () -> support.fullSource(VERSION, symbol));
        assertEquals("Unsafe indexed source path: " + symbol.sourcePath(), exception.getMessage());
    }

    @Test
        // Keep the alias-retargeting sequence visible in one regression.
    void methodSqlAndSourceStayPinnedWhenAncestorAliasChangesBetweenQueries() throws Exception {
        PlatformPaths first = fixture(temporaryDirectory.resolve("first/cache-root"));
        PlatformPaths second = fixture(temporaryDirectory.resolve("second/cache-root"));
        Path secondSource = second.sourceRoot(VERSION).resolve("alpha/Alpha.java");
        Files.writeString(secondSource, Files.readString(secondSource).replace("String arg", "String replacement"));
        SymbolIndexFixture.rewrite(second.symbolIndex(VERSION), data -> new SymbolIndexData(data.header(), data.packages(), data.types(), data.fields(), data.methods(), data.parameters().stream().map(row -> row.methodId() == 1 ? new SymbolIndexData.ParameterRow(row.id(), row.methodId(), row.ordinal(), "replacement", row.type(), row.varargs(), row.startOffset(), row.endOffset(), row.startLine(), row.endLine()) : row).toList()));
        Map<String, Object> arguments = Map.of("className", "alpha.Alpha", "methodName", "needle", "version", VERSION.value());
        String oldExpected = text(catalog(first), "mc_get_method", arguments);
        String newExpected = text(catalog(second), "mc_get_method", arguments);
        assertNotEquals(oldExpected, newExpected);
        Path alias = temporaryDirectory.resolve("selected-parent");
        DirectoryAliasFixture.create(alias, first.cacheRoot().getParent());
        var retargeted = new AtomicBoolean();
        StaticToolSupport support = new StaticToolSupport(new PlatformPaths(alias.resolve("cache-root")), () -> {
            if (retargeted.compareAndSet(false, true)) {
                try {
                    DirectoryAliasFixture.remove(alias);
                    DirectoryAliasFixture.create(alias, second.cacheRoot().getParent());
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var binding = McGetMethodTool.binding(support).withBlockingExecutor(executor);
            assertEquals(oldExpected, contentText(binding.invoke(McpJsonDefaults.getMapper(), arguments, Cancellation.none()).toCompletableFuture().get(15, TimeUnit.SECONDS)));
            assertEquals(newExpected, contentText(binding.invoke(McpJsonDefaults.getMapper(), arguments, Cancellation.none()).toCompletableFuture().get(15, TimeUnit.SECONDS)));
        } finally {
            DirectoryAliasFixture.remove(alias);
        }
        assertTrue(Files.readString(secondSource).contains("String replacement"));
        assertTrue(Files.readString(first.sourceRoot(VERSION).resolve("alpha/Alpha.java")).contains("String arg"));
    }

    @Test
    void everyStaticEntryFailsClosedForPendingMigrationIncludingActiveVersionAndList() throws Exception {
        PlatformPaths paths = fixture();
        ToolCatalog catalog = catalog(paths);
        text(catalog, "mc_version", Map.of("action", "set", "version", VERSION.value()));
        Path pending = paths.cacheRoot().resolve("migrations").resolve(VERSION.value()).resolve("pending.json");
        Files.createDirectories(pending.getParent());
        Map<String, Map<String, Object>> requests = Map.of("mc_get_method", Map.of("className", "alpha.Alpha", "methodName", "needle"), "mc_get_class", Map.of("className", "alpha.Alpha"), "mc_search", Map.of("query", "needle"), "mc_list_classes", Map.of("packagePath", "alpha"), "mc_list_packages", Map.of(), "mc_find_hierarchy", Map.of("className", "alpha.Alpha", "direction", "subclasses"), "mc_find_refs", Map.of("className", "alpha.Alpha", "methodName", "needle", "direction", "callers"));
        for (String phase : List.of("PREPARED", "BACKING_UP", "BACKED_UP", "INSTALLING_SOURCE", "INSTALLING_DATABASE", "INSTALLING_STAMP", "VALIDATING_PAIR", "COMMITTED", "corrupt")) {
            Files.writeString(pending, phase);
            for (var entry : requests.entrySet()) {
                for (boolean explicit : List.of(false, true)) {
                    Map<String, Object> arguments = new HashMap<>(entry.getValue());
                    if (explicit) {
                        arguments.put("version", VERSION.value());
                    }
                    ToolResult<?> result = catalog.dispatch(entry.getKey(), arguments, Cancellation.none()).toCompletableFuture().join();
                    assertTrue(result.isError(), entry.getKey() + " in " + phase);
                    assertTrue(contentText(result).contains("Source/index recovery required"), contentText(result));
                }
            }
            for (String action : List.of("list", "set")) {
                ToolResult<?> result = catalog.dispatch("mc_version", Map.of("action", action, "version", VERSION.value()), Cancellation.none()).toCompletableFuture().join();
                assertTrue(result.isError());
                assertTrue(contentText(result).contains("Source/index recovery required"), contentText(result));
            }
        }
    }

    /**
     * Frozen responses remain unchanged; only three exact launcher-guidance responses adapt to the preview launch policy, four show a constructor without Node's "undefined" return type, and one names the classes that share a simple name that was not found. The reviewed 31 schema-invalid requests remain explicit pre-mapper exclusions.
     */
    @Test
    void replaysSchemaValidFrozenNodeJsonlCorpusExactly() throws Exception {
        List<Map<String, Object>> requests = jsonLines("contracts/static-tools/requests.jsonl");
        List<Map<String, Object>> responses = jsonLines("contracts/static-tools/responses.jsonl");
        assertEquals(requests.size(), responses.size());
        ToolCatalog catalog = catalog(fixture());
        Map<String, ToolBinding<?>> bindings = StaticToolModule.handlers(new PlatformPaths(temporaryDirectory));
        Set<String> observedSchemaInvalidLabels = new HashSet<>();
        Set<String> corpusLabels = new HashSet<>();
        for (Map<String, Object> request : requests) {
            String label = (String) request.get("label");
            corpusLabels.add(label);
            Map<String, Object> params = requestParams(request);
            var mapper = new CountingMcpJsonMapper(McpJsonDefaults.getMapper());
            try {
                String toolName = (String) params.get("name");
                bindings.get(toolName).input().decode(mapper, requestArguments(params));
            } catch (IllegalArgumentException exception) {
                if (mapper.convertValueCalls() != 0) {
                    throw new AssertionError("Corpus request failed during mapping instead of schema validation: " + label, exception);
                }
                observedSchemaInvalidLabels.add(label);
                continue;
            }
            assertEquals(1, mapper.convertValueCalls(), "Schema-valid corpus request must map exactly once: " + label);
        }
        assertEquals(SCHEMA_INVALID_LABELS, observedSchemaInvalidLabels, "The reviewed schema-invalid exclusion set must match the frozen corpus");
        assertTrue(corpusLabels.containsAll(SCHEMA_INVALID_LABELS), "Every schema-invalid exclusion must remain represented in the frozen corpus");
        assertTrue(corpusLabels.containsAll(PREVIEW_LAUNCH_GUIDANCE.keySet()), "Every reviewed preview-guidance case must remain represented in the frozen corpus");
        assertTrue(corpusLabels.containsAll(CONSTRUCTOR_SIGNATURE_LABELS), "Every reviewed constructor case must remain represented in the frozen corpus");
        assertTrue(corpusLabels.containsAll(SIMILAR_CLASS_HINTS.keySet()), "Every reviewed similar-class case must remain represented in the frozen corpus");
        for (int index = 0; index < requests.size(); index++) {
            Map<String, Object> request = requests.get(index);
            String label = (String) request.get("label");
            if (SCHEMA_INVALID_LABELS.contains(label)) {
                continue;
            }
            Map<String, Object> expected = responses.get(index);
            Map<String, Object> params = requestParams(request);
            Map<String, Object> response = McpJsonDefaults.getMapper().convertValue(expected.get("response"), new TypeRef<>() {
            });
            Map<String, Object> result = McpJsonDefaults.getMapper().convertValue(response.get("result"), new TypeRef<>() {
            });
            String toolName = (String) params.get("name");
            ToolResult<?> actual = catalog.dispatch(toolName, McpJsonDefaults.getMapper().convertValue(params.get("arguments"), new TypeRef<>() {
            }), Cancellation.none()).toCompletableFuture().join();
            List<Map<String, Object>> content = McpJsonDefaults.getMapper().convertValue(result.get("content"), new TypeRef<>() {
            });
            String expectedText = similarClassCorpusText(label, constructorCorpusText(label, previewLaunchCorpusText(label, assertInstanceOf(String.class, content.getFirst().get("text")))));
            assertEquals(expectedText, contentText(actual), "corpus line " + index + " " + request.get("label"));
            assertEquals(Boolean.TRUE.equals(result.get("isError")), actual.isError(), "corpus line " + index + " " + request.get("label"));
        }
    }

    @Test
    void previewGuidanceAdaptationRejectsChangedTextAndDoesNotNormalizeOtherCases() {
        String frozen = PREVIEW_LAUNCH_GUIDANCE.get("version_set_unknown");
        assertTrue(previewLaunchCorpusText("version_set_unknown", frozen).contains("java --enable-preview -jar"));
        assertEquals(frozen, previewLaunchCorpusText("unreviewed_label", frozen));
        assertThrows(AssertionError.class, () -> previewLaunchCorpusText("version_set_unknown", frozen + " changed"));
    }

    private ToolCatalog catalog(PlatformPaths paths) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        catalogExecutors.add(executor);
        return ToolCatalog.load(new AppEnvironment(Map.of()), CompleteToolBindings.including(McpJsonDefaults.getMapper(), StaticToolModule.handlers(paths)), McpJsonDefaults.getMapper(), executor);
    }

    private PlatformPaths fixture() throws Exception {
        return fixture(temporaryDirectory);
    }

    private PlatformPaths fixture(Path root) throws Exception {
        PlatformPaths paths = new PlatformPaths(root);
        Path sourceRoot = paths.sourceRoot(VERSION);
        Path alpha = sourceRoot.resolve("alpha/Alpha.java");
        Path beta = sourceRoot.resolve("beta/Beta.java");
        Path gamma = sourceRoot.resolve("gamma/Gamma.java");
        Path constructorOnly = sourceRoot.resolve("alpha/ConstructorOnly.java");
        Files.createDirectories(alpha.getParent());
        Files.createDirectories(beta.getParent());
        Files.createDirectories(gamma.getParent());
        Files.writeString(alpha, "package alpha;\npublic class Alpha {\n    private int Needle;\n    public void needle(String arg) { }\n    public void Needle() { }\n}\n");
        Files.writeString(constructorOnly, "package alpha;\npublic class ConstructorOnly {\n    public ConstructorOnly() { }\n}\n");
        Files.writeString(beta, "package beta;\npublic class Beta extends alpha.Alpha { }\n");
        Files.writeString(gamma, "package gamma;\npublic class Gamma { }\n");
        Files.createDirectories(paths.sourceRoot(new MinecraftVersion("1.21.4")));
        Files.createDirectories(paths.sourceRoot(new MinecraftVersion("1.21.6")).resolve("other"));
        Files.writeString(paths.sourceRoot(new MinecraftVersion("1.21.6")).resolve("other/Other.java"), "package other;\npublic class Other { }\n");
        createPrimaryDatabase(paths, sourceRoot);
        createOtherDatabase(paths);
        return paths;
    }
}
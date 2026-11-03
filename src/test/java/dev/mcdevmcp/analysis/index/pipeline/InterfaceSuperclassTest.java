package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.classfile.ClassFileTypeCatalog;
import dev.mcdevmcp.analysis.index.SourceIndexer;
import dev.mcdevmcp.mcp.tool.CompleteToolBindings;
import dev.mcdevmcp.mcp.tool.ToolCatalog;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.symbols.SymbolIndexFiles;
import dev.mcdevmcp.storage.symbols.SymbolRepository;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.Cancellation;
import dev.mcdevmcp.tools.statictool.StaticToolModule;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.lang.model.element.ElementKind;
import java.lang.constant.ClassDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;


class InterfaceSuperclassTest {
    private static final MinecraftVersion VERSION = new MinecraftVersion("1.21.5");
    private static final Map<String, String> SOURCES = Map.of("hierarchy/Fixture.java", """
                                                                                        package hierarchy;
                                                                                        class Plain { void method() {} }
                                                                                        class Child extends Plain {}
                                                                                        enum Choice { FIRST }
                                                                                        record Value(int number) {}
                                                                                        interface Parent<T> {}
                                                                                        interface Extra {}
                                                                                        interface ChildInterface extends Parent<String>, Extra {
                                                                                            default int marker() { return 1; }
                                                                                        }
                                                                                        @interface Mark { String value(); }
                                                                                        class Implementation implements ChildInterface {}
                                                                                        """);
    private static final List<String> INTERFACES = List.of("Parent", "Extra", "ChildInterface", "Mark");
    @TempDir
    Path temporaryDirectory;

    private static String hierarchy(ToolCatalog catalog, String name, String direction, int limit) {
        return text(catalog, "mc_find_hierarchy", Map.of("className", name, "direction", direction, "limit", limit));
    }

    private static String text(ToolCatalog catalog, String tool, Map<String, Object> arguments) {
        var values = new HashMap<>(arguments);
        values.put("version", VERSION.value());
        var result = catalog.dispatch(tool, values, Cancellation.none()).toCompletableFuture().join();
        return assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text();
    }

    @Test
    void rawCatalogRetainsVmInterfaceSlotsAndObjectHasNoSuperclass() throws Exception {
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("compiled.jar"), SOURCES);
        var catalog = ClassFileTypeCatalog.read(jar);
        for (String name : INTERFACES) {
            var type = catalog.require("hierarchy." + name);
            assertTrue(type.accessFlags().contains(AccessFlag.INTERFACE), name);
            assertEquals(Optional.of(ClassDesc.of("java.lang.Object")), type.superclass(), name);
        }
        assertTrue(catalog.require("hierarchy.Mark").accessFlags().contains(AccessFlag.ANNOTATION));
        assertEquals(List.of(ClassDesc.of("hierarchy.Parent"), ClassDesc.of("hierarchy.Extra")), catalog.require("hierarchy.ChildInterface").interfaces());
        assertEquals(List.of(ClassDesc.of("java.lang.annotation.Annotation")), catalog.require("hierarchy.Mark").interfaces());

        Path objectJar = temporaryDirectory.resolve("object.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(objectJar));
             var input = Object.class.getResourceAsStream("Object.class")) {
            assertNotNull(input);
            output.putNextEntry(new JarEntry("java/lang/Object.class"));
            input.transferTo(output);
            output.closeEntry();
        }
        var object = ClassFileTypeCatalog.read(objectJar).require("java.lang.Object");
        assertEquals(Optional.empty(), object.superclass());
        assertFalse(object.accessFlags().contains(AccessFlag.INTERFACE));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void indexedHierarchyStoresAndHydratesLanguageSuperclasses(boolean catalogBacked) throws Exception {
        PlatformPaths paths = index(catalogBacked);
        var repository = new SymbolRepository(paths.symbolIndex(VERSION));
        for (String name : INTERFACES) {
            var type = repository.classByName("hierarchy." + name);
            assertNotNull(type);
            assertEquals(name.equals("Mark") ? ElementKind.ANNOTATION_TYPE : ElementKind.INTERFACE, type.kind());
            assertEquals(Optional.empty(), type.superclassBinaryName(), name);
            assertNull(SymbolIndexFiles.read(paths.symbolIndex(VERSION)).types().stream().filter(row -> row.binaryName().equals(type.binaryName())).findFirst().orElseThrow().superclass(), name);
        }
        assertEquals(List.of(), Objects.requireNonNull(repository.classByName("hierarchy.Parent")).interfaceBinaryNames());
        assertEquals(List.of(), Objects.requireNonNull(repository.classByName("hierarchy.Extra")).interfaceBinaryNames());
        assertEquals(List.of("hierarchy.Parent", "hierarchy.Extra"), Objects.requireNonNull(repository.classByName("hierarchy.ChildInterface")).interfaceBinaryNames());
        assertEquals(List.of("java.lang.annotation.Annotation"), Objects.requireNonNull(repository.classByName("hierarchy.Mark")).interfaceBinaryNames());
        assertEquals(List.of("hierarchy.ChildInterface"), Objects.requireNonNull(repository.classByName("hierarchy.Implementation")).interfaceBinaryNames());
        for (var control : Map.of("Plain", "java.lang.Object", "Child", "hierarchy.Plain", "Choice", "java.lang.Enum", "Value", "java.lang.Record", "Implementation", "java.lang.Object").entrySet()) {
            assertEquals(Optional.of(control.getValue()), Objects.requireNonNull(repository.classByName("hierarchy." + control.getKey())).superclassBinaryName(), control.getKey());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void actualToolsExcludeInterfacesFromObjectSubclassesAndSuperclassHeaders(boolean catalogBacked) throws Exception {
        PlatformPaths paths = index(catalogBacked);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var mapper = McpJsonDefaults.getMapper();
            ToolCatalog catalog = ToolCatalog.load(new AppEnvironment(Map.of()), CompleteToolBindings.including(mapper, StaticToolModule.handlers(paths)), mapper, executor);
            assertEquals("Subclasses of java.lang.Object:\nhierarchy.Plain\nhierarchy.Implementation\nTotal: 2 subclasses", hierarchy(catalog, "java.lang.Object", "subclasses", 20));
            assertEquals("Subclasses of hierarchy.Plain:\nhierarchy.Child\nTotal: 1 subclasses", hierarchy(catalog, "hierarchy.Plain", "subclasses", 20));
            assertEquals("Implementors of hierarchy.Parent:\nhierarchy.ChildInterface\nTotal: 1 implementors", hierarchy(catalog, "hierarchy.Parent", "implementors", 20));
            assertEquals("Implementors of hierarchy.Extra:\nhierarchy.ChildInterface\nTotal: 1 implementors", hierarchy(catalog, "hierarchy.Extra", "implementors", 20));
            assertEquals("Implementors of hierarchy.ChildInterface:\nhierarchy.Implementation\nTotal: 1 implementors", hierarchy(catalog, "hierarchy.ChildInterface", "implementors", 20));
            assertEquals("Implementors of java.lang.annotation.Annotation:\nhierarchy.Mark\nTotal: 1 implementors", hierarchy(catalog, "java.lang.annotation.Annotation", "implementors", 20));
            assertEquals("Subclasses of java.lang.Object:\nhierarchy.Plain\n... and possibly more subclasses (showing first 1; pass a larger `limit` to see more)", hierarchy(catalog, "java.lang.Object", "subclasses", 1));
            assertEquals("Implementors of hierarchy.Parent:\nhierarchy.ChildInterface\n... and possibly more implementors (showing first 1; pass a larger `limit` to see more)", hierarchy(catalog, "hierarchy.Parent", "implementors", 1));
            for (String name : INTERFACES) {
                String rendered = text(catalog, "mc_get_class", Map.of("className", "hierarchy." + name, "view", "summary"));
                assertTrue(rendered.startsWith("// " + (name.equals("Mark") ? "annotation" : "interface") + " hierarchy." + name + "\n"), rendered);
                assertFalse(rendered.contains("// Extends:"), rendered);
                if (name.equals("ChildInterface")) {
                    assertTrue(rendered.contains("// Implements: hierarchy.Parent, hierarchy.Extra\n"), rendered);
                }
                if (name.equals("Mark")) {
                    assertTrue(rendered.contains("// Implements: java.lang.annotation.Annotation\n"), rendered);
                }
            }
            for (var member : Map.of("ChildInterface", "marker", "Mark", "value").entrySet()) {
                String rendered = text(catalog, "mc_get_method", Map.of("className", "hierarchy." + member.getKey(), "methodName", member.getValue()));
                assertTrue(rendered.startsWith("// Method: hierarchy." + member.getKey() + "#" + member.getValue() + "\n"), rendered);
                assertFalse(rendered.contains("// Class extends:"), rendered);
            }
            assertTrue(text(catalog, "mc_get_class", Map.of("className", "hierarchy.Plain")).contains("// Extends: java.lang.Object\n"));
            assertTrue(text(catalog, "mc_get_method", Map.of("className", "hierarchy.Plain", "methodName", "method")).contains("// Class extends: java.lang.Object\n"));
        }
    }

    private PlatformPaths index(boolean catalogBacked) throws Exception {
        PlatformPaths paths = new PlatformPaths(temporaryDirectory.resolve("cache"));
        Path sourceRoot = paths.sourceRoot(VERSION);
        for (var source : SOURCES.entrySet()) {
            Path file = sourceRoot.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
        }
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("catalog.jar"), catalogBacked ? SOURCES : Map.of());
        new SourceIndexer().build(IndexerTestSupport.request(sourceRoot, jar, paths.symbolIndex(VERSION), 1));
        return paths;
    }
}
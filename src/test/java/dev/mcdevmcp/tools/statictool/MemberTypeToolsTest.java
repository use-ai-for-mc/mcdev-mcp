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

import java.nio.charset.StandardCharsets;
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
 * The static tools over an index that holds member types, overloads, constructors and a small type hierarchy.
 */
class MemberTypeToolsTest {
    private static final MinecraftVersion VERSION = new MinecraftVersion("1.21.5");
    private static final String SOURCE = """
                                         package alpha;
                                         public class Outer implements Shape {
                                             public Outer(int size) { }
                                             public void draw() { }
                                             public void draw(int count) { }
                                             static class Inner extends Outer {
                                                 Inner() { super(0); }
                                                 public void draw() { }
                                                 record Deep(int value) { }
                                             }
                                         }
                                         """;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    @TempDir
    Path temporaryDirectory;
    private ToolCatalog catalog;

    private static SymbolIndexData.MethodRow method(long id, long typeId, int ordinal, String name, String descriptor, String returnType, boolean constructor, int line) {
        return new SymbolIndexData.MethodRow(id, typeId, ordinal, name, descriptor, returnType, "public", constructor, 0, 0, line, line);
    }

    private static CallgraphDataRecord edge(long id, String callerClass, String calleeClass, String calleeMethod) {
        return new CallgraphDataRecord(id, CallgraphEdgeKind.CALL, callerClass, "run", "()V", calleeClass, calleeMethod, "()V", (int) id);
    }

    @BeforeEach
    void createIndexAndCallgraph() throws Exception {
        PlatformPaths paths = new PlatformPaths(temporaryDirectory);
        Path sourceRoot = paths.sourceRoot(VERSION);
        Files.createDirectories(sourceRoot.resolve("alpha"));
        Files.writeString(sourceRoot.resolve("alpha/Outer.java"), SOURCE, StandardCharsets.UTF_8);
        Files.writeString(sourceRoot.resolve("alpha/Shape.java"), "package alpha;\npublic interface Shape {\n    void draw();\n}\n", StandardCharsets.UTF_8);
        List<SymbolIndexData.TypeRow> types = List.of(SymbolIndexFixture.minecraftType(1, 1, "alpha.Outer", "Outer", "class", "java.lang.Object", List.of("alpha.Shape"), "alpha/Outer.java", 2, 11), SymbolIndexFixture.minecraftType(2, 1, "alpha.Outer$Inner", "Inner", "class", "alpha.Outer", List.of(), "alpha/Outer.java", 6, 10), SymbolIndexFixture.minecraftType(3, 1, "alpha.Outer$Inner$Deep", "Deep", "record", "java.lang.Record", List.of(), "alpha/Outer.java", 9, 9), SymbolIndexFixture.minecraftType(4, 1, "alpha.Shape", "Shape", "interface", null, List.of(), "alpha/Shape.java", 2, 4));
        List<SymbolIndexData.MethodRow> methods = List.of(method(1, 1, 0, "<init>", "(I)V", null, true, 3), method(2, 1, 1, "draw", "()V", "void", false, 4), method(3, 1, 2, "draw", "(I)V", "void", false, 5), method(4, 2, 0, "<init>", "()V", null, true, 7), method(5, 2, 1, "draw", "()V", "void", false, 8), method(6, 4, 0, "draw", "()V", "void", false, 3));
        List<SymbolIndexData.ParameterRow> parameters = List.of(new SymbolIndexData.ParameterRow(1, 1, 0, "size", "int", false, 0, 0, 3, 3), new SymbolIndexData.ParameterRow(2, 3, 0, "count", "int", false, 0, 0, 5, 5));
        SymbolIndexFixture.write(paths.symbolIndex(VERSION), VERSION, sourceRoot, "0".repeat(64), Instant.parse("2026-07-16T00:00:00Z"), List.of(SymbolIndexFixture.minecraftPackage(1, "alpha")), types, List.of(), methods, parameters);
        CallgraphBundleTestSupport.publish(paths.callgraphBundle(VERSION), VERSION, List.of(edge(1, "caller.One", "alpha.Shape", "draw"), edge(2, "caller.Two", "alpha.Shape", "draw"), edge(3, "caller.Three", "alpha.Outer", "draw"), edge(4, "caller.Four", "java.lang.Object", "hashCode")));
        catalog = ToolCatalog.load(new AppEnvironment(Map.of()), CompleteToolBindings.including(McpJsonDefaults.getMapper(), StaticToolModule.handlers(paths)), McpJsonDefaults.getMapper(), executor);
        text("mc_version", Map.of("action", "set", "version", VERSION.value()));
    }

    @AfterEach
    void closeExecutor() {
        executor.close();
    }

    private String text(String tool, Map<String, Object> arguments) {
        ToolResult<?> result = catalog.dispatch(tool, arguments, Cancellation.none()).toCompletableFuture().join();
        return assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text();
    }

    @Test
    void showsAMemberTypeByEitherNameWithOnlyItsOwnDeclaration() {
        String expected = """
                          // class alpha.Outer$Inner
                          // Declared in: alpha.Outer (lines 6-10)
                          // Extends: alpha.Outer
                          // Member types: alpha.Outer$Inner$Deep
                          // Fields: 0, Methods: 2

                              static class Inner extends Outer {
                                  Inner() { super(0); }
                                  public void draw() { }
                                  record Deep(int value) { }
                              }
                          """;

        assertEquals(expected, text("mc_get_class", Map.of("className", "alpha.Outer$Inner", "view", "full")));
        assertEquals(expected, text("mc_get_class", Map.of("className", "alpha.Outer.Inner", "view", "full")));
    }

    @Test
    void namesTheMemberTypesOfATypeAndPrintsConstructorsAsDeclared() {
        assertEquals("""
                     // class alpha.Outer
                     // Extends: java.lang.Object
                     // Implements: alpha.Shape
                     // Member types: alpha.Outer$Inner
                     // Fields: 0, Methods: 3

                     // Methods:
                     public Outer(int size);
                     public void draw();
                     public void draw(int count);
                     """, text("mc_get_class", Map.of("className", "alpha.Outer", "view", "methods")));
    }

    @Test
    void findsMemberTypesAndTheirMembersBySearch() {
        assertEquals("Found 1 result(s):\n[class] alpha.Outer$Inner extends alpha.Outer (0 fields, 2 methods)\nTotal: 1 result(s)", text("mc_search", Map.of("query", "inner", "type", "class")));
        assertEquals("Found 1 result(s):\n[record] alpha.Outer$Inner$Deep extends java.lang.Record (0 fields, 0 methods)\nTotal: 1 result(s)", text("mc_search", Map.of("query", "Deep")));
        assertEquals("Found 2 result(s):\n[method] alpha.Outer#<init>: public Outer(int size) (line 3)\n[method] alpha.Outer$Inner#<init>: public Inner() (line 7)\nTotal: 2 result(s)", text("mc_search", Map.of("query", "<init>", "type", "method")));
    }

    @Test
    void showsEveryOverloadOfAMethodAndFindsAConstructorByItsClassName() {
        assertEquals("""
                     // 2 methods of alpha.Outer are named draw

                     // Method: alpha.Outer#draw
                     // Signature: void draw()
                     // Modifiers: public
                     // Lines: 4-4

                     // Class extends: java.lang.Object

                     public class Outer implements Shape {
                         public Outer(int size) { }
                         public void draw() { }
                         public void draw(int count) { }
                         static class Inner extends Outer {
                             Inner() { super(0); }

                     // Method: alpha.Outer#draw
                     // Signature: void draw(int count)
                     // Modifiers: public
                     // Lines: 5-5

                     // Class extends: java.lang.Object

                         public Outer(int size) { }
                         public void draw() { }
                         public void draw(int count) { }
                         static class Inner extends Outer {
                             Inner() { super(0); }
                             public void draw() { }""", text("mc_get_method", Map.of("className", "alpha.Outer", "methodName", "draw")));
        assertEquals("""
                     // Method: alpha.Outer$Inner#<init>
                     // Signature: Inner()
                     // Modifiers: public
                     // Lines: 7-7

                     // Class extends: alpha.Outer

                         public void draw(int count) { }
                         static class Inner extends Outer {
                             Inner() { super(0); }
                             public void draw() { }
                             record Deep(int value) { }
                         }""", text("mc_get_method", Map.of("className", "alpha.Outer.Inner", "methodName", "Inner")));
    }

    @Test
    void showsOnlyTheRequestedLinesOfTheOverloadsTheyCover() {
        assertEquals("""
                     // 2 methods of alpha.Outer are named draw; the requested lines cover 1 of them

                     // Method: alpha.Outer#draw
                     // Signature: void draw(int count)
                     // Modifiers: public
                     // Lines: 5-5 (showing 5-5)

                     // Class extends: java.lang.Object

                         public void draw(int count) { }""", text("mc_get_method", Map.of("className", "alpha.Outer", "methodName", "draw", "fromLine", 5)));
        assertEquals("""
                     // Method: alpha.Outer$Inner#<init>
                     // Signature: Inner()
                     // Modifiers: public
                     // Lines: 7-7 (showing 7-7)

                     // Class extends: alpha.Outer

                             Inner() { super(0); }""", text("mc_get_method", Map.of("className", "alpha.Outer$Inner", "methodName", "Inner", "fromLine", 1, "toLine", 7)));
        assertEquals("No lines of alpha.Outer#draw fall in 6-end; its lines are 4-4, 5-5", text("mc_get_method", Map.of("className", "alpha.Outer", "methodName", "draw", "fromLine", 6)));
        assertEquals("Error executing mc_get_method: 'fromLine' must not be after 'toLine'", text("mc_get_method", Map.of("className", "alpha.Outer", "methodName", "draw", "fromLine", 5, "toLine", 4)));
    }

    @Test
    void pointsFromAMethodToTheSupertypesThroughWhichItIsCalled() {
        assertEquals("""
                     Found 1 callers:
                     caller.Three.run()V (line 3)
                     Total: 1 callers
                     Calls made through a supertype are recorded under that type:
                       alpha.Shape#draw (2 callers)""", text("mc_find_refs", Map.of("className", "alpha.Outer", "methodName", "draw", "direction", "callers")));
        assertEquals("""
                     No callers found for alpha.Outer.Inner#draw
                     Calls made through a supertype are recorded under that type:
                       alpha.Outer#draw (1 callers)
                       alpha.Shape#draw (2 callers)""", text("mc_find_refs", Map.of("className", "alpha.Outer.Inner", "methodName", "draw", "direction", "callers")));
        assertEquals("No callees found for alpha.Outer$Inner#draw", text("mc_find_refs", Map.of("className", "alpha.Outer$Inner", "methodName", "draw", "direction", "callees")));
    }

    @Test
    void saysWhenAClassDeclaresNoSuchMethodOrIsNotIndexed() {
        assertEquals("No callers found for alpha.Shape#absent\nalpha.Shape declares no method named absent.", text("mc_find_refs", Map.of("className", "alpha.Shape", "methodName", "absent", "direction", "callers")));
        assertEquals("""
                     No callers found for alpha.Outer$Inner#hashCode
                     alpha.Outer$Inner declares no method named hashCode.
                     Supertypes that declare it:
                       java.lang.Object#hashCode (1 callers)""", text("mc_find_refs", Map.of("className", "alpha.Outer$Inner", "methodName", "hashCode", "direction", "callers")));
        assertEquals("No callers found for wrong.Outer#draw\nClasses with that simple name: alpha.Outer", text("mc_find_refs", Map.of("className", "wrong.Outer", "methodName", "draw", "direction", "callers")));
        assertEquals("No callers found for alpha.Outer$1#run", text("mc_find_refs", Map.of("className", "alpha.Outer$1", "methodName", "run", "direction", "callers")));
    }
}

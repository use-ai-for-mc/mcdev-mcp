package dev.mcdevmcp.storage.symbols;

import dev.mcdevmcp.storage.model.ClassSymbol;
import dev.mcdevmcp.storage.model.FieldSymbol;
import dev.mcdevmcp.storage.model.MethodSymbol;
import dev.mcdevmcp.storage.model.ParameterSymbol;
import dev.mcdevmcp.storage.model.SearchHit;
import dev.mcdevmcp.storage.model.SearchHitKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SymbolIndexTest {
    private static final SymbolIndex INDEX = SymbolIndex.of(data());

    /**
     * Ids are deliberately out of query order: the Fabric rows have the lowest ids and must still sort last.
     */
    private static SymbolIndexData data() {
        List<SymbolIndexData.PackageRow> packages = List.of(new SymbolIndexData.PackageRow(1, "fabric", "0.102.0+1.21.5", "net.fabricmc.fabric.api"), SymbolIndexFixture.minecraftPackage(2, "alpha"), SymbolIndexFixture.minecraftPackage(3, "alpha.beta"));
        List<SymbolIndexData.TypeRow> types = List.of(new SymbolIndexData.TypeRow(1, 1, "fabric", "0.102.0+1.21.5", "net.fabricmc.fabric.api.FabricNeedle", "FabricNeedle", "class", "alpha.Base", List.of(), "FabricNeedle.java", 0, 0, 1, 1), SymbolIndexFixture.minecraftType(2, 3, "alpha.beta.Deep", "Deep", "class", null, List.of("alpha.Marker"), "alpha/beta/Deep.java", 1, 1), SymbolIndexFixture.minecraftType(3, 2, "alpha.Base", "Base", "class", null, List.of(), "alpha/Base.java", 1, 1), SymbolIndexFixture.minecraftType(4, 2, "alpha.NeedleChild", "NeedleChild", "class", "alpha.Base", List.of("alpha.Marker", "alpha.Marker"), "alpha/NeedleChild.java", 1, 1), SymbolIndexFixture.minecraftType(5, 2, "alpha.Marker", "Marker", "interface", null, List.of(), "alpha/Marker.java", 1, 1), SymbolIndexFixture.minecraftType(6, 3, "alpha.Base", "Base", "class", null, List.of(), "alpha/beta/Base.java", 1, 1));
        List<SymbolIndexData.FieldRow> fields = List.of(new SymbolIndexData.FieldRow(1, 4, 1, "second_needle", "int", "private", 0, 0, 1, 1), new SymbolIndexData.FieldRow(2, 4, 0, "firstNEEDLE", "int", "", 0, 0, 1, 1), new SymbolIndexData.FieldRow(3, 4, 2, "other", "int", "public,static", 0, 0, 1, 1));
        List<SymbolIndexData.MethodRow> methods = List.of(new SymbolIndexData.MethodRow(1, 4, 0, "Run", "()V", "void", "public", false, 0, 0, 1, 1), new SymbolIndexData.MethodRow(2, 4, 1, "run", "(I)V", "void", "public", false, 0, 0, 1, 1), new SymbolIndexData.MethodRow(3, 4, 2, "needleMethod", "(II)V", "void", "public", false, 0, 0, 1, 1), new SymbolIndexData.MethodRow(4, 4, 3, "NeedleChild", "()V", null, "public", true, 0, 0, 1, 1));
        List<SymbolIndexData.ParameterRow> parameters = List.of(new SymbolIndexData.ParameterRow(1, 3, 1, "right", "int", false, 0, 0, 1, 1), new SymbolIndexData.ParameterRow(2, 3, 0, "left", "int", false, 0, 0, 1, 1));
        return SymbolIndexData.of("1.21.5", "client", "0".repeat(64), "2026-07-16T00:00:00Z", packages, types, fields, methods, parameters);
    }

    private static SymbolIndexData nestedData() {
        List<SymbolIndexData.PackageRow> packages = List.of(SymbolIndexFixture.minecraftPackage(1, "alpha"));
        List<SymbolIndexData.TypeRow> types = List.of(SymbolIndexFixture.minecraftType(1, 1, "alpha.Outer", "Outer", "class", null, List.of(), "alpha/Outer.java", 1, 9), SymbolIndexFixture.minecraftType(2, 1, "alpha.Outer$Inner", "Inner", "class", null, List.of(), "alpha/Outer.java", 2, 8), SymbolIndexFixture.minecraftType(3, 1, "alpha.Outer$Inner$Deep", "Deep", "record", null, List.of(), "alpha/Outer.java", 3, 4), SymbolIndexFixture.minecraftType(4, 1, "alpha.Dollar$Sign", "Dollar$Sign", "class", null, List.of(), "alpha/Dollar$Sign.java", 1, 1), SymbolIndexFixture.minecraftType(5, 1, "alpha.Dollar", "Dollar", "class", null, List.of(), "alpha/Dollar.java", 1, 1));
        return SymbolIndexData.of("1.21.5", "client", "0".repeat(64), "2026-07-16T00:00:00Z", packages, types, List.of(), List.of(), List.of());
    }

    private static List<Long> ids(List<ClassSymbol> symbols) {
        return symbols.stream().map(ClassSymbol::id).toList();
    }

    @Test
    void ordersTypesByNamespaceThenPackageThenId() {
        assertEquals(List.of(3L, 4L, 5L, 2L, 6L), ids(INDEX.classesUnder("ALPHA", 100)));
        assertEquals(List.of(2L, 6L), ids(INDEX.classesUnder("alpha.beta", 100)));
        assertEquals(List.of(3L, 4L), ids(INDEX.classesUnder("alpha", 2)));
        assertEquals(List.of(1L), ids(INDEX.classesUnder("net.fabricmc", 100)));
        assertEquals(List.of(), ids(INDEX.classesUnder("alph", 100)));
        assertEquals(List.of(), ids(INDEX.classesUnder("alpha", 0)));
    }

    @Test
    void listsPackagesWithMinecraftFirstAndCountsBeforeLimiting() {
        assertEquals(List.of("alpha", "alpha.beta", "net.fabricmc.fabric.api"), INDEX.packages(null, 100).packages());
        assertEquals(3, INDEX.packages(null, 1).total());
        assertEquals(List.of("alpha"), INDEX.packages(null, 1).packages());
        assertEquals(List.of("net.fabricmc.fabric.api"), INDEX.packages("fabric", 100).packages());
        assertEquals(0, INDEX.packages("unknown", 100).total());
    }

    @Test
    void resolvesBinaryNamesExactlyAndPrefersTheLowestId() {
        assertEquals(3, INDEX.classByName("alpha.Base").id());
        assertEquals("alpha", INDEX.classByName("alpha.Base").packageName());
        assertNull(INDEX.classByName("alpha.base"));
        assertNull(INDEX.classByName("missing.Type"));
    }

    @Test
    void ordersMembersByOrdinalAndKeepsModifiers() {
        assertEquals(List.of("firstNEEDLE", "second_needle", "other"), INDEX.fields(4).stream().map(FieldSymbol::name).toList());
        assertEquals(List.of("Run", "run", "needleMethod", "NeedleChild"), INDEX.methods(4).stream().map(MethodSymbol::name).toList());
        assertEquals(List.of("left", "right"), INDEX.parameters(3).stream().map(ParameterSymbol::name).toList());
        assertEquals(2, INDEX.fields(4).get(2).modifiers().size());
        assertTrue(INDEX.fields(4).getFirst().modifiers().isEmpty());
        assertEquals(List.of(), INDEX.fields(99));
        assertEquals(List.of(), INDEX.parameters(99));
    }

    @Test
    void prefersExactMethodNamesOverCaseInsensitiveOnes() {
        assertEquals(List.of(2L), INDEX.methodsNamed(4, "run").stream().map(MethodSymbol::id).toList());
        assertEquals(List.of(1L), INDEX.methodsNamed(4, "Run").stream().map(MethodSymbol::id).toList());
        assertEquals(List.of(1L), INDEX.methodsNamed(4, "RUN").stream().map(MethodSymbol::id).toList());
        assertEquals(List.of(), INDEX.methodsNamed(4, "missing"));
        assertEquals(List.of(), INDEX.methodsNamed(99, "run"));
    }

    @Test
    void resolvesMemberTypesByBinaryNameAndBySourceName() {
        SymbolIndex index = SymbolIndex.of(nestedData());

        assertEquals(2, index.classByName("alpha.Outer$Inner").id());
        assertEquals(2, index.classByName("alpha.Outer.Inner").id());
        assertEquals(3, index.classByName("alpha.Outer.Inner.Deep").id());
        assertNull(index.classByName("alpha.Outer.Inner$Deep"));
        assertEquals(4, index.classByName("alpha.Dollar$Sign").id());
        assertNull(index.classByName("alpha.Dollar.Sign"));
        assertEquals(List.of(2L), ids(index.memberTypes("alpha.Outer")));
        assertEquals(List.of(3L), ids(index.memberTypes("alpha.Outer$Inner")));
        assertEquals(List.of(), ids(index.memberTypes("alpha.Dollar")));
        assertEquals(1, index.enclosingType(index.classByName("alpha.Outer$Inner")).orElseThrow().id());
        assertTrue(index.enclosingType(index.classByName("alpha.Outer")).isEmpty());
        assertTrue(index.enclosingType(index.classByName("alpha.Dollar$Sign")).isEmpty(), "a dollar sign in a top-level name is not nesting");
    }

    @Test
    void suggestsTypesByTheLastSegmentOfAName() {
        SymbolIndex index = SymbolIndex.of(nestedData());

        assertEquals(List.of(2L), ids(index.classesNamedLike("Inner", 10)));
        assertEquals(List.of(2L), ids(index.classesNamedLike("wrong.pkg.INNER", 10)));
        assertEquals(List.of(3L), ids(index.classesNamedLike("alpha.Outer$Deep", 10)));
        assertEquals(List.of(), ids(index.classesNamedLike("alpha.Outer.", 10)));
        assertEquals(List.of(), ids(index.classesNamedLike("Inner", 0)));
    }

    @Test
    void walksTheHierarchyInTypeOrderOncePerListing() {
        assertEquals(List.of(4L, 1L), ids(INDEX.hierarchy("alpha.Base", true, 100)));
        assertEquals(List.of(4L), ids(INDEX.hierarchy("alpha.Base", true, 1)));
        assertEquals(List.of(4L, 4L, 2L), ids(INDEX.hierarchy("alpha.Marker", false, 100)));
        assertEquals(List.of(), ids(INDEX.hierarchy("alpha.Marker", true, 100)));
        assertEquals(List.of(), ids(INDEX.hierarchy("missing.Type", false, 100)));
    }

    @Test
    void searchesLiterallyAndCaseInsensitivelyInTypeThenMemberOrder() {
        List<SearchHit> all = INDEX.search("NEEDLE", null, 100);
        assertEquals(List.of(SearchHitKind.CLASS, SearchHitKind.FIELD, SearchHitKind.FIELD, SearchHitKind.METHOD, SearchHitKind.METHOD, SearchHitKind.CLASS), all.stream().map(SearchHit::kind).toList());
        assertEquals(List.of(4L, 4L, 4L, 4L, 4L, 1L), all.stream().map(hit -> hit.owner().id()).toList());
        assertEquals(List.of("firstNEEDLE", "second_needle"), all.stream().flatMap(hit -> hit.field().stream()).map(FieldSymbol::name).toList());
        assertEquals(List.of("needleMethod", "NeedleChild"), all.stream().flatMap(hit -> hit.method().stream()).map(MethodSymbol::name).toList());
        assertEquals(List.of("left", "right"), all.get(3).parameters().stream().map(ParameterSymbol::name).toList());
        assertEquals(3, all.getFirst().fieldCount());
        assertEquals(4, all.getFirst().methodCount());

        assertEquals(3, INDEX.search("needle", null, 3).size());
        assertEquals(List.of(), INDEX.search("needle", null, 0));
        assertEquals(List.of(), INDEX.search("needle", "package", 100));
        assertEquals(List.of(), INDEX.search("%", null, 100));
        assertEquals(List.of("second_needle"), INDEX.search("_", "field", 100).stream().flatMap(hit -> hit.field().stream()).map(FieldSymbol::name).toList());
    }

    @Test
    void reportsMemberCountsOnlyWhenClassesAreSearched() {
        assertEquals(List.of(4L, 1L), INDEX.search("needle", "class", 100).stream().map(hit -> hit.owner().id()).toList());
        assertEquals(3, INDEX.search("needle", "class", 100).getFirst().fieldCount());
        SearchHit field = INDEX.search("needle", "field", 100).getFirst();
        assertEquals(0, field.fieldCount());
        assertEquals(0, field.methodCount());
        SearchHit method = INDEX.search("needle", "method", 100).getFirst();
        assertEquals(SearchHitKind.METHOD, method.kind());
        assertEquals(0, method.methodCount());
    }

    @Test
    void rejectsRowsThatCannotBeResolved() {
        SymbolIndexData valid = data();
        SymbolIndexData.Header header = valid.header();
        SymbolIndexData orphan = SymbolIndexData.of(header.minecraftVersion(), header.sourceRoot(), header.remappedJarSha256(), header.builtAt(), valid.packages().subList(1, 3), valid.types(), valid.fields(), valid.methods(), valid.parameters());
        assertThrows(IllegalArgumentException.class, () -> SymbolIndex.of(orphan));
    }
}

package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.index.IndexRequest;
import dev.mcdevmcp.analysis.index.IndexSummary;
import dev.mcdevmcp.analysis.index.SourceIndexer;
import dev.mcdevmcp.analysis.index.SourceRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JavacSourceParserTest {
    @TempDir
    Path temporaryDirectory;

    private static long typeId(List<String> dump, String binaryName) {
        String row = dump.stream().filter(candidate -> candidate.startsWith("types|") && candidate.contains("|" + binaryName + "|")).findFirst().orElseThrow();
        return Long.parseLong(row.split("\\|", -1)[1]);
    }

    private static boolean memberOf(String row, String table, long typeId) {
        return row.startsWith(table + "|") && row.split("\\|", -1)[2].equals(Long.toString(typeId));
    }

    private static void assertRecordRange(List<String> dump, String table, String name, String source, String declaration) {
        String row = dump.stream().filter(candidate -> candidate.startsWith(table + "|") && candidate.split("\\|", -1)[4].equals(name)).findFirst().orElseThrow();
        assertRecordRange(row, source, declaration);
    }

    private static void assertRecordRange(String row, String source, String declaration) {
        int startColumn = row.startsWith("methods|") ? 9 : 7;
        String[] columns = row.split("\\|", -1);
        int start = source.indexOf(declaration);
        int end = start + declaration.length();
        int startLine = 1 + (int) source.substring(0, start).chars().filter(character -> character == '\n').count();
        int endLine = startLine + (int) declaration.chars().filter(character -> character == '\n').count();
        assertEquals(start, Integer.parseInt(columns[startColumn]), row);
        assertEquals(end, Integer.parseInt(columns[startColumn + 1]), row);
        assertEquals(startLine, Integer.parseInt(columns[startColumn + 2]), row);
        assertEquals(endLine, Integer.parseInt(columns[startColumn + 3]), row);
        assertEquals(declaration, source.substring(Integer.parseInt(columns[startColumn]), Integer.parseInt(columns[startColumn + 1])));
    }

    @Test
    void indexesEveryTopLevelDeclarationAndOnlySourceDeclaredDirectMembers() throws Exception {
        Path sources = IndexerTestSupport.copyFixture("main", temporaryDirectory.resolve("sources"));
        Path jar = IndexerTestSupport.fixtureCatalog(temporaryDirectory.resolve("remapped.jar"));
        Path dependency = IndexerTestSupport.fixtureDependency(temporaryDirectory.resolve("dependency.jar"));
        Path database = temporaryDirectory.resolve("symbols.mv.db");

        IndexRequest request = IndexerTestSupport.request(List.of(new SourceRoot(dev.mcdevmcp.storage.model.SourceNamespace.MINECRAFT, Optional.empty(), sources)), jar, List.of(dependency), database, 1);
        IndexSummary summary = new SourceIndexer().build(request);
        List<String> dump = IndexerTestSupport.dump(database);

        assertEquals(8, summary.types());
        assertTrue(dump.stream().anyMatch(row -> row.contains("|index.fixture.FeatureSet|FeatureSet|class|java.util.ArrayList|")));
        assertTrue(dump.stream().anyMatch(row -> row.startsWith("type_interfaces|") && row.endsWith("|java.lang.Runnable")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|index.fixture.Child|Child|class|index.fixture.FeatureSet|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|index.fixture.NestedChild|NestedChild|class|index.fixture.FeatureSet$Nested|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|index.fixture.SourceBase|SourceBase|class|java.lang.Object|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|index.fixture.Pair|Pair|record|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|index.fixture.Marker|Marker|annotation|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|index.fixture.Shade|Shade|enum|")));
        assertFalse(dump.stream().anyMatch(row -> row.startsWith("types|") && row.split("\\|", -1)[5].equals("index.fixture.FeatureSet$Nested")));
        assertFalse(dump.stream().anyMatch(row -> row.contains("|hidden|")));
        assertFalse(dump.stream().anyMatch(row -> row.contains("|hiddenMethod|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|values|java.util.List<? super T[]>|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|external|dependency.External|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|FIRST|int|public,static,final|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|SECOND|int|public,static,final|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|transform|(Ljava/lang/CharSequence;[Ljava/lang/String;)Ljava/util/List;|java.util.List<? extends U>|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|rest|java.lang.String[]|true|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|<init>|(Ljava/lang/Object;Ljava/lang/Object;)V|null|")));
        assertFalse(dump.stream().anyMatch(row -> row.contains("|FeatureSet|()V|")), "constructors use <init>, not the source type name");
        assertFalse(dump.stream().anyMatch(row -> row.contains("|values|()[Lindex/fixture/Shade;|") || row.contains("|valueOf|(Ljava/lang/String;)Lindex/fixture/Shade;|")));
        assertFalse(dump.stream().anyMatch(row -> row.contains("|left|()Ljava/lang/Object;|") || row.contains("|right|()Ljava/lang/Object;|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|left|T|")));
        assertTrue(dump.stream().anyMatch(row -> row.contains("|right|T|")));

        long sourceBaseId = typeId(dump, "index.fixture.SourceBase");
        long defaultsId = typeId(dump, "index.fixture.Defaults");
        long markerId = typeId(dump, "index.fixture.Marker");
        long shadeId = typeId(dump, "index.fixture.Shade");
        assertFalse(dump.stream().anyMatch(row -> row.startsWith("methods|") && row.split("\\|", -1)[2].equals(Long.toString(sourceBaseId))), "default constructors are compiler-generated");
        assertTrue(dump.stream().anyMatch(row -> memberOf(row, "fields", defaultsId) && row.contains("|CONSTANT|int|public,static,final|")));
        assertTrue(dump.stream().anyMatch(row -> memberOf(row, "methods", defaultsId) && row.contains("|value|()I|int|public,default|")));
        assertTrue(dump.stream().anyMatch(row -> memberOf(row, "methods", markerId) && row.contains("|value|()Ljava/lang/String;|java.lang.String|public,abstract|")));
        assertTrue(dump.stream().anyMatch(row -> memberOf(row, "fields", shadeId) && row.contains("|RED|index.fixture.Shade|public,static,final|")));
        assertTrue(dump.stream().anyMatch(row -> memberOf(row, "fields", shadeId) && row.contains("|BLUE|index.fixture.Shade|public,static,final|")));
        assertTrue(dump.stream().anyMatch(row -> row.startsWith("types|") && row.contains("|index/fixture/FeatureSet.java|")));
    }

    @Test
    void preservesUtf16ExclusiveOffsetsAndEndLines() throws Exception {
        Path sources = temporaryDirectory.resolve("unicode");
        java.nio.file.Files.createDirectories(sources.resolve("unicode"));
        String source = "package unicode;\npublic class Ranges {\n    String value = \"\uD83D\uDE00\";\n    void first() {\n    }\n    void second() {}\n}\n";
        java.nio.file.Files.writeString(sources.resolve("unicode/Ranges.java"), source, java.nio.charset.StandardCharsets.UTF_8);
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("empty.jar"), java.util.Map.of());
        Path database = temporaryDirectory.resolve("ranges.mv.db");

        new SourceIndexer().build(IndexerTestSupport.request(sources, jar, database, 1));
        List<String> dump = IndexerTestSupport.dump(database);

        String type = dump.stream().filter(row -> row.startsWith("types|")).findFirst().orElseThrow();
        String[] typeColumns = type.split("\\|", -1);
        assertEquals(source.indexOf("public class"), Integer.parseInt(typeColumns[10]));
        assertEquals(source.lastIndexOf('}') + 1, Integer.parseInt(typeColumns[11]));
        assertEquals("2", typeColumns[12]);
        assertEquals("7", typeColumns[13]);
        String first = dump.stream().filter(row -> row.startsWith("methods|") && row.contains("|first|")).findFirst().orElseThrow();
        assertTrue(first.endsWith("|4|5"), first);
        assertEquals(source.length(), source.codePoints().map(Character::charCount).sum());
    }

    @Test
    void usesExactCompilerRangesForAnnotatedMultilineRecordComponentsAndCompactParameters() throws Exception {
        Path sources = Files.createDirectories(temporaryDirectory.resolve("record-ranges/ranges"));
        String source = """
                        package ranges;
                        import java.lang.annotation.ElementType;
                        import java.lang.annotation.Target;
                        @Target(ElementType.RECORD_COMPONENT)
                        @interface Label { String value(); }
                        public record ExactRecord(
                                @Label("name)") String name,
                                @Label("name,") java.util.List<
                                        String
                                    > values,
                                String nameAgain
                        ) {
                            public ExactRecord {
                            }
                        }
                        """;
        Files.writeString(sources.resolve("ExactRecord.java"), source, StandardCharsets.UTF_8);
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("record-empty.jar"), Map.of());
        Path database = temporaryDirectory.resolve("record-ranges.mv.db");

        new SourceIndexer().build(IndexerTestSupport.request(sources.getParent(), jar, database, 1));
        List<String> dump = IndexerTestSupport.dump(database);

        assertRecordRange(dump, "fields", "name", source, "@Label(\"name)\") String name");
        assertRecordRange(dump, "fields", "values", source, """
                                                            @Label("name,") java.util.List<
                                                                            String
                                                                        > values""");
        assertRecordRange(dump, "fields", "nameAgain", source, "String nameAgain");
        assertRecordRange(dump, "parameters", "name", source, "@Label(\"name)\") String name");
        assertRecordRange(dump, "parameters", "values", source, """
                                                                @Label("name,") java.util.List<
                                                                                String
                                                                            > values""");
        assertRecordRange(dump, "parameters", "nameAgain", source, "String nameAgain");
    }

    @Test
    void indexesSourcesWithBuiltinAnnotationsAndComplexFieldInitializers() throws Exception {
        Path sources = Files.createDirectories(temporaryDirectory.resolve("annotated-sources/sample"));
        String source = """
                        package sample;
                        import org.jetbrains.annotations.Nullable;
                        import org.jetbrains.annotations.Contract;
                        import org.jspecify.annotations.NonNull;
                        import javax.annotation.CheckForNull;
                        public class AnnotatedClass {
                            public static final String FIELD = "test";
                            @Nullable private String nullableField;
                            @NonNull public String method(@CheckForNull String arg) {
                                return arg == null ? "" : arg;
                            }
                        }
                        """;
        Files.writeString(sources.resolve("AnnotatedClass.java"), source, StandardCharsets.UTF_8);
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("annotated-empty.jar"), Map.of());
        Path database = temporaryDirectory.resolve("annotated.mv.db");

        IndexSummary summary = new SourceIndexer().build(IndexerTestSupport.request(sources.getParent(), jar, database, 1));
        assertEquals(1, summary.types());
        assertEquals(2, summary.fields());
        assertEquals(1, summary.methods());
    }

    @Test
    void indexesTypeQualifierDefaultWithoutTheCompileOnlyJsr305Jar() throws Exception {
        Path sources = Files.createDirectories(temporaryDirectory.resolve("type-qualifier/com/mojang/blaze3d"));
        Files.writeString(sources.resolve("DontObfuscate.java"), """
                                                                 package com.mojang.blaze3d;
                                                                 import java.lang.annotation.ElementType;
                                                                 import java.lang.annotation.Retention;
                                                                 import java.lang.annotation.RetentionPolicy;
                                                                 import javax.annotation.meta.TypeQualifierDefault;
                                                                 @TypeQualifierDefault({ElementType.TYPE, ElementType.METHOD})
                                                                 @Retention(RetentionPolicy.CLASS)
                                                                 public @interface DontObfuscate {}
                                                                 """, StandardCharsets.UTF_8);
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("type-qualifier-empty.jar"), Map.of());

        IndexSummary summary = new SourceIndexer().build(IndexerTestSupport.request(sources.getParent().getParent().getParent(), jar, temporaryDirectory.resolve("type-qualifier.mv.db"), 1));

        assertEquals(1, summary.types());
        assertEquals(0, summary.fields());
        assertEquals(0, summary.methods());
    }

    @Test
    void retainsDecompilerWeakerAccessDiagnosticsWithoutDiscardingDeclarations() throws Exception {
        Path sources = Files.createDirectories(temporaryDirectory.resolve("weaker-access/sample"));
        Files.writeString(sources.resolve("Base.java"), "package sample; public class Base<T> { public void run(T task) {} }", StandardCharsets.UTF_8);
        Files.writeString(sources.resolve("Server.java"), "package sample; public class Server extends Base<String> { protected void run(String task) {} }", StandardCharsets.UTF_8);
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("weaker-access-empty.jar"), Map.of());

        IndexSummary summary = new SourceIndexer().build(IndexerTestSupport.request(sources.getParent(), jar, temporaryDirectory.resolve("weaker-access.mv.db"), 1));

        assertEquals(2, summary.types());
        assertEquals(2, summary.methods());
        assertTrue(summary.evidence().diagnostics().stream().anyMatch(diagnostic -> diagnostic.contains("compiler.err.override.weaker.access")));
    }

    @Test
    void indexesQualifiedNestedTypeUseAnnotationDespiteJavacDiagnostic() throws Exception {
        Path sources = Files.createDirectories(temporaryDirectory.resolve("qualified-nested/nested"));
        String source = """
                        package nested;
                        import java.lang.annotation.ElementType;
                        import java.lang.annotation.Target;
                        public class RealmsPdpScreen {
                            static class PdpHeaderEntry {
                                static class Subtitle {}
                            }
                            @Target(ElementType.TYPE_USE)
                            @interface Nullable {}
                            void inadmissible(final @Nullable RealmsPdpScreen.PdpHeaderEntry.Subtitle subTitle) {}
                            void admissible(final RealmsPdpScreen.PdpHeaderEntry.@Nullable Subtitle subTitle) {}
                        }
                        """;
        Files.writeString(sources.resolve("RealmsPdpScreen.java"), source, StandardCharsets.UTF_8);
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("qualified-nested-empty.jar"), Map.of());
        Path database = temporaryDirectory.resolve("qualified-nested.mv.db");

        IndexSummary summary = new SourceIndexer().build(IndexerTestSupport.request(sources.getParent(), jar, database, 1));
        List<String> dump = IndexerTestSupport.dump(database);

        assertEquals(1, summary.types());
        assertEquals(List.of("nested/RealmsPdpScreen.java"), summary.evidence().typedCompilationUnits());
        assertTrue(dump.stream().anyMatch(row -> row.contains("|nested.RealmsPdpScreen|RealmsPdpScreen|class|java.lang.Object|nested/RealmsPdpScreen.java|")), dump.toString());
        String indexedMethod = dump.stream().filter(row -> row.contains("|inadmissible|(Lnested/RealmsPdpScreen$PdpHeaderEntry$Subtitle;)V|void||false|")).findFirst().orElseThrow(() -> new AssertionError(dump));
        String methodId = indexedMethod.split("\\|", -1)[1];
        assertRecordRange(indexedMethod, source, "void inadmissible(final @Nullable RealmsPdpScreen.PdpHeaderEntry.Subtitle subTitle) {}");
        assertTrue(dump.stream().anyMatch(row -> row.contains("|admissible|(Lnested/RealmsPdpScreen$PdpHeaderEntry$Subtitle;)V|void||false|")), dump.toString());
        String indexedParameter = dump.stream().filter(row -> row.startsWith("parameters|") && row.split("\\|", -1)[2].equals(methodId) && row.contains("|subTitle|nested.RealmsPdpScreen$PdpHeaderEntry$Subtitle|false|")).findFirst().orElseThrow(() -> new AssertionError(dump));
        assertRecordRange(indexedParameter, source, "final @Nullable RealmsPdpScreen.PdpHeaderEntry.Subtitle subTitle");
        assertTrue(summary.evidence().diagnostics().stream().anyMatch(diagnostic -> diagnostic.startsWith("nested/RealmsPdpScreen.java:10:69: ") && diagnostic.contains("compiler.err.type.annotation.inadmissible")), summary.evidence().diagnostics().toString());
    }
}

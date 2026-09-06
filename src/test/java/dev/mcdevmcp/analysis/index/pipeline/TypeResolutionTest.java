package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.classfile.ClassFileType;
import dev.mcdevmcp.analysis.classfile.ClassFileTypeCatalog;
import dev.mcdevmcp.analysis.index.SourceIndexer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.constant.ClassDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TypeResolutionTest {
    @TempDir
    Path temporaryDirectory;

    private static void write(Path root, String relativePath, String source) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    private static void assertHierarchy(List<String> dump, String type, String superclass) {
        assertTrue(dump.stream().anyMatch(row -> row.startsWith("types|") && row.contains("|" + type + "|") && row.contains("|" + superclass + "|")), dump.toString());
    }

    private static void assertInterface(List<String> dump, String type, String interfaceName) {
        String typeRow = dump.stream().filter(row -> row.startsWith("types|") && row.contains("|" + type + "|")).findFirst().orElseThrow();
        String typeId = typeRow.split("\\|", -1)[1];
        assertTrue(dump.stream().anyMatch(row -> row.startsWith("type_interfaces|" + typeId + "|") && row.endsWith("|" + interfaceName)), dump.toString());
    }

    @Test
    void classFileCatalogUsesFinalJdkApiAndPreservesBinaryHierarchy() throws Exception {
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("catalog.jar"), Map.of("catalog/Outer.java", "package catalog; public class Outer { public static final class Inner extends java.util.ArrayList<String> implements java.io.Serializable {} }"));

        ClassFileTypeCatalog catalog = ClassFileTypeCatalog.read(jar);
        ClassFileType inner = catalog.require("catalog.Outer$Inner");

        assertEquals(ClassDesc.of("catalog.Outer$Inner"), inner.descriptor());
        assertEquals(Optional.of(ClassDesc.of("java.util.ArrayList")), inner.superclass());
        assertEquals(List.of(ClassDesc.of("java.io.Serializable")), inner.interfaces());
        assertTrue(inner.nestHost().isPresent());
        assertFalse(catalog.contains("module-info"));
    }

    @Test
    void classFileCatalogRejectsDuplicateLogicalEntries() throws Exception {
        Path original = IndexerTestSupport.createJar(temporaryDirectory.resolve("original.jar"), Map.of("duplicate/Type.java", "package duplicate; public class Type {}"));
        Path duplicate = temporaryDirectory.resolve("duplicate.jar");
        byte[] classBytes;
        try (var zip = new java.util.zip.ZipFile(original.toFile())) {
            classBytes = zip.getInputStream(zip.getEntry("duplicate/Type.class")).readAllBytes();
        }
        try (var output = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(duplicate))) {
            for (String name : List.of("duplicate/Type.class", "other/Name.class")) {
                output.putNextEntry(new java.util.jar.JarEntry(name));
                output.write(classBytes);
                output.closeEntry();
            }
        }

        IOException failure = assertThrows(IOException.class, () -> ClassFileTypeCatalog.read(duplicate));
        assertTrue(failure.getMessage().contains("duplicate.Type"));
    }

    @Test
    void preservesParameterizedAndRawOwnersOfGenericNonStaticMemberTypes() throws Exception {
        Path sources = Files.createDirectories(temporaryDirectory.resolve("owners/owner"));
        Files.writeString(sources.resolve("Outer.java"), """
                                                         package owner;
                                                         public class Outer<T> {
                                                             public class Inner<U> {}
                                                         }
                                                         """, StandardCharsets.UTF_8);
        Files.writeString(sources.resolve("Use.java"), """
                                                       package owner;
                                                       public class Use {
                                                           Outer<String>.Inner<Integer> field;
                                                           Outer.Inner rawOwner;
                                                           java.util.List<? extends Outer<String>.Inner<Integer>[]> wildcardArray;
                                                           Outer<String>.Inner<Integer> convert(Outer<String>.Inner<? super Long> value) { return null; }
                                                       }
                                                       """, StandardCharsets.UTF_8);
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("owners-empty.jar"), Map.of());
        Path database = temporaryDirectory.resolve("owners.mv.db");

        new SourceIndexer().build(IndexerTestSupport.request(sources.getParent(), jar, database, 1));
        List<String> dump = IndexerTestSupport.dump(database);

        assertTrue(dump.stream().anyMatch(row -> row.startsWith("fields|") && row.contains("|field|owner.Outer<java.lang.String>::owner.Outer$Inner<java.lang.Integer>|")), dump.toString());
        assertTrue(dump.stream().anyMatch(row -> row.startsWith("fields|") && row.contains("|rawOwner|owner.Outer::owner.Outer$Inner|")), dump.toString());
        assertTrue(dump.stream().anyMatch(row -> row.startsWith("fields|") && row.contains("|wildcardArray|java.util.List<? extends owner.Outer<java.lang.String>::owner.Outer$Inner<java.lang.Integer>[]>|")), dump.toString());
        assertTrue(dump.stream().anyMatch(row -> row.startsWith("methods|") && row.contains("|convert|") && row.contains("|owner.Outer<java.lang.String>::owner.Outer$Inner<java.lang.Integer>|")), dump.toString());
        assertTrue(dump.stream().anyMatch(row -> row.startsWith("parameters|") && row.contains("|value|owner.Outer<java.lang.String>::owner.Outer$Inner<? super java.lang.Long>|")), dump.toString());
    }

    @Test
    void resolvesEverySourceOnlyHierarchyNameForm() throws Exception {
        Path root = temporaryDirectory.resolve("hierarchy");
        write(root, "base/ExplicitBase.java", "package base; public class ExplicitBase {}");
        write(root, "base/ExplicitContract.java", "package base; public interface ExplicitContract {}");
        write(root, "base/WildcardBase.java", "package base; public class WildcardBase {}");
        write(root, "base/WildcardContract.java", "package base; public interface WildcardContract {}");
        write(root, "same/SameBase.java", "package same; public class SameBase {}");
        write(root, "same/SameContract.java", "package same; public interface SameContract {}");
        write(root, "nested/Owner.java", "package nested; public class Owner { public static class Nested {} public interface Contract {} }");
        write(root, "use/ExplicitChild.java", "package use; import base.ExplicitBase; import base.ExplicitContract; public class ExplicitChild extends ExplicitBase implements ExplicitContract {}");
        write(root, "use/WildcardChild.java", "package use; import base.*; public class WildcardChild extends WildcardBase implements WildcardContract {}");
        write(root, "same/SameChild.java", "package same; public class SameChild extends SameBase implements SameContract {}");
        write(root, "use/NestedChild.java", "package use; import nested.Owner; public class NestedChild extends Owner.Nested implements Owner.Contract {}");
        Path jar = IndexerTestSupport.createJar(temporaryDirectory.resolve("hierarchy-empty.jar"), Map.of());
        Path database = temporaryDirectory.resolve("hierarchy.mv.db");

        new SourceIndexer().build(IndexerTestSupport.request(root, jar, database, 4));
        List<String> dump = IndexerTestSupport.dump(database);

        assertHierarchy(dump, "use.ExplicitChild", "base.ExplicitBase");
        assertInterface(dump, "use.ExplicitChild", "base.ExplicitContract");
        assertHierarchy(dump, "use.WildcardChild", "base.WildcardBase");
        assertInterface(dump, "use.WildcardChild", "base.WildcardContract");
        assertHierarchy(dump, "same.SameChild", "same.SameBase");
        assertInterface(dump, "same.SameChild", "same.SameContract");
        assertHierarchy(dump, "use.NestedChild", "nested.Owner$Nested");
        assertInterface(dump, "use.NestedChild", "nested.Owner$Contract");
    }
}

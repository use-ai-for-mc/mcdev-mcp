package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.index.SourceIndexer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class CompilerClasspathTest {
    private static final String USER = """
            package game;

            import java.util.List;

            @stub.Checked
            public class User {
                @stub.Tagged(value = "x", count = 3, type = String.class, targets = {java.lang.annotation.ElementType.FIELD}, nested = @stub.Checked, empty = {})
                public List<@stub.Maybe String> names;

                @stub.Api.Internal
                public void run(@stub.Checked int value) {
                }
            }
            """;

    @TempDir
    Path temporaryDirectory;

    // Copies a jar without the entries under one directory, as a jar built against compile-only annotations ships.
    private static Path without(Path jar, String directory, Path target) throws Exception {
        try (JarFile input = new JarFile(jar.toFile()); JarOutputStream output = new JarOutputStream(Files.newOutputStream(target))) {
            for (JarEntry entry : input.stream().filter(entry -> !entry.getName().startsWith(directory)).toList()) {
                output.putNextEntry(new JarEntry(entry.getName()));
                try (var stream = input.getInputStream(entry)) {
                    stream.transferTo(output);
                }
                output.closeEntry();
            }
        }
        return target;
    }

    private static ClassModel stub(CompilerClasspath classpath, String binaryName) {
        CompilerClassFile file = classpath.classes().stream().filter(candidate -> candidate.binaryName().equals(binaryName)).findFirst().orElseThrow(() -> new AssertionError("no stub for " + binaryName));
        return ClassFile.of().parse(file.bytes());
    }

    @Test
    void sourcesNamingAnnotationTypesNoEntryDefinesStillIndex() throws Exception {
        Path compiled = IndexerTestSupport.createJar(temporaryDirectory.resolve("compiled.jar"), Map.of(
                "stub/Checked.java", "package stub; public @interface Checked {}",
                "stub/Maybe.java", "package stub; @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE) public @interface Maybe {}",
                "stub/Api.java", "package stub; public final class Api { public @interface Internal {} }",
                "stub/Tagged.java", "package stub; public @interface Tagged { String value(); int count(); Class<?> type(); java.lang.annotation.ElementType[] targets(); Checked nested(); String[] empty(); }",
                "game/User.java", USER));
        Path jar = without(compiled, "stub/", temporaryDirectory.resolve("remapped.jar"));
        Path sources = Files.createDirectories(temporaryDirectory.resolve("sources/game"));
        Files.writeString(sources.resolve("User.java"), USER, StandardCharsets.UTF_8);
        var request = IndexerTestSupport.request(sources.getParent(), jar, temporaryDirectory.resolve("index.jsonl"), 1);

        var summary = new SourceIndexer().build(request);
        assertEquals(1, summary.types());
        assertEquals(1, summary.fields());

        CompilerClasspath classpath = CompilerClasspath.read(request);
        ClassModel tagged = stub(classpath, "stub.Tagged");
        assertEquals(List.of("count()I", "empty()[Ljava/lang/String;", "nested()Lstub/Checked;", "targets()[Ljava/lang/annotation/ElementType;", "type()Ljava/lang/Class;", "value()Ljava/lang/String;"), tagged.methods().stream().map(method -> method.methodName().stringValue() + method.methodType().stringValue()).toList());
        assertTrue(tagged.methods().stream().allMatch(method -> method.findAttribute(Attributes.annotationDefault()).isPresent()));
        assertTrue(stub(classpath, "stub.Maybe").findAttribute(Attributes.runtimeVisibleAnnotations()).isPresent());
        assertTrue(stub(classpath, "stub.Checked").findAttribute(Attributes.runtimeVisibleAnnotations()).isEmpty());
        assertTrue(stub(classpath, "stub.Api").methods().isEmpty());
        assertTrue(stub(classpath, "stub.Api$Internal").flags().has(AccessFlag.ANNOTATION));
        assertFalse(stub(classpath, "stub.Api").flags().has(AccessFlag.ANNOTATION));
    }
}

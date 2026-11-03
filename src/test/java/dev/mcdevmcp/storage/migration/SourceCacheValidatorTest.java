package dev.mcdevmcp.storage.migration;

import dev.mcdevmcp.analysis.decompile.MinecraftDecompiler;
import dev.mcdevmcp.analysis.decompile.MinecraftRemapper;
import dev.mcdevmcp.support.Cancellation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

final class SourceCacheValidatorTest {
    @TempDir
    Path temporary;

    @Test
    void accountsForNestedLocalAnonymousDollarNamedAndDescriptorUnits() throws Exception {
        Path sources = sources(Map.of("sample/Outer.java", "package sample; public class Outer { class Nested {} Object value() { class Local {} return new Runnable() { public void run() {} }; } }", "sample/Dollar$Name.java", "package sample; public class Dollar$Name {}", "sample/package-info.java", "@Deprecated package sample;", "module-info.java", "module sample.module { exports sample; }"));
        Path jar = compile(sources, "-g:source");
        SourceValidation validation = new SourceCacheValidator().validate(sources, jar, Cancellation.none());
        assertEquals(SourceValidationStatus.VALID, validation.status(), validation.diagnostics().toString());
        assertEquals(List.of("module-info.java", "sample/Dollar$Name.java", "sample/Outer.java", "sample/package-info.java"), validation.requiredUnits());
    }

    @Test
    void rejectsPartialButIndividuallyValidLegacyAndParsesExtraSources() throws Exception {
        Path sources = sources(Map.of("A.java", "class A {}", "B.java", "class B {}"));
        Path jar = compile(sources, "-g:source");
        Files.delete(sources.resolve("B.java"));
        SourceValidation partial = new SourceCacheValidator().validate(sources, jar, Cancellation.none());
        assertEquals(SourceValidationStatus.INVALID, partial.status());
        assertTrue(partial.diagnostics().contains("Missing required compilation unit: B.java"));
        Files.writeString(sources.resolve("B.java"), "class B {} class UserAddition {}");
        Files.writeString(sources.resolve("Extra.java"), "class Extra { broken ! }");
        SourceValidation extra = new SourceCacheValidator().validate(sources, jar, Cancellation.none());
        assertEquals(SourceValidationStatus.INVALID, extra.status());
        assertTrue(extra.diagnostics().stream().anyMatch(value -> value.contains("Extra.java")));
    }

    @Test
    void retainsMoreThanOneHundredSyntaxErrorsInOneBatch() throws Exception {
        Path sources = sources(Map.of("A.java", "class A {}"));
        Path jar = compile(sources, "-g:source");
        for (int index = 0; index < 120; index++) {
            Files.writeString(sources.resolve("Broken" + index + ".java"), "class Broken" + index + " { int field = ; }");
        }
        SourceValidation validation = new SourceCacheValidator().validate(sources, jar, Cancellation.none());
        assertEquals(SourceValidationStatus.INVALID, validation.status());
        assertEquals(120, validation.diagnostics().size());
        for (int index = 0; index < 120; index++) {
            String file = "Broken" + index + ".java";
            assertTrue(validation.diagnostics().stream().anyMatch(message -> message.contains(file)), file);
        }
        assertEquals(121, validation.parsedUnits().size());
    }

    @Test
    void acceptsSyntaxADecompilerLeavesInsideABody() throws Exception {
        Path sources = sources(Map.of("sample/Encoder.java", "package sample; public class Encoder { void before() {} void run(Object value, int index) { switch (index) { case 0: return; default: break; } } void after() {} }"));
        Path jar = compile(sources, "-g:source");
        Files.writeString(sources.resolve("sample/Encoder.java"), "package sample; public class Encoder { void before() {} void run(Object value, int index) { switch (SwitchBootstraps.typeSwitch<\"typeSwitch\",String,Integer>(value, index)) { case 0: return; default: break; } } void after() {} }");

        SourceValidation validation = new SourceCacheValidator().validate(sources, jar, Cancellation.none());

        assertEquals(SourceValidationStatus.VALID, validation.status(), validation.diagnostics().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"package sample; public class Encoder { void run(<VAR_NAMELESS_ENCLOSURE>) { } }", "package sample; public class Encoder { void run() { int value = 1;", "package sample; public class Encoder { void run() { } } void stray() { } }"})
    void rejectsSyntaxThatDamagesADeclarationOrEndsTheUnitEarly(String damaged) throws Exception {
        Path sources = sources(Map.of("sample/Encoder.java", "package sample; public class Encoder { void run() { } }"));
        Path jar = compile(sources, "-g:source");
        Files.writeString(sources.resolve("sample/Encoder.java"), damaged);

        SourceValidation validation = new SourceCacheValidator().validate(sources, jar, Cancellation.none());

        assertEquals(SourceValidationStatus.INVALID, validation.status());
        assertTrue(validation.diagnostics().stream().anyMatch(message -> message.contains("Encoder.java")), validation.diagnostics().toString());
    }

    @Test
    void rejectsMissingSourceFileAndWrongTopLevelDeclaration() throws Exception {
        Path sources = sources(Map.of("A.java", "class A {}"));
        Path jar = compile(sources, "-g:none");
        SourceValidation unknown = new SourceCacheValidator().validate(sources, jar, Cancellation.none());
        assertEquals(SourceValidationStatus.UNVERIFIED_COMPLETENESS, unknown.status());
        assertTrue(unknown.diagnostics().stream().anyMatch(value -> value.contains("SourceFile")));
        jar = compile(sources, "-g:source");
        Files.writeString(sources.resolve("A.java"), "class Different {}");
        SourceValidation mismatch = new SourceCacheValidator().validate(sources, jar, Cancellation.none());
        assertEquals(SourceValidationStatus.INVALID, mismatch.status());
        assertTrue(mismatch.diagnostics().contains("Missing top-level declaration A in A.java"));
    }

    @Test
    void rejectsAmbiguousNestedSourceOwnership() throws Exception {
        Path sources = sources(Map.of("Outer.java", "class Outer { class Inner {} }"));
        Path jar = compile(sources, "-g:source");
        Path changed = temporary.resolve("ambiguous.jar");
        try (JarFile input = new JarFile(jar.toFile());
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(changed))) {
            for (var entry : input.stream().toList()) {
                byte[] bytes;
                try (var stream = input.getInputStream(entry)) {
                    bytes = stream.readAllBytes();
                }
                if (entry.getName().equals("Outer$Inner.class")) {
                    bytes = ClassFile.of().transformClass(ClassFile.of().parse(bytes), (builder, element) -> {
                        if (element instanceof SourceFileAttribute) {
                            builder.with(SourceFileAttribute.of("Other.java"));
                        }
                        else {
                            builder.with(element);
                        }
                    });
                }
                output.putNextEntry(new JarEntry(entry.getName()));
                output.write(bytes);
                output.closeEntry();
            }
        }
        Files.writeString(sources.resolve("Other.java"), "class Other {}");
        SourceValidation validation = new SourceCacheValidator().validate(sources, changed, Cancellation.none());
        assertEquals(SourceValidationStatus.UNVERIFIED_COMPLETENESS, validation.status());
        assertTrue(validation.diagnostics().stream().anyMatch(value -> value.contains("Unverified declaring compilation unit")));
    }

    @Test
    void externalEditsMarkersAndEmptyDirectoriesNeverAcquireGeneratedOwnership() throws Exception {
        Path sources = sources(Map.of("A.java", "class A {}"));
        Path jar = compile(sources, "-g:source");
        SourceInputIdentity inputs = SourceInputIdentity.capture(jar, List.of(), Cancellation.none());
        SourceTreeInventory initial = SourceTreeInventory.capture(sources, Cancellation.none());
        SourceValidation validation = new SourceCacheValidator().validate(sources, jar, Cancellation.none());
        var producer = new SourceProducerIdentity("test-producer", "obsolete-version", inputs.remappedJar(), Map.of(), "no resources");
        SourcePreparationStamp generated = SourceProvenance.generated(inputs, initial, validation, producer);
        assertEquals(SourceSelection.REUSE, SourceProvenance.select(initial, validation, generated, inputs, false));
        assertEquals(producer, SourceProvenance.observed(inputs, initial, validation, generated).producer());
        SourceValidation invalid = new SourceValidation(SourceValidationStatus.INVALID, List.of("invalid output"), validation.requiredUnits(), validation.parsedUnits());
        Files.writeString(sources.resolve("marker.txt"), "user marker");
        assertEquals(SourceSelection.REQUIRE_REFRESH, SourceProvenance.select(SourceTreeInventory.capture(sources, Cancellation.none()), invalid, generated, inputs, false));
        Files.delete(sources.resolve("marker.txt"));
        Files.createDirectory(sources.resolve("empty"));
        assertEquals(SourceSelection.REQUIRE_REFRESH, SourceProvenance.select(SourceTreeInventory.capture(sources, Cancellation.none()), invalid, generated, inputs, false));
        Files.delete(sources.resolve("empty"));
        Files.writeString(sources.resolve("A.java"), "class A { int userEdit; }");
        SourceTreeInventory edited = SourceTreeInventory.capture(sources, Cancellation.none());
        assertNotEquals(initial, edited);
        SourceValidation validEdit = new SourceCacheValidator().validate(sources, jar, Cancellation.none());
        SourcePreparationStamp external = SourceProvenance.observed(inputs, edited, validEdit, generated);
        assertEquals(SourceOwnership.VALIDATED_EXTERNAL, external.ownership());
        assertNull(external.producer());
        assertEquals(SourceSelection.REUSE, SourceProvenance.select(edited, validEdit, generated, inputs, false));
        Path stampFile = temporary.resolve("source-preparation.json");
        SourceProvenance.write(stampFile, external);
        assertEquals(external, SourceProvenance.read(stampFile).orElseThrow());
        assertEquals(SourceSelection.GENERATE, SourceProvenance.select(initial, invalid, generated, inputs, false));
        assertEquals(SourceSelection.REQUIRE_REFRESH, SourceProvenance.select(edited, invalid, generated, inputs, false));
        assertEquals(SourceSelection.REQUIRE_REFRESH, SourceProvenance.select(edited, invalid, external, inputs, false));
        assertEquals(SourceSelection.GENERATE, SourceProvenance.select(edited, invalid, external, inputs, true));
    }

    @Test
    void decompiledSeparateTopLevelsRemainAccountedToOriginalCompilationUnit() throws Exception {
        Path sources = sources(Map.of("A.java", "class A {} class B { class Nested {} }"));
        Path jar = compile(sources, "-g:source");
        var validator = new SourceCacheValidator();
        assertTrue(validator.validate(sources, jar, Cancellation.none()).valid());
        Path output = temporary.resolve("decompiled");
        new MinecraftDecompiler().decompile(jar, output);
        SourceValidation validation = validator.validate(output, jar, Cancellation.none());
        assertTrue(validation.valid(), validation.diagnostics().toString());
        assertEquals(List.of("A.java", "B.java"), validation.requiredUnits());
    }

    @Test
    void refusesRegularFileAsSourceRootAndHonorsCancellation() throws Exception {
        Path sources = sources(Map.of("A.java", "class A {}"));
        Path jar = compile(sources, "-g:source");
        var validator = new SourceCacheValidator();
        assertThrows(IOException.class, () -> validator.validate(sources.resolve("A.java"), jar, Cancellation.none()));
        assertThrows(InterruptedIOException.class, () -> validator.validate(sources, jar, () -> true));
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void missingAndEmptyRootsHaveDifferentSelection() throws Exception {
        Path sources = sources(Map.of("A.java", "class A {}"));
        Path jar = compile(sources, "-g:source");
        SourceInputIdentity inputs = SourceInputIdentity.capture(jar, List.of(), Cancellation.none());
        Path missing = temporary.resolve("missing");
        var validator = new SourceCacheValidator();
        assertEquals(SourceSelection.GENERATE, SourceProvenance.select(SourceTreeInventory.capture(missing, Cancellation.none()), validator.validate(missing, jar, Cancellation.none()), null, inputs, false));
        Files.createDirectory(missing);
        assertEquals(SourceSelection.REQUIRE_REFRESH, SourceProvenance.select(SourceTreeInventory.capture(missing, Cancellation.none()), validator.validate(missing, jar, Cancellation.none()), null, inputs, false));
    }

    @Test
    void malformedStampIsDistinguishedFromUnsafeFilesystemObject() throws Exception {
        Path stamp = temporary.resolve("source-preparation.json");
        assertTrue(SourceProvenance.read(stamp).isEmpty());
        Files.writeString(stamp, "{malformed");
        assertThrows(InvalidSourceStampException.class, () -> SourceProvenance.read(stamp));
        Files.delete(stamp);
        Files.createDirectory(stamp);
        IOException unsafe = assertThrows(IOException.class, () -> SourceProvenance.read(stamp));
        assertFalse(unsafe instanceof InvalidSourceStampException);
    }

    @Test
    void recordsActualRemapperSourceFileAndDecompilerOutputContract() throws Exception {
        Path source = sources(Map.of("a.java", "public class a {}"));
        Path original = compile(source, "-g:source");
        Path mapping = temporary.resolve("mapping.tiny");
        Files.writeString(mapping, "tiny\t2\t0\tofficial\tnamed\nc\ta\tsample/Example\n");
        Path mapped = temporary.resolve("mapped.jar");
        new MinecraftRemapper(1).remap(original, mapping, mapped);
        try (JarFile jar = new JarFile(mapped.toFile());
             var stream = jar.getInputStream(jar.getJarEntry("sample/Example.class"))) {
            var model = ClassFile.of().parse(stream.readAllBytes());
            assertEquals("a.java", model.findAttribute(Attributes.sourceFile()).orElseThrow().sourceFile().stringValue());
        }
        Path decompiled = temporary.resolve("decompiled");
        new MinecraftDecompiler().decompile(mapped, decompiled);
        SourceValidation validation = new SourceCacheValidator().validate(decompiled, mapped, Cancellation.none());
        assertTrue(validation.valid(), validation.diagnostics().toString());
    }

    private Path sources(Map<String, String> files) throws IOException {
        Path root = temporary.resolve("sources");
        for (var entry : files.entrySet()) {
            Path file = root.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
        }
        return root;
    }

    private Path compile(Path sources, String debug) throws IOException {
        Path classes = Files.createTempDirectory(temporary, "classes");
        List<String> args = new ArrayList<>(List.of("--release", "21", debug, "-d", classes.toString()));
        try (var paths = Files.walk(sources)) {
            paths.filter(path -> path.toString().endsWith(".java")).sorted().map(Path::toString).forEach(args::add);
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
        Path jar = Files.createTempFile(temporary, "input", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
             var paths = Files.walk(classes)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, output);
                output.closeEntry();
            }
        }
        return jar;
    }
}
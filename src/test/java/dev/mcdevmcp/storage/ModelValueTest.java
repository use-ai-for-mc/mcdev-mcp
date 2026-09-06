package dev.mcdevmcp.storage;

import dev.mcdevmcp.storage.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelValueTest {
    @Test
    void mapsOnlySupportedJdkElementKindsToStableH2WireNames() {
        assertEquals("class", ElementKindCodec.wireName(ElementKind.CLASS));
        assertEquals("annotation", ElementKindCodec.wireName(ElementKind.ANNOTATION_TYPE));
        assertEquals(ElementKind.RECORD, ElementKindCodec.fromWireName("record"));
        assertThrows(IllegalArgumentException.class, () -> ElementKindCodec.wireName(ElementKind.METHOD));
    }

    @Test
    void classSymbolsKeepStructuredValuesAndImmutableCollections() {
        var interfaces = new java.util.ArrayList<>(List.of("java.io.Closeable"));
        var symbol = new ClassSymbol(1L, SourceNamespace.FABRIC, Optional.of(new FabricApiVersion("0.120.0")), "net.fabricmc.Test", "net.fabricmc", "Test", ElementKind.CLASS, Optional.empty(), interfaces, Path.of("Test.java"), 0, 10, 1, 2);

        interfaces.add("java.lang.Runnable");
        assertEquals(List.of("java.io.Closeable"), symbol.interfaceBinaryNames());
        assertThrows(UnsupportedOperationException.class, () -> symbol.interfaceBinaryNames().add("x"));
        assertThrows(IllegalArgumentException.class, () -> new ClassSymbol(2L, SourceNamespace.MINECRAFT, Optional.of(new FabricApiVersion("0.120.0")), "net.minecraft.Test", "net.minecraft", "Test", ElementKind.CLASS, Optional.empty(), List.of(), Path.of("Test.java"), 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ClassSymbol(3L, SourceNamespace.FABRIC, Optional.empty(), "net.fabricmc.Missing", "net.fabricmc", "Missing", ElementKind.CLASS, Optional.empty(), List.of(), Path.of("Missing.java"), 0, 1, 1, 1));
        assertEquals(Set.of(Modifier.PUBLIC), Set.copyOf(Set.of(Modifier.PUBLIC)));
    }

    @Test
    void fabricApiVersionsAreSafeSingleFilesystemComponents() {
        assertEquals("0.120.0", new FabricApiVersion("0.120.0").value());
        assertThrows(IllegalArgumentException.class, () -> new FabricApiVersion("."));
        assertThrows(IllegalArgumentException.class, () -> new FabricApiVersion(".."));
        assertThrows(IllegalArgumentException.class, () -> new FabricApiVersion("0.120.0/escape"));
        assertThrows(IllegalArgumentException.class, () -> new FabricApiVersion("C:\\escape"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ".", "..", "C:escape", "C:\\escape", "../escape", "..\\escape", "//server/share", "\\\\server\\share", "version<", "version>", "version:", "version\"", "version/", "version\\", "version|", "version?", "version*", "version.", "version ", "CON", "prn.txt", "AUX", "nul.log", "COM1", "com9.zip", "LPT1", "lpt9.data", "cOm¹", "cOm¹.txt", "CoM²", "CoM².log", "com³", "com³.zip", "lPt¹", "lPt¹.txt", "LpT²", "LpT².log", "lpt³", "lpt³.zip", "cLoCk$", "ClOcK$.txt", "cOnIn$", "ConIn$.log", "cOnOuT$", "ConOut$.zip", "CON .txt", "COM1 .zip", "control\u0001"})
    void minecraftVersionsRejectNonPortableFilesystemComponents(String value) {
        assertThrows(IllegalArgumentException.class, () -> new MinecraftVersion(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ".", "..", "C:escape", "C:\\escape", "../escape", "..\\escape", "//server/share", "\\\\server\\share", "version<", "version>", "version:", "version\"", "version/", "version\\", "version|", "version?", "version*", "version.", "version ", "CON", "prn.txt", "AUX", "nul.log", "COM1", "com9.zip", "LPT1", "lpt9.data", "cOm¹", "cOm¹.txt", "CoM²", "CoM².log", "com³", "com³.zip", "lPt¹", "lPt¹.txt", "LpT²", "LpT².log", "lpt³", "lpt³.zip", "cLoCk$", "ClOcK$.txt", "cOnIn$", "ConIn$.log", "cOnOuT$", "ConOut$.zip", "CON .txt", "COM1 .zip", "control\u0001"})
    void fabricApiVersionsRejectNonPortableFilesystemComponents(String value) {
        assertThrows(IllegalArgumentException.class, () -> new FabricApiVersion(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.21.5", "0.120.0+1.21.5", "1.21.5-pre1", "1_21_5", "версия", "build$5", "version¹"})
    void versionValuesAcceptPortableMinecraftAndFabricForms(String value) {
        assertEquals(value, new MinecraftVersion(value).value());
        assertEquals(value, new FabricApiVersion(value).value());
    }

    @Test
    void memberSymbolsUseImmutableJdkModifierSets() {
        var field = new FieldSymbol(1L, 2L, 0, "field", "int", Set.of(Modifier.PRIVATE), 0, 1, 1, 1);
        var method = new MethodSymbol(3L, 2L, 0, "method", "()V", Optional.empty(), Set.of(Modifier.PUBLIC), false, 0, 1, 1, 1);
        var parameter = new ParameterSymbol(4L, 3L, 0, "parameter", "int", false, 0, 1, 1, 1);

        assertEquals(Set.of(Modifier.PRIVATE), field.modifiers());
        assertEquals(Set.of(Modifier.PUBLIC), method.modifiers());
        assertEquals("parameter", parameter.name());
        assertThrows(UnsupportedOperationException.class, () -> field.modifiers().add(Modifier.PUBLIC));
    }
}

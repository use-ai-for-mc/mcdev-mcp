package dev.mcdevmcp.storage.symbols;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SymbolIndexFilesTest {
    @TempDir
    Path temporaryDirectory;

    private static SymbolIndexData sample() {
        List<SymbolIndexData.PackageRow> packages = List.of(SymbolIndexFixture.minecraftPackage(1, "alpha"), new SymbolIndexData.PackageRow(2, "fabric", "0.102.0+1.21.5", "net.fabricmc.fabric.api"));
        List<SymbolIndexData.TypeRow> types = List.of(SymbolIndexFixture.minecraftType(1, 1, "alpha.Alpha", "Alpha", "class", "alpha.Base", List.of("alpha.Marker", "java.lang.Runnable"), "alpha/Alpha.java", 2, 9), new SymbolIndexData.TypeRow(2, 2, "fabric", "0.102.0+1.21.5", "net.fabricmc.fabric.api.Thing", "Thing", "interface", null, List.of(), "Thing.java", 10, 40, 1, 3));
        List<SymbolIndexData.FieldRow> fields = List.of(new SymbolIndexData.FieldRow(1, 1, 0, "näme \"quoted\"", "java.lang.String", "private,final", 20, 30, 3, 3));
        List<SymbolIndexData.MethodRow> methods = List.of(new SymbolIndexData.MethodRow(1, 1, 0, "Alpha", "()V", null, "public", true, 31, 40, 4, 4), new SymbolIndexData.MethodRow(2, 1, 1, "run", "(I[Ljava/lang/String;)V", "void", "public", false, 41, 60, 5, 6));
        List<SymbolIndexData.ParameterRow> parameters = List.of(new SymbolIndexData.ParameterRow(1, 2, 0, "count", "int", false, 45, 54, 5, 5), new SymbolIndexData.ParameterRow(2, 2, 1, "rest", "String[]", true, 55, 59, 5, 5));
        return SymbolIndexData.of("1.21.5", "C:\\cache\\1.21.5\\client", "0".repeat(64), "2026-07-16T00:00:00.000001Z", packages, types, fields, methods, parameters);
    }

    private Path written() throws IOException {
        Path file = temporaryDirectory.resolve("symbols.jsonl");
        SymbolIndexFiles.write(file, sample());
        return file;
    }

    @Test
    void roundTripsTheHeaderAndEveryRow() throws Exception {
        Path file = written();

        assertEquals(sample(), SymbolIndexFiles.read(file));
        assertEquals(sample().header(), SymbolIndexFiles.readHeader(file));
    }

    @Test
    void writesOneLfTerminatedLinePerRecord() throws Exception {
        String content = Files.readString(written(), StandardCharsets.UTF_8);

        assertEquals(1 + 2 + 2 + 1 + 2 + 2, content.lines().count());
        assertTrue(content.endsWith("\n"));
        assertFalse(content.contains("\r"));
    }

    @Test
    void refusesToOverwriteAnExistingFile() throws Exception {
        Path file = written();

        assertThrows(FileAlreadyExistsException.class, () -> SymbolIndexFiles.write(file, sample()));
    }

    @Test
    void readsTheHeaderOfATruncatedFileButRejectsItsRows() throws Exception {
        Path file = written();
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        lines.removeLast();
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);

        assertEquals(sample().header(), SymbolIndexFiles.readHeader(file));
        IOException failure = assertThrows(IOException.class, () -> SymbolIndexFiles.read(file));
        assertTrue(failure.getMessage().contains("truncated"));
    }

    @Test
    void rejectsRowsBeyondTheHeaderCounts() throws Exception {
        Path file = written();
        Files.writeString(file, "{}\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        IOException failure = assertThrows(IOException.class, () -> SymbolIndexFiles.read(file));
        assertTrue(failure.getMessage().contains("beyond its header counts"));
    }

    @Test
    void rejectsAnUnsupportedFormat() throws Exception {
        Path file = written();
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(content.startsWith("{\"format\":2,"));
        Files.writeString(file, content.replaceFirst("\\{\"format\":2,", "{\"format\":1,"), StandardCharsets.UTF_8);

        IOException failure = assertThrows(IOException.class, () -> SymbolIndexFiles.readHeader(file));
        assertTrue(failure.getMessage().contains("Unsupported symbol index format 1"));
    }

    @Test
    void rejectsEmptyFilesInvalidRecordsAndForeignLineEndings() throws Exception {
        Path empty = Files.createFile(temporaryDirectory.resolve("empty.jsonl"));
        assertTrue(assertThrows(IOException.class, () -> SymbolIndexFiles.readHeader(empty)).getMessage().contains("empty"));

        Path garbage = Files.writeString(temporaryDirectory.resolve("garbage.jsonl"), "not json\n", StandardCharsets.UTF_8);
        assertTrue(assertThrows(IOException.class, () -> SymbolIndexFiles.readHeader(garbage)).getMessage().contains("invalid record"));

        Path missingField = written();
        List<String> lines = new ArrayList<>(Files.readAllLines(missingField, StandardCharsets.UTF_8));
        lines.set(1, "{\"id\":1}");
        Files.writeString(missingField, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        assertTrue(assertThrows(IOException.class, () -> SymbolIndexFiles.read(missingField)).getMessage().contains("invalid record"));

        Path windows = temporaryDirectory.resolve("windows.jsonl");
        Files.writeString(windows, Files.readString(missingField, StandardCharsets.UTF_8).replace("\n", "\r\n"), StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> SymbolIndexFiles.readHeader(windows));
    }
}

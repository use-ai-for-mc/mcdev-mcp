package dev.mcdevmcp.storage.symbols;

import dev.mcdevmcp.storage.model.MinecraftVersion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Builds and edits symbol index files row by row for tests.
 */
public final class SymbolIndexFixture {
    private SymbolIndexFixture() {
    }

    public static void writeEmpty(Path file, MinecraftVersion version, Path sourceRoot, String remappedJarSha256, Instant builtAt) throws IOException {
        write(file, version, sourceRoot, remappedJarSha256, builtAt, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public static void write(Path file, MinecraftVersion version, Path sourceRoot, String remappedJarSha256, Instant builtAt, List<SymbolIndexData.PackageRow> packages, List<SymbolIndexData.TypeRow> types, List<SymbolIndexData.FieldRow> fields, List<SymbolIndexData.MethodRow> methods, List<SymbolIndexData.ParameterRow> parameters) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.deleteIfExists(file);
        SymbolIndexFiles.write(file, SymbolIndexData.of(version.value(), sourceRoot.toString(), remappedJarSha256, builtAt.toString(), packages, types, fields, methods, parameters));
    }

    public static void rewrite(Path file, UnaryOperator<SymbolIndexData> change) throws IOException {
        SymbolIndexData changed = change.apply(SymbolIndexFiles.read(file));
        Files.delete(file);
        SymbolIndexFiles.write(file, changed);
    }

    public static SymbolIndexData withHeader(SymbolIndexData data, UnaryOperator<SymbolIndexData.Header> change) {
        return new SymbolIndexData(change.apply(data.header()), data.packages(), data.types(), data.fields(), data.methods(), data.parameters());
    }

    public static SymbolIndexData.PackageRow minecraftPackage(long id, String name) {
        return new SymbolIndexData.PackageRow(id, "minecraft", null, name);
    }

    public static SymbolIndexData.TypeRow minecraftType(long id, long packageId, String binaryName, String simpleName, String kind, String superclass, List<String> interfaces, String sourcePath, int startLine, int endLine) {
        return new SymbolIndexData.TypeRow(id, packageId, "minecraft", null, binaryName, simpleName, kind, superclass, interfaces, sourcePath, 0, 0, startLine, endLine);
    }
}

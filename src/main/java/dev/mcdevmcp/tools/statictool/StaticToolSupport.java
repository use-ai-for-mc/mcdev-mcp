package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.api.ContentToolResult;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.callgraph.CallgraphRepository;
import dev.mcdevmcp.storage.h2.SymbolRepository;
import dev.mcdevmcp.storage.h2.VersionStateRepository;
import dev.mcdevmcp.storage.model.ClassSymbol;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.SourceNamespace;
import dev.mcdevmcp.support.AppVersion;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

final class StaticToolSupport {
    private final PlatformPaths paths;
    private final VersionStateRepository states;
    private final ConcurrentHashMap<MinecraftVersion, CallgraphRepository> callgraphs = new ConcurrentHashMap<>();
    private volatile MinecraftVersion activeVersion;

    StaticToolSupport(PlatformPaths paths) {
        this.paths = paths;
        states = new VersionStateRepository(paths);
    }

    static String modifiers(Set<Modifier> values) {
        return values.stream().sorted(Comparator.comparing(Enum::ordinal)).map(value -> value.name().toLowerCase(Locale.ROOT)).collect(Collectors.joining(" ", "", values.isEmpty() ? "" : " "));
    }

    static String returnType(String value) {
        return value == null ? "undefined" : value;
    }

    ContentToolResult<Void> execute(String toolName, StaticToolOperation operation) {
        try {
            return operation.run();
        } catch (ExpectedVersionException exception) {
            return ToolResult.text(exception.getMessage());
        } catch (StaticToolException exception) {
            return ToolResult.error("Error executing " + toolName + ": " + exception.getMessage());
        } catch (IOException | SQLException exception) {
            String message = exception.getMessage() == null ? exception.toString() : exception.getMessage();
            return ToolResult.error("Error executing " + toolName + ": " + message);
        }
    }

    MinecraftVersion resolve(MinecraftVersion explicit) {
        if (explicit != null) {
            if (!Files.isDirectory(paths.sourceRoot(explicit))) {
                throw new ExpectedVersionException("Version " + explicit.value() + " not initialized. STOP and ask the USER to run this command in their terminal:\n" + "  java -jar " + AppVersion.executableJarName() + " init -v " + explicit.value() + "\n\n" + "This will download, decompile, and index Minecraft " + explicit.value() + " sources (including callgraph).");
            }
            if (!states.isH2Ready(explicit)) {
                throw new ExpectedVersionException("Version " + explicit.value() + " not indexed. STOP and ask the USER to run this command in their terminal:\n" + "  java -jar " + AppVersion.executableJarName() + " init -v " + explicit.value() + "\n\n" + "This will index Minecraft " + explicit.value() + " sources (including callgraph).");
            }
            return explicit;
        }
        if (activeVersion == null) {
            throw new ExpectedVersionException("""
                                               No Minecraft version is currently set.
                                               
                                               STOP and ask the USER which version they want to use, then call mc_version with action="set".
                                               Or, provide a 'version' parameter in your tool call.
                                               
                                               To see available versions, call mc_version with action="list".
                                               """.stripTrailing());
        }
        return activeVersion;
    }

    void activate(MinecraftVersion version) {
        activeVersion = version;
    }

    Optional<MinecraftVersion> active() {
        return Optional.ofNullable(activeVersion);
    }

    SymbolRepository repository(MinecraftVersion version) {
        return new SymbolRepository(paths.symbolDatabase(version));
    }

    CallgraphRepository callgraphRepository(MinecraftVersion version) {
        return callgraphs.computeIfAbsent(version, value -> new CallgraphRepository(paths.callgraphBundle(value), value));
    }

    boolean indexed(MinecraftVersion version) {
        return states.isH2Ready(version);
    }

    PlatformPaths paths() {
        return paths;
    }

    String fullSource(MinecraftVersion version, ClassSymbol symbol) throws IOException {
        Path root = symbol.namespace() == SourceNamespace.FABRIC ? paths.fabricSourceRoot(symbol.fabricApiVersion().orElseThrow()) : paths.sourceRoot(version);
        Path relative;
        try {
            relative = symbol.sourcePath().normalize();
        } catch (RuntimeException exception) {
            throw new StaticToolException("Unsafe indexed source path: " + symbol.sourcePath());
        }
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new StaticToolException("Unsafe indexed source path: " + symbol.sourcePath());
        }
        Path resolvedRoot = root.toRealPath();
        Path file = root.resolve(relative).toRealPath();
        if (!file.startsWith(resolvedRoot) || !Files.isRegularFile(file)) {
            throw new StaticToolException("Unsafe indexed source path: " + symbol.sourcePath());
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

}

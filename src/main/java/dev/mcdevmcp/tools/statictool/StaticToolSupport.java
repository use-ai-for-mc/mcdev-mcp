package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.api.ContentToolResult;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.callgraph.CallgraphRepository;
import dev.mcdevmcp.storage.symbols.SymbolRepository;
import dev.mcdevmcp.storage.symbols.VersionStateRepository;
import dev.mcdevmcp.storage.migration.VersionOperationLease;
import dev.mcdevmcp.storage.model.ClassSymbol;
import dev.mcdevmcp.storage.model.MethodSymbol;
import dev.mcdevmcp.storage.model.ParameterSymbol;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.SourceNamespace;
import dev.mcdevmcp.storage.model.VersionState;
import dev.mcdevmcp.support.AppVersion;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

final class StaticToolSupport {
    private static final int SIMILAR_CLASS_LIMIT = 10;

    private final PlatformPaths paths;
    private final VersionStateRepository states;
    private final Runnable beforeSourceRead;
    private volatile MinecraftVersion activeVersion;

    StaticToolSupport(PlatformPaths paths) {
        this(paths, () -> {
        });
    }

    StaticToolSupport(PlatformPaths paths, Runnable beforeSourceRead) {
        this.paths = paths;
        this.beforeSourceRead = Objects.requireNonNull(beforeSourceRead, "beforeSourceRead");
        states = new VersionStateRepository(paths);
    }

    static String modifiers(Set<Modifier> values) {
        return values.stream().sorted(Comparator.comparing(Enum::ordinal)).map(value -> value.name().toLowerCase(Locale.ROOT)).collect(Collectors.joining(" ", "", values.isEmpty() ? "" : " "));
    }

    /**
     * A method as it is declared: a constructor has no return type and carries the name of its class.
     */
    static String signature(ClassSymbol owner, MethodSymbol method, String parameters) {
        String name = method.constructor() ? owner.simpleName() : method.returnType().orElse("void") + " " + method.name();
        return name + "(" + parameters + ")";
    }

    static String parameters(List<ParameterSymbol> parameters) {
        return parameters.stream().map(parameter -> parameter.type() + " " + parameter.name()).collect(Collectors.joining(", "));
    }

    /**
     * A line naming the indexed classes that share the simple name of a class that was not found, or nothing.
     */
    static String similarClasses(SymbolRepository repository, String className) throws IOException {
        List<ClassSymbol> similar = repository.classesNamedLike(className, SIMILAR_CLASS_LIMIT);
        return similar.isEmpty() ? "" : "\nClasses with that simple name: " + similar.stream().map(ClassSymbol::binaryName).collect(Collectors.joining(", "));
    }

    ContentToolResult<Void> execute(String toolName, StaticToolOperation operation) {
        try {
            return operation.run();
        } catch (ExpectedVersionException exception) {
            return ToolResult.text(exception.getMessage());
        } catch (StaticToolException exception) {
            return ToolResult.error("Error executing " + toolName + ": " + exception.getMessage());
        } catch (IOException exception) {
            String message = exception.getMessage() == null ? exception.toString() : exception.getMessage();
            return ToolResult.error("Error executing " + toolName + ": " + message);
        }
    }

    VersionOperationLease read(MinecraftVersion explicit) throws IOException {
        MinecraftVersion version = explicit == null ? resolveActive() : explicit;
        VersionOperationLease lease = VersionOperationLease.read(paths, version);
        try {
            validateExplicit(explicit, lease);
            return lease;
        } catch (IOException | RuntimeException exception) {
            try {
                lease.close();
            } catch (IOException failure) {
                exception.addSuppressed(failure);
            }
            throw exception;
        }
    }

    private void validateExplicit(MinecraftVersion explicit, VersionOperationLease lease) throws IOException {
        if (explicit != null) {
            if (!Files.isDirectory(lease.boundary().require(lease.resolvedPaths().sourceRoot(explicit)))) {
                throw new ExpectedVersionException("Version " + explicit.value() + " not initialized. STOP and ask the USER to run this command in their terminal:\n" + "  " + AppVersion.commandLine() + " init -v " + explicit.value() + "\n\n" + "This will download, decompile, and index Minecraft " + explicit.value() + " sources (including callgraph).");
            }
            if (state(explicit, lease) != VersionState.READY) {
                throw new ExpectedVersionException("Version " + explicit.value() + " not indexed. STOP and ask the USER to run this command in their terminal:\n" + "  " + AppVersion.commandLine() + " init -v " + explicit.value() + "\n\n" + "This will index Minecraft " + explicit.value() + " sources (including callgraph).");
            }
        }
    }

    private MinecraftVersion resolveActive() {
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

    SymbolRepository repository(VersionOperationLease lease) throws IOException {
        lease.require(paths, lease.version());
        Path database = lease.boundary().require(lease.resolvedPaths().symbolIndex(lease.version()));
        lease.boundary().require(database.resolveSibling(database.getFileName() + ".lock"));
        return new SymbolRepository(database);
    }

    CallgraphRepository callgraphRepository(VersionOperationLease lease) throws IOException {
        lease.require(paths, lease.version());
        return new CallgraphRepository(lease.boundary().require(lease.resolvedPaths().callgraphBundle(lease.version())), lease.version());
    }

    VersionState state(MinecraftVersion version, VersionOperationLease lease) throws IOException {
        return states.state(version, lease);
    }

    PlatformPaths paths() {
        return paths;
    }

    String fullSource(MinecraftVersion version, ClassSymbol symbol) throws IOException {
        try (var lease = VersionOperationLease.read(paths, version)) {
            return fullSource(lease, symbol);
        }
    }

    String fullSource(VersionOperationLease lease, ClassSymbol symbol) throws IOException {
        beforeSourceRead.run();
        lease.require(paths, lease.version());
        PlatformPaths resolvedPaths = lease.resolvedPaths();
        Path root = symbol.namespace() == SourceNamespace.FABRIC ? resolvedPaths.fabricSourceRoot(symbol.fabricApiVersion().orElseThrow()) : resolvedPaths.sourceRoot(lease.version());
        Path relative;
        try {
            relative = symbol.sourcePath().normalize();
        } catch (RuntimeException exception) {
            throw new StaticToolException("Unsafe indexed source path: " + symbol.sourcePath());
        }
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new StaticToolException("Unsafe indexed source path: " + symbol.sourcePath());
        }
        Path resolvedRoot = lease.boundary().require(root).toRealPath();
        Path current = resolvedRoot;
        int depth = 0;
        for (Path component : relative) {
            current = current.resolve(component);
            BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther() || ++depth < relative.getNameCount() && !attributes.isDirectory()) {
                throw new StaticToolException("Unsafe indexed source path: " + symbol.sourcePath());
            }
        }
        Path file = lease.boundary().require(root.resolve(relative)).toRealPath();
        if (!file.startsWith(resolvedRoot) || !Files.isRegularFile(file)) {
            throw new StaticToolException("Unsafe indexed source path: " + symbol.sourcePath());
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

}
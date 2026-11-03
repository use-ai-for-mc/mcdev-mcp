package dev.mcdevmcp.storage.symbols;

import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.migration.VersionOperationLease;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.VersionState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class VersionStateRepository {
    private final PlatformPaths paths;

    public VersionStateRepository(PlatformPaths paths) {
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    private static boolean isReady(Path database) {
        if (!Files.isRegularFile(database)) {
            return false;
        }
        try {
            SymbolIndexFiles.readHeader(database);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean hasLegacyIndex(MinecraftVersion version, VersionOperationLease lease) throws IOException {
        Path root = lease.resolvedPaths().indexRoot(version);
        return Files.isRegularFile(lease.boundary().require(root.resolve("symbols.mv.db"))) || Files.isRegularFile(lease.boundary().require(root.resolve("manifest.json"))) || Files.isDirectory(lease.boundary().require(root.resolve("minecraft"))) || Files.isDirectory(lease.boundary().require(root.resolve("fabric")));
    }

    public VersionState state(MinecraftVersion version) throws IOException {
        try (var lease = VersionOperationLease.read(paths, version)) {
            return state(version, lease);
        }
    }

    public VersionState state(MinecraftVersion version, VersionOperationLease lease) throws IOException {
        lease.require(paths, version);
        PlatformPaths resolvedPaths = lease.resolvedPaths();
        Path database = lease.boundary().require(resolvedPaths.symbolIndex(version));
        lease.boundary().require(database.resolveSibling(database.getFileName() + ".lock"));
        if (isReady(database)) {
            return VersionState.READY;
        }
        if (Files.isRegularFile(database) || hasLegacyIndex(version, lease)) {
            return VersionState.NEEDS_REBUILD;
        }
        if (Files.isDirectory(lease.boundary().require(resolvedPaths.sourceRoot(version)))) {
            return VersionState.SOURCE_ONLY;
        }
        return VersionState.ABSENT;
    }

    public boolean isReady(MinecraftVersion version) throws IOException {
        return state(version) == VersionState.READY;
    }

    public boolean needsRebuild(MinecraftVersion version) throws IOException {
        return state(version) == VersionState.NEEDS_REBUILD;
    }

    public boolean isSourceOnly(MinecraftVersion version) throws IOException {
        return state(version) == VersionState.SOURCE_ONLY;
    }

    public boolean isAbsent(MinecraftVersion version) throws IOException {
        return state(version) == VersionState.ABSENT;
    }
}
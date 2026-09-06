package dev.mcdevmcp.storage.h2;

import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.VersionState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;

public final class VersionStateRepository {
    private final PlatformPaths paths;

    public VersionStateRepository(PlatformPaths paths) {
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    private static boolean isH2Ready(Path database) {
        if (!Files.isRegularFile(database)) {
            return false;
        }
        try {
            new SymbolRepository(database).query(connection -> {
                SymbolSchema.validate(connection);
                return null;
            });
            return true;
        } catch (IOException | SQLException exception) {
            return false;
        }
    }

    public VersionState state(MinecraftVersion version) {
        Path database = paths.symbolDatabase(version);
        if (isH2Ready(database)) {
            return VersionState.READY;
        }
        if (hasLegacyIndex(version)) {
            return VersionState.NEEDS_REBUILD;
        }
        if (Files.isDirectory(paths.sourceRoot(version))) {
            return VersionState.SOURCE_ONLY;
        }
        return VersionState.ABSENT;
    }

    public boolean isH2Ready(MinecraftVersion version) {
        return state(version) == VersionState.READY;
    }

    public boolean needsRebuild(MinecraftVersion version) {
        return state(version) == VersionState.NEEDS_REBUILD;
    }

    public boolean isSourceOnly(MinecraftVersion version) {
        return state(version) == VersionState.SOURCE_ONLY;
    }

    public boolean isAbsent(MinecraftVersion version) {
        return state(version) == VersionState.ABSENT;
    }

    private boolean hasLegacyIndex(MinecraftVersion version) {
        Path root = paths.indexRoot(version);
        return Files.isRegularFile(root.resolve("manifest.json")) || Files.isDirectory(root.resolve("minecraft")) || Files.isDirectory(root.resolve("fabric"));
    }
}
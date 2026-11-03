package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.storage.model.MinecraftVersion;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Identity constraints used while selecting a DebugBridge instance.
 */
value record ExpectedInstance(Optional<MinecraftVersion> version, Optional<Path> gameDirectory) {
    ExpectedInstance {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(gameDirectory, "gameDirectory");
    }

    static ExpectedInstance none() {
        return new ExpectedInstance(Optional.empty(), Optional.empty());
    }
}
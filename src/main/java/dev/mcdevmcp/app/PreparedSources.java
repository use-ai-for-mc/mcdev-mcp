package dev.mcdevmcp.app;

import dev.mcdevmcp.analysis.index.SourceRoot;
import dev.mcdevmcp.storage.model.MinecraftVersion;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record PreparedSources(MinecraftVersion minecraftVersion, List<SourceRoot> sourceRoots, Path obfuscatedJar, Path unobfuscatedJar, Path remappedJar) {
    public PreparedSources {
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        sourceRoots = List.copyOf(Objects.requireNonNull(sourceRoots, "sourceRoots"));
        if (sourceRoots.isEmpty()) {
            throw new IllegalArgumentException("sourceRoots must not be empty");
        }
        obfuscatedJar = normalize(obfuscatedJar, "obfuscatedJar");
        unobfuscatedJar = normalize(unobfuscatedJar, "unobfuscatedJar");
        remappedJar = normalize(remappedJar, "remappedJar");
    }

    private static Path normalize(Path value, String name) {
        return Objects.requireNonNull(value, name).toAbsolutePath().normalize();
    }
}
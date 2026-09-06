package dev.mcdevmcp.storage;

import dev.mcdevmcp.storage.model.FabricApiVersion;
import dev.mcdevmcp.storage.model.MinecraftVersion;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record PlatformPaths(Path cacheRoot) {
    public PlatformPaths {
        cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot").normalize();
    }

    public static PlatformPaths forEnvironment(String osName, Map<String, String> env, Path home) {
        Objects.requireNonNull(osName, "osName");
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(home, "home");
        String normalizedOsName = osName.toLowerCase(Locale.ROOT);
        if (normalizedOsName.contains("mac") || normalizedOsName.contains("darwin")) {
            return new PlatformPaths(home.resolve("Library").resolve("Caches").resolve("mcdev-mcp"));
        }
        if (normalizedOsName.contains("win")) {
            String configuredLocalApplicationData = env.get("LOCALAPPDATA");
            Path localApplicationData = configuredLocalApplicationData == null || configuredLocalApplicationData.isBlank() ? home.resolve("AppData").resolve("Local") : Path.of(configuredLocalApplicationData);
            return new PlatformPaths(localApplicationData.resolve("mcdev-mcp").resolve("Cache"));
        }
        String configuredXdgCache = env.get("XDG_CACHE_HOME");
        Path xdgCache = configuredXdgCache == null || configuredXdgCache.isBlank() ? home.resolve(".cache") : Path.of(configuredXdgCache);
        return new PlatformPaths(xdgCache.resolve("mcdev-mcp"));
    }

    public Path versionCache(MinecraftVersion version) {
        return cacheRoot.resolve("cache").resolve(version.value());
    }

    public Path sourceRoot(MinecraftVersion version) {
        return versionCache(version).resolve("client");
    }

    public Path remappedJar(MinecraftVersion version) {
        return versionCache(version).resolve("jars").resolve(version.value() + "_unobfuscated.jar");
    }

    public Path remappedCallgraphJar(MinecraftVersion version) {
        return versionCache(version).resolve("callgraph").resolve("client-remapped.jar");
    }

    public Path fabricSourceRoot(FabricApiVersion version) {
        return cacheRoot.resolve("cache").resolve("fabric-api-" + version.value());
    }

    public Path symbolDatabase(MinecraftVersion version) {
        return indexRoot(version).resolve("symbols.mv.db");
    }

    public Path callgraphBundle(MinecraftVersion version) {
        return versionCache(version).resolve("indexes").resolve("callgraph");
    }

    public Path indexRoot(MinecraftVersion version) {
        return cacheRoot.resolve("index").resolve(version.value());
    }
}
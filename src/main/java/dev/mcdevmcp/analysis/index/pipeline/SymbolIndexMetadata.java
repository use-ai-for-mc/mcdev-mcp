package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.storage.model.MinecraftVersion;

import java.nio.file.Path;
import java.time.Instant;

value record SymbolIndexMetadata(boolean singleton, int schemaVersion, MinecraftVersion minecraftVersion, Path sourceRoot, String remappedJarSha256, Instant builtAt) {
}
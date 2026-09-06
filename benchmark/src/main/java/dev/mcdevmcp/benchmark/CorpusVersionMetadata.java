package dev.mcdevmcp.benchmark;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.mcdevmcp.storage.model.MinecraftVersion;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record CorpusVersionMetadata(MinecraftVersion id, List<CorpusOfficialLibrary> libraries) {
}
package dev.mcdevmcp.benchmark;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.mcdevmcp.storage.model.MinecraftVersion;

import java.net.URI;

@JsonIgnoreProperties(ignoreUnknown = true)
record CorpusOfficialVersion(MinecraftVersion id, URI url, String sha1) {
}
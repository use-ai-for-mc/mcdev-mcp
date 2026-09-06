package dev.mcdevmcp.benchmark;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.net.URI;

@JsonIgnoreProperties(ignoreUnknown = true)
record CorpusOfficialArtifact(String path, Long size, String sha1, URI url) {
}
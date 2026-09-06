package dev.mcdevmcp.benchmark;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


@JsonIgnoreProperties(ignoreUnknown = true)
record CorpusOfficialDownloads(CorpusOfficialArtifact artifact) {
}
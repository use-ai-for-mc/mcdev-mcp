package dev.mcdevmcp.benchmark;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record CorpusGlobalMetadata(List<CorpusOfficialVersion> versions) {
}
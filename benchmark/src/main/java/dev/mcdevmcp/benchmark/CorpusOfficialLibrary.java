package dev.mcdevmcp.benchmark;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


@JsonIgnoreProperties(ignoreUnknown = true)
record CorpusOfficialLibrary(String name, CorpusOfficialDownloads downloads) {
}
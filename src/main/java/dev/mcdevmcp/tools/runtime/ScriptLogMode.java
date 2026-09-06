package dev.mcdevmcp.tools.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;

enum ScriptLogMode {
    @JsonProperty("errors") ERRORS, @JsonProperty("stats") STATS, @JsonProperty("paths") PATHS
}
package dev.mcdevmcp.tools.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mcdevmcp.mcp.tool.api.InputProperty;

record ScriptLogsArguments(@InputProperty(description = "What to show: 'errors' (recent failures), 'stats' (error patterns), 'paths' (file locations)", defaultValue = "errors") ScriptLogMode mode, @InputProperty(minimum = "1", description = "Number of entries to show (for 'errors' mode). Default: 20", defaultValue = "20") int limit) {
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    ScriptLogsArguments(@JsonProperty("mode") ScriptLogMode mode, @JsonProperty("limit") Integer limit) {
        this(mode, limit == null ? 0 : limit);
    }

    public ScriptLogsArguments {
        if (mode == null) {
            mode = ScriptLogMode.ERRORS;
        }
        if (limit == 0) {
            limit = 20;
        }
        if (limit < 0) {
            throw new IllegalArgumentException("'limit' must be at least 1");
        }
    }
}

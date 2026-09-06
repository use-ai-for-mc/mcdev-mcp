package dev.mcdevmcp.tools.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mcdevmcp.mcp.tool.api.InputProperty;

record ChatHistoryArguments(@InputProperty(description = "Max messages returned. Default 50.", minimum = "0", defaultValue = "50") Integer limit, @InputProperty(description = "Include the Component as JSON for each message. Default false.", defaultValue = "false") boolean includeJson) {
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    static ChatHistoryArguments fromJson(@JsonProperty("limit") Integer limit, @JsonProperty("includeJson") Boolean includeJson) {
        return new ChatHistoryArguments(limit, includeJson != null && includeJson);
    }

    ChatHistoryArguments {
        if (limit != null && limit < 0) {
            throw new IllegalArgumentException("'limit' must be non-negative");
        }
    }
}

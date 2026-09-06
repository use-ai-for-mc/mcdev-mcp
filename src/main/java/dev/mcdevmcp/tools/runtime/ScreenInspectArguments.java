package dev.mcdevmcp.tools.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mcdevmcp.mcp.tool.api.InputProperty;

record ScreenInspectArguments(@InputProperty(description = "Render each unique item's icon and attach as an icons map. Default false.", defaultValue = "false") boolean includeIcons) {
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    static ScreenInspectArguments fromJson(@JsonProperty("includeIcons") Boolean includeIcons) {
        return new ScreenInspectArguments(includeIcons != null && includeIcons);
    }
}
package dev.mcdevmcp.tools.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mcdevmcp.mcp.tool.api.InputProperty;

record NearbyEntitiesArguments(@InputProperty(description = "Search radius in blocks. Default 64.", minimum = "0", defaultValue = "64") Double range, @InputProperty(description = "Max entries returned. Default 100.", minimum = "0", defaultValue = "100") Integer limit, @InputProperty(description = "Render each unique primaryEquipment item's icon. Default false.", defaultValue = "false") boolean includeIcons) {
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    static NearbyEntitiesArguments fromJson(@JsonProperty("range") Double range, @JsonProperty("limit") Integer limit, @JsonProperty("includeIcons") Boolean includeIcons) {
        return new NearbyEntitiesArguments(range, limit, includeIcons != null && includeIcons);
    }

    NearbyEntitiesArguments {
        if (range != null && (!Double.isFinite(range) || range < 0)) {
            throw new IllegalArgumentException("'range' must be a finite non-negative number");
        }
        if (limit != null && limit < 0) {
            throw new IllegalArgumentException("'limit' must be non-negative");
        }
    }
}

package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.mcp.tool.api.InputProperty;

record NearbyBlocksArguments(@InputProperty(description = "Search radius in blocks. Default 16.", minimum = "0", defaultValue = "16") Double range, @InputProperty(description = "Max entries returned. Default 100.", minimum = "0", defaultValue = "100") Integer limit) {
    NearbyBlocksArguments {
        if (range != null && (!Double.isFinite(range) || range < 0)) {
            throw new IllegalArgumentException("'range' must be a finite non-negative number");
        }
        if (limit != null && limit < 0) {
            throw new IllegalArgumentException("'limit' must be non-negative");
        }
    }
}

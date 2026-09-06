package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.mcp.tool.api.InputProperty;

record LookedAtEntityArguments(@InputProperty(description = "Raycast distance in blocks. Default 64.", minimum = "0", defaultValue = "64") Double range) {
    LookedAtEntityArguments {
        if (range != null && (!Double.isFinite(range) || range < 0)) {
            throw new IllegalArgumentException("'range' must be a finite non-negative number");
        }
    }
}

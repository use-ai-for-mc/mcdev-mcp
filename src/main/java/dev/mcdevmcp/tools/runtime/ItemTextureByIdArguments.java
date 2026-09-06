package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.minecraft.ResourceIdentifier;
import dev.mcdevmcp.mcp.tool.api.InputProperty;

record ItemTextureByIdArguments(@InputProperty(required = true, description = "Registry id like \"minecraft:diamond\".") ResourceIdentifier itemId) {
    public ItemTextureByIdArguments {
        if (itemId == null) {
            throw new IllegalArgumentException("'itemId' is required");
        }
    }
}

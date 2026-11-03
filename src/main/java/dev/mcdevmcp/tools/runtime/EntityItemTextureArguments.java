package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.mcp.tool.api.InputProperty;

value record EntityItemTextureArguments(@InputProperty(required = true) int entityId, @InputProperty(required = true) EntityItemSlot slot) {
    public EntityItemTextureArguments {
        if (slot == null) {
            throw new IllegalArgumentException("'slot' is required");
        }
    }
}

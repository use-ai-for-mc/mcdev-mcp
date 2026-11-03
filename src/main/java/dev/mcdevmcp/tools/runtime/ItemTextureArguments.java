package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.mcp.tool.api.InputProperty;

value record ItemTextureArguments(@InputProperty(required = true, description = "Inventory slot index (0-35 main inventory, 36 feet, 37 legs, 38 chest, 39 head, 40 offhand).", minimum = "0", maximum = "40") int slot) {
    public ItemTextureArguments {
        if (slot < 0 || slot > 40) {
            throw new IllegalArgumentException("'slot' must be between 0 and 40");
        }
    }
}

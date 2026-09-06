package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.minecraft.BlockPosition;
import dev.mcdevmcp.mcp.tool.api.InputProperty;

import java.util.Objects;

record BlockGlowArguments(@InputProperty(required = true) BlockPosition position, @InputProperty(description = "true to highlight, false to remove this position.", required = true) boolean glow) {
    BlockGlowArguments {
        Objects.requireNonNull(position, "position");
    }
}

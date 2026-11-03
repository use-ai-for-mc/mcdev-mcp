package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.mcp.tool.api.InputProperty;

value record EntityGlowArguments(@InputProperty(description = "Entity id from mc_nearby_entities.", required = true) int entityId, @InputProperty(description = "true to outline, false to remove.", required = true) boolean glow) {
}

package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.mcp.tool.api.InputProperty;

value record EntityDetailsArguments(@InputProperty(description = "Entity id from mc_nearby_entities or mc_looked_at_entity.", required = true) int entityId) {
}

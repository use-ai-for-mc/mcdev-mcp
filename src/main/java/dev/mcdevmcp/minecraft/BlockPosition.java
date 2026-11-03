package dev.mcdevmcp.minecraft;

import dev.mcdevmcp.mcp.tool.api.InputProperty;

/**
 * Integer block coordinates accepted by the runtime tools.
 */
public value record BlockPosition(@InputProperty(required = true) int x, @InputProperty(required = true) int y, @InputProperty(required = true) int z) {
}
package dev.mcdevmcp.mcp;

import dev.mcdevmcp.mcp.resource.ResourceCatalog;
import dev.mcdevmcp.mcp.tool.ToolCatalog;

import java.util.Objects;

/**
 * Immutable MCP identity and catalog composition shared by every transport.
 */
public value record ServerDefinition(String name, String version, String instructions, ToolCatalog tools, ResourceCatalog resources) {
    public ServerDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(instructions, "instructions");
        Objects.requireNonNull(tools, "tools");
        Objects.requireNonNull(resources, "resources");
    }
}
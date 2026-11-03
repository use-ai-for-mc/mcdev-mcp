package dev.mcdevmcp.mcp.tool.api;

value record ScalarInput(@InputProperty(required = true) WireVersion version, @InputProperty(required = true) WireMode mode) {
}
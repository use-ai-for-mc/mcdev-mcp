package dev.mcdevmcp.mcp.tool.api;

record ScalarInput(@InputProperty(required = true) WireVersion version, @InputProperty(required = true) WireMode mode) {
}
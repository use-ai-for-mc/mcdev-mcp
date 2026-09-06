module dev.mcdevmcp.mcp.tool.api.smoke {
    requires dev.mcdevmcp.mcp.tool.api;
    requires io.modelcontextprotocol.sdk.mcp.json.jackson3;

    opens dev.mcdevmcp.mcp.tool.api.smoke to tools.jackson.databind;
}

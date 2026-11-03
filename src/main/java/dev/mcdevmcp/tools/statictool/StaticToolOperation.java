package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.api.ContentToolResult;

import java.io.IOException;

@FunctionalInterface
interface StaticToolOperation {
    ContentToolResult<Void> run() throws IOException;
}

package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.BlockingToolHandler;

final class McConnectTool {
    static final ToolDeclaration<ConnectArguments> DECLARATION = ToolDeclaration.of("mc_connect", ConnectArguments.class);

    private McConnectTool() {
    }

    static BlockingToolHandler<ConnectArguments> handler(RuntimeToolSupport support) {
        return (arguments, _) -> support.connect(arguments);
    }
}
package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.mcp.tool.api.ContentToolBinding;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;

final class McConnectTool {
    static final ToolDeclaration<ConnectArguments> DECLARATION = ToolDeclaration.of("mc_connect", ConnectArguments.class);

    private McConnectTool() {
    }

    static ContentToolBinding<ConnectArguments> binding(RuntimeToolSupport support) {
        return DECLARATION.bind((arguments, _) -> support.connect(arguments));
    }
}

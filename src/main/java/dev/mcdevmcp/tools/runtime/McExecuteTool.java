package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.BlockingToolHandler;

final class McExecuteTool {
    static final ToolDeclaration<ExecuteArguments> DECLARATION = ToolDeclaration.of("mc_execute", ExecuteArguments.class);

    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("execute");

    private McExecuteTool() {
    }

    static BlockingToolHandler<ExecuteArguments> handler(RuntimeToolSupport support, ScriptLogger scriptLogger, boolean scriptLogsEnabled) {
        return (arguments, _) -> support.execute(arguments, scriptLogger, scriptLogsEnabled);
    }
}

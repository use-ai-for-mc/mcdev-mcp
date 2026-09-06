package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ContentToolBinding;

final class McExecuteTool {
    static final ToolDeclaration<ExecuteArguments> DECLARATION = ToolDeclaration.of("mc_execute", ExecuteArguments.class);

    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("execute");

    private McExecuteTool() {
    }

    static ContentToolBinding<ExecuteArguments> binding(RuntimeToolSupport support, ScriptLogger scriptLogger, boolean scriptLogsEnabled) {
        return DECLARATION.bind((arguments, _) -> support.execute(arguments, scriptLogger, scriptLogsEnabled));
    }
}

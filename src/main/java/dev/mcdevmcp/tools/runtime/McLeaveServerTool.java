package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ContentToolBinding;
import dev.mcdevmcp.mcp.tool.api.ContentToolResult;
import dev.mcdevmcp.mcp.tool.api.ToolHandlers;
import dev.mcdevmcp.mcp.tool.api.ToolResult;


final class McLeaveServerTool {
    static final ToolDeclaration<RuntimeEmptyArguments> DECLARATION = ToolDeclaration.of("mc_leave_server", RuntimeEmptyArguments.class);

    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("disconnect");

    private McLeaveServerTool() {
    }

    static ContentToolBinding<RuntimeEmptyArguments> binding(RuntimeToolSupport runtime, SessionControlSupport sessionControl) {
        return DECLARATION.bind((_, _) -> SessionControlSupport.recoverTool(SessionControlSupport.composeCancellable(sessionControl.checkSessionControlEnabled(), disabled -> {
            if (disabled != null) {
                return ToolHandlers.completed(ToolResult.error(disabled));
            }
            return SessionControlSupport.mapCancellable(sessionControl.send(ENDPOINT, RuntimeToolSupport.EMPTY_PAYLOAD, null), response -> {
                ContentToolResult<Void> failure = RuntimeToolSupport.declaredFailure(response);
                return failure == null ? ToolResult.text("Disconnect queued: " + safeResult(runtime, response)) : failure;
            });
        })));
    }

    static String safeResult(RuntimeToolSupport runtime, dev.mcdevmcp.bridge.BridgeResponse response) {
        return response.resultPresent() ? runtime.prettyJson(response.result()) : "undefined";
    }
}

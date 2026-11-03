package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.bridge.BridgeResponse;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.BlockingToolHandler;
import dev.mcdevmcp.mcp.tool.api.ContentToolResult;
import dev.mcdevmcp.mcp.tool.api.ToolResult;

import java.io.IOException;

final class McLeaveServerTool {
    static final ToolDeclaration<RuntimeEmptyArguments> DECLARATION = ToolDeclaration.of("mc_leave_server", RuntimeEmptyArguments.class);

    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("disconnect");

    private McLeaveServerTool() {
    }

    static BlockingToolHandler<RuntimeEmptyArguments> handler(RuntimeToolSupport runtime, SessionControlSupport sessionControl) {
        return (_, _) -> {
            try {
                String disabled = sessionControl.checkSessionControlEnabled();
                if (disabled != null) {
                    return ToolResult.error(disabled);
                }
                BridgeResponse response = sessionControl.send(ENDPOINT, RuntimeToolSupport.EMPTY_PAYLOAD, null);
                ContentToolResult<Void> failure = RuntimeToolSupport.declaredFailure(response);
                return failure == null ? ToolResult.text("Disconnect queued: " + safeResult(runtime, response)) : failure;
            } catch (IOException | RuntimeException failure) {
                return ToolResult.error(SessionControlSupport.message(failure));
            }
        };
    }

    static String safeResult(RuntimeToolSupport runtime, BridgeResponse response) {
        return response.resultPresent() ? runtime.prettyJson(response.result()) : "undefined";
    }
}

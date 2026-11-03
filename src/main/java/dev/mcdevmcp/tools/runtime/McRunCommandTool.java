package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.bridge.BridgeResponse;
import dev.mcdevmcp.bridge.payload.RunCommandPayload;
import dev.mcdevmcp.mcp.tool.ToolAvailability;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.BlockingToolHandler;
import dev.mcdevmcp.mcp.tool.api.ContentToolResult;
import dev.mcdevmcp.mcp.tool.api.ToolResult;

import java.io.IOException;

final class McRunCommandTool {
    static final ToolDeclaration<RunCommandArguments> DECLARATION = ToolDeclaration.of("mc_run_command", RunCommandArguments.class, ToolAvailability.RUN_COMMAND);

    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("runCommand");

    private McRunCommandTool() {
    }

    static BlockingToolHandler<RunCommandArguments> handler(RuntimeToolSupport runtime, SessionControlSupport sessionControl) {
        return (arguments, _) -> {
            try {
                BridgeResponse response = sessionControl.send(ENDPOINT, new RunCommandPayload(stripSlash(arguments.command())), null);
                ContentToolResult<Void> failure = RuntimeToolSupport.declaredFailure(response);
                return failure == null ? ToolResult.text(runtime.prettyJson(RuntimeToolSupport.requireResult(ENDPOINT, response))) : failure;
            } catch (IOException | RuntimeException failure) {
                return ToolResult.error(SessionControlSupport.message(failure));
            }
        };
    }

    private static String stripSlash(String command) {
        return command.startsWith("/") ? command.substring(1) : command;
    }
}

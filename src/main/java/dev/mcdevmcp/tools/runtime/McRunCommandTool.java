package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.bridge.payload.RunCommandPayload;
import dev.mcdevmcp.mcp.tool.ToolAvailability;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ContentToolBinding;
import dev.mcdevmcp.mcp.tool.api.ContentToolResult;
import dev.mcdevmcp.mcp.tool.api.ToolResult;

final class McRunCommandTool {
    static final ToolDeclaration<RunCommandArguments> DECLARATION = ToolDeclaration.of("mc_run_command", RunCommandArguments.class, ToolAvailability.RUN_COMMAND);

    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("runCommand");

    private McRunCommandTool() {
    }

    static ContentToolBinding<RunCommandArguments> binding(RuntimeToolSupport runtime, SessionControlSupport sessionControl) {
        return DECLARATION.bind((arguments, _) -> SessionControlSupport.recoverTool(SessionControlSupport.mapCancellable(sessionControl.send(ENDPOINT, new RunCommandPayload(stripSlash(arguments.command())), null), response -> {
            ContentToolResult<Void> failure = RuntimeToolSupport.declaredFailure(response);
            if (failure != null) {
                return failure;
            }
            return ToolResult.text(runtime.prettyJson(RuntimeToolSupport.requireResult(ENDPOINT, response)));
        })));
    }

    private static String stripSlash(String command) {
        return command.startsWith("/") ? command.substring(1) : command;
    }
}

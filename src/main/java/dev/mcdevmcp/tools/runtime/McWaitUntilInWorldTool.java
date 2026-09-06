package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ContentToolBinding;
import dev.mcdevmcp.mcp.tool.api.ContentToolResult;
import dev.mcdevmcp.mcp.tool.api.ToolResult;

final class McWaitUntilInWorldTool {
    static final ToolDeclaration<WaitUntilInWorldArguments> DECLARATION = ToolDeclaration.of("mc_wait_until_in_world", WaitUntilInWorldArguments.class);

    private McWaitUntilInWorldTool() {
    }

    static ContentToolBinding<WaitUntilInWorldArguments> binding(SessionControlSupport support) {
        return DECLARATION.bind((arguments, cancellation) -> SessionControlSupport.recoverTool(SessionControlSupport.mapCancellable(support.waitUntilInWorld(arguments.timeoutSeconds(), arguments.requireAbsenceFirst(), cancellation), McWaitUntilInWorldTool::render)));
    }

    private static ContentToolResult<Void> render(InWorldWaitResult outcome) {
        String seconds = RuntimeToolSupport.nodeNumber(outcome.elapsedSeconds());
        return switch (outcome.state()) {
            case JOINED -> ToolResult.text("In-world after " + seconds + "s.");
            case FAILED -> ToolResult.error("Join failed — DisconnectedScreen shown.\nReason: " + outcome.reason());
            case TIMEOUT ->
                    ToolResult.error("Not in-world after " + seconds + "s and no DisconnectedScreen. Use mc_screen_inspect to see what screen the client is on.");
        };
    }
}

package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.bridge.BridgeResponse;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.*;

import java.io.IOException;
import java.util.Locale;

final class McQuitClientTool {
    static final ToolDeclaration<QuitClientArguments> DECLARATION = ToolDeclaration.of("mc_quit_client", QuitClientArguments.class);

    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("quit");

    private McQuitClientTool() {
    }

    static BlockingToolHandler<QuitClientArguments> handler(SessionControlSupport support) {
        return (arguments, cancellation) -> {
            try {
                String disabled = support.checkSessionControlEnabled();
                if (disabled != null) {
                    return ToolResult.error(disabled);
                }
                int port = (int) support.connectedPort().orElse(-1);
                boolean wait = arguments.waitForExit() && port > 0;
                Long pid = wait ? support.resolveListeningPid(port) : null;
                ContentToolResult<Void> refused = quit(support);
                if (refused != null) {
                    return refused;
                }
                support.disconnect();
                if (!wait) {
                    return ToolResult.text("Quit queued — the client is shutting down. Use mc_wait_for_bridge after relaunching to reconnect.");
                }
                return renderExit(support.waitForClientExit(port, pid, arguments.timeoutSeconds(), cancellation), port, pid, arguments);
            } catch (IOException | RuntimeException failure) {
                return ToolResult.error(SessionControlSupport.message(failure));
            }
        };
    }

    /**
     * Returns the bridge's declared failure, or null once the quit is acknowledged,
     * including by the client closing the connection while it shuts down.
     */
    private static ContentToolResult<Void> quit(SessionControlSupport support) throws IOException, InterruptedException {
        BridgeResponse response;
        try {
            response = support.send(ENDPOINT, RuntimeToolSupport.EMPTY_PAYLOAD, null);
        } catch (IOException | RuntimeException failure) {
            String lower = SessionControlSupport.message(failure).toLowerCase(Locale.ROOT);
            if (lower.contains("connection closed") || lower.contains("peer closed") || lower.contains("debugbridge closed")) {
                return null;
            }
            throw failure;
        }
        return RuntimeToolSupport.declaredFailure(response);
    }

    private static ContentToolResult<Void> renderExit(ClientExitResult result, int port, Long pid, QuitClientArguments arguments) {
        String timeout = RuntimeToolSupport.nodeNumber(arguments.timeoutSeconds());
        return switch (result) {
            case ClientExitResult.Timeout(ClientExitResult.Phase waitingOn) -> waitingOn == ClientExitResult.Phase.PORT ? ToolResult.error("Quit was acknowledged but port " + port + " is still listening after " + timeout + "s. The game may be stuck on a save/exit prompt — ask the user to close it manually before relaunching.") : ToolResult.error("Port " + port + " closed but the client process (PID " + pid + ") is still running after " + timeout + "s — it's likely still finishing shutdown, or hung. Wait for it to exit (kill -0 " + pid + ") before relaunching.");
            case ClientExitResult.Exited(boolean pidConfirmed) -> pidConfirmed ? ToolResult.text("Client shut down — port " + port + " closed and process " + pid + " exited. Safe to relaunch immediately; use mc_wait_for_bridge to reconnect afterwards.") : ToolResult.text("Client shut down — port " + port + " closed. Couldn't resolve the client PID to also confirm process exit, and the JVM can outlive the port by a few seconds — if the launcher tracks the instance (Prism ignores --launch while the old process lives), confirm it exited (pgrep / kill -0) before relaunching. Use mc_wait_for_bridge to reconnect afterwards.");
        };
    }
}

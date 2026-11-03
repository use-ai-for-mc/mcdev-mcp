package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.bridge.BridgeResponse;
import dev.mcdevmcp.bridge.payload.JoinServerPayload;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.*;

import java.io.IOException;
import java.time.Duration;

final class McJoinServerTool {
    static final ToolDeclaration<JoinServerArguments> DECLARATION = ToolDeclaration.of("mc_join_server", JoinServerArguments.class);

    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("joinServer");
    private static final BridgeEndpoint SNAPSHOT = new BridgeEndpoint("snapshot");

    private McJoinServerTool() {
    }

    static BlockingToolHandler<JoinServerArguments> handler(RuntimeToolSupport runtime, SessionControlSupport sessionControl) {
        return (arguments, cancellation) -> {
            try {
                String disabled = sessionControl.checkSessionControlEnabled();
                if (disabled != null) {
                    return ToolResult.error(disabled);
                }
                boolean requireAbsenceFirst = arguments.waitForWorld() && inWorldBeforeJoin(sessionControl);
                return sendJoin(runtime, sessionControl, arguments, cancellation, requireAbsenceFirst);
            } catch (IOException | RuntimeException failure) {
                return ToolResult.error(SessionControlSupport.message(failure));
            }
        };
    }

    private static boolean inWorldBeforeJoin(SessionControlSupport sessionControl) throws InterruptedException {
        BridgeResponse response;
        try {
            response = sessionControl.send(SNAPSHOT, RuntimeToolSupport.EMPTY_PAYLOAD, null);
        } catch (IOException | RuntimeException _) {
            return false;
        }
        return response.success() && SessionControlSupport.classifyInWorldPoll(response.result(), null) instanceof InWorldPollResult.Joined;
    }

    private static ContentToolResult<Void> sendJoin(RuntimeToolSupport runtime, SessionControlSupport sessionControl, JoinServerArguments arguments, ToolCancellation cancellation, boolean requireAbsenceFirst) throws IOException, InterruptedException {
        BridgeResponse response = sessionControl.send(ENDPOINT, new JoinServerPayload(arguments.address().value(), arguments.acceptResourcePacks()), Duration.ofSeconds(65));
        ContentToolResult<Void> failure = RuntimeToolSupport.declaredFailure(response);
        if (failure != null) {
            return failure;
        }
        if (!arguments.waitForWorld()) {
            return ToolResult.text("Join accepted (connect started): " + McLeaveServerTool.safeResult(runtime, response) + "\nUse mc_wait_until_in_world to confirm the outcome.");
        }
        return renderOutcome(arguments.address().value(), sessionControl.waitUntilInWorld(arguments.timeoutSeconds(), requireAbsenceFirst, cancellation));
    }

    private static ContentToolResult<Void> renderOutcome(String address, InWorldWaitResult outcome) {
        String seconds = RuntimeToolSupport.nodeNumber(outcome.elapsedSeconds());
        return switch (outcome.state()) {
            case JOINED -> ToolResult.text("Joined " + address + " — in-world after " + seconds + "s.");
            case FAILED ->
                    ToolResult.error("Join failed: disconnected from " + address + ".\nReason: " + outcome.reason());
            case TIMEOUT ->
                    ToolResult.error("Still not in-world after " + seconds + "s joining " + address + " (no DisconnectedScreen either — possibly a slow login or resource pack download). Use mc_wait_until_in_world to keep waiting, or mc_screen_inspect to see the current screen.");
        };
    }
}

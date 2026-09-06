package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.bridge.payload.JoinServerPayload;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class McJoinServerTool {
    static final ToolDeclaration<JoinServerArguments> DECLARATION = ToolDeclaration.of("mc_join_server", JoinServerArguments.class);

    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("joinServer");
    private static final BridgeEndpoint SNAPSHOT = new BridgeEndpoint("snapshot");

    private McJoinServerTool() {
    }

    static ContentToolBinding<JoinServerArguments> binding(RuntimeToolSupport runtime, SessionControlSupport sessionControl) {
        return DECLARATION.bind((arguments, cancellation) -> SessionControlSupport.recoverTool(SessionControlSupport.composeCancellable(sessionControl.checkSessionControlEnabled(), disabled -> {
            if (disabled != null) {
                return ToolHandlers.completed(ToolResult.error(disabled));
            }
            CompletionStage<Boolean> absenceGate = arguments.waitForWorld() ? preJoinAbsenceGate(sessionControl) : CompletableFuture.completedFuture(false);
            return SessionControlSupport.composeCancellable(absenceGate, requireAbsenceFirst -> sendJoin(runtime, sessionControl, arguments, cancellation, requireAbsenceFirst));
        })));
    }

    private static CompletionStage<Boolean> preJoinAbsenceGate(SessionControlSupport sessionControl) {
        return SessionControlSupport.handleCancellable(sessionControl.send(SNAPSHOT, RuntimeToolSupport.EMPTY_PAYLOAD, null), (response, failure) -> failure == null && response.success() && SessionControlSupport.classifyInWorldPoll(response.result(), null) instanceof InWorldPollResult.Joined);
    }

    private static CompletionStage<ContentToolResult<Void>> sendJoin(RuntimeToolSupport runtime, SessionControlSupport sessionControl, JoinServerArguments arguments, ToolCancellation cancellation, boolean requireAbsenceFirst) {
        return SessionControlSupport.composeCancellable(sessionControl.send(ENDPOINT, new JoinServerPayload(arguments.address().value(), arguments.acceptResourcePacks()), Duration.ofSeconds(65)), response -> {
            ContentToolResult<Void> failure = RuntimeToolSupport.declaredFailure(response);
            if (failure != null) {
                return ToolHandlers.completed(failure);
            }
            if (!arguments.waitForWorld()) {
                return ToolHandlers.completed(ToolResult.text("Join accepted (connect started): " + McLeaveServerTool.safeResult(runtime, response) + "\nUse mc_wait_until_in_world to confirm the outcome."));
            }
            return SessionControlSupport.mapCancellable(sessionControl.waitUntilInWorld(arguments.timeoutSeconds(), requireAbsenceFirst, cancellation), outcome -> renderOutcome(arguments.address().value(), outcome));
        });
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

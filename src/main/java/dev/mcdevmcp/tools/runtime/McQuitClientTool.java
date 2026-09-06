package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.bridge.BridgeResponse;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.*;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

final class McQuitClientTool {
    static final ToolDeclaration<QuitClientArguments> DECLARATION = ToolDeclaration.of("mc_quit_client", QuitClientArguments.class);

    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("quit");

    private McQuitClientTool() {
    }

    static ContentToolBinding<QuitClientArguments> binding(SessionControlSupport support) {
        return DECLARATION.bind((arguments, cancellation) -> SessionControlSupport.recoverTool(SessionControlSupport.composeCancellable(support.checkSessionControlEnabled(), disabled -> {
            if (disabled != null) {
                return ToolHandlers.completed(ToolResult.error(disabled));
            }
            long port = support.connectedPort().orElse(-1);
            boolean wait = arguments.waitForExit() && port > 0;
            CompletionStage<Long> pid = wait ? support.resolveListeningPid((int) port) : CompletableFuture.completedFuture(null);
            return SessionControlSupport.composeCancellable(pid, resolvedPid -> quit(support, arguments, cancellation, (int) port, wait, resolvedPid));
        })));
    }

    private static CompletionStage<ContentToolResult<Void>> quit(SessionControlSupport support, QuitClientArguments arguments, ToolCancellation cancellation, int port, boolean wait, Long pid) {
        CompletionStage<QuitAck> ack = SessionControlSupport.handleCancellable(support.send(ENDPOINT, RuntimeToolSupport.EMPTY_PAYLOAD, null), McQuitClientTool::classifyAck);
        return SessionControlSupport.composeCancellable(ack, result -> {
            if (result.failure() != null) {
                return ToolHandlers.completed(result.failure());
            }
            support.disconnect();
            if (!wait) {
                return ToolHandlers.completed(ToolResult.text("Quit queued — the client is shutting down. Use mc_wait_for_bridge after relaunching to reconnect."));
            }
            return SessionControlSupport.mapCancellable(support.waitForClientExit(port, pid, arguments.timeoutSeconds(), cancellation), exit -> renderExit(exit, port, pid, arguments));
        });
    }

    private static QuitAck classifyAck(BridgeResponse response, Throwable failure) {
        if (failure != null) {
            String message = failureMessage(failure);
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("connection closed") || lower.contains("peer closed") || lower.contains("debugbridge closed")) {
                return new QuitAck(null);
            }
            throw new CompletionException(failure);
        }
        ContentToolResult<Void> declaredFailure = RuntimeToolSupport.declaredFailure(response);
        return new QuitAck(declaredFailure);
    }

    private static ContentToolResult<Void> renderExit(ClientExitResult result, int port, Long pid, QuitClientArguments arguments) {
        String timeout = RuntimeToolSupport.nodeNumber(arguments.timeoutSeconds());
        if (result instanceof ClientExitResult.Timeout(ClientExitResult.Phase waitingOn)) {
            return waitingOn == ClientExitResult.Phase.PORT ? ToolResult.error("Quit was acknowledged but port " + port + " is still listening after " + timeout + "s. The game may be stuck on a save/exit prompt — ask the user to close it manually before relaunching.") : ToolResult.error("Port " + port + " closed but the client process (PID " + pid + ") is still running after " + timeout + "s — it's likely still finishing shutdown, or hung. Wait for it to exit (kill -0 " + pid + ") before relaunching.");
        }
        ClientExitResult.Exited exited = (ClientExitResult.Exited) result;
        return exited.pidConfirmed() ? ToolResult.text("Client shut down — port " + port + " closed and process " + pid + " exited. Safe to relaunch immediately; use mc_wait_for_bridge to reconnect afterwards.") : ToolResult.text("Client shut down — port " + port + " closed. Couldn't resolve the client PID to also confirm process exit, and the JVM can outlive the port by a few seconds — if the launcher tracks the instance (Prism ignores --launch while the old process lives), confirm it exited (pgrep / kill -0) before relaunching. Use mc_wait_for_bridge to reconnect afterwards.");
    }

    private static String failureMessage(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    private record QuitAck(ContentToolResult<Void> failure) {
    }
}

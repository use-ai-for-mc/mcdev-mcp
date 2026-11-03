package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.SessionInfo;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.BlockingToolHandler;
import dev.mcdevmcp.mcp.tool.api.ContentToolResult;
import dev.mcdevmcp.mcp.tool.api.ToolResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class McWaitForBridgeTool {
    static final ToolDeclaration<WaitForBridgeArguments> DECLARATION = ToolDeclaration.of("mc_wait_for_bridge", WaitForBridgeArguments.class);

    private McWaitForBridgeTool() {
    }

    static BlockingToolHandler<WaitForBridgeArguments> handler(SessionControlSupport support) {
        return (arguments, cancellation) -> {
            List<String> notes = new ArrayList<>();
            ExpectedInstance expected = expectedInstance(support, arguments);
            FoundBridge found;
            SessionInfo info;
            try {
                found = support.waitForBridge(expected, arguments.timeoutSeconds(), notes, cancellation);
                info = support.adoptPort(found.port());
            } catch (IOException | RuntimeException failure) {
                return ToolResult.error(errorWithNotes(failure, notes));
            }
            return renderSuccess(info, found.port(), notes);
        };
    }

    private static ExpectedInstance expectedInstance(SessionControlSupport support, WaitForBridgeArguments arguments) {
        if (arguments.expectedVersion() != null) {
            return new ExpectedInstance(Optional.of(arguments.expectedVersion()), Optional.empty());
        }
        return support.sessionInfo().map(info -> new ExpectedInstance(Optional.of(info.version()), info.gameDir())).orElseGet(ExpectedInstance::none);
    }

    private static ContentToolResult<Void> renderSuccess(SessionInfo info, int port, List<String> notes) {
        List<String> lines = new ArrayList<>();
        lines.add("Connected: Minecraft " + info.version().value() + " on port " + port + ".");
        info.gameDir().ifPresent(path -> lines.add("Game dir: " + path));
        info.sessionControlEnabled().ifPresent(enabled -> lines.add("Session control: " + (enabled ? "enabled" : "disabled")));
        notes.forEach(note -> lines.add("Note: " + note));
        return ToolResult.text(String.join("\n", lines));
    }

    private static String errorWithNotes(Throwable failure, List<String> notes) {
        String message = SessionControlSupport.message(failure);
        return notes.isEmpty() ? message : message + "\n" + String.join("\n", notes.stream().map(note -> "Note: " + note).toList());
    }
}
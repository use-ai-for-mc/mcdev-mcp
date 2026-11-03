package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.bridge.payload.ChatHistoryPayload;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.BlockingToolHandler;

final class McChatHistoryTool {
    static final ToolDeclaration<ChatHistoryArguments> DECLARATION = ToolDeclaration.of("mc_chat_history", ChatHistoryArguments.class);

    private static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("chatHistory");

    private McChatHistoryTool() {
    }

    static BlockingToolHandler<ChatHistoryArguments> handler(RuntimeToolSupport support) {
        return (arguments, _) -> support.container(ENDPOINT, new ChatHistoryPayload(arguments.limit(), arguments.includeJson()));
    }
}

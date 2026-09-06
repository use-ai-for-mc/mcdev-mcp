package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.bridge.payload.ChatHistoryPayload;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ContentToolBinding;

final class McChatHistoryTool {
    static final ToolDeclaration<ChatHistoryArguments> DECLARATION = ToolDeclaration.of("mc_chat_history", ChatHistoryArguments.class);

    private static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("chatHistory");

    private McChatHistoryTool() {
    }

    static ContentToolBinding<ChatHistoryArguments> binding(RuntimeToolSupport support) {
        return DECLARATION.bind((arguments, _) -> support.container(ENDPOINT, new ChatHistoryPayload(arguments.limit(), arguments.includeJson())));
    }
}

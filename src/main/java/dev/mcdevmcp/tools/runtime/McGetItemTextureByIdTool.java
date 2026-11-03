package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.BlockingToolHandler;

final class McGetItemTextureByIdTool {
    static final ToolDeclaration<ItemTextureByIdArguments> DECLARATION = ToolDeclaration.of("mc_get_item_texture_by_id", ItemTextureByIdArguments.class);

    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("getItemTextureById");

    private McGetItemTextureByIdTool() {
    }

    static BlockingToolHandler<ItemTextureByIdArguments> handler(MediaToolSupport support) {
        return (arguments, _) -> support.itemTextureById(arguments);
    }
}

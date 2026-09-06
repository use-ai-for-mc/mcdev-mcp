package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ContentToolBinding;

final class McGetItemTextureByIdTool {
    static final ToolDeclaration<ItemTextureByIdArguments> DECLARATION = ToolDeclaration.of("mc_get_item_texture_by_id", ItemTextureByIdArguments.class);

    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("getItemTextureById");

    private McGetItemTextureByIdTool() {
    }

    static ContentToolBinding<ItemTextureByIdArguments> binding(MediaToolSupport support) {
        return DECLARATION.bind((arguments, _) -> support.itemTextureById(arguments));
    }
}

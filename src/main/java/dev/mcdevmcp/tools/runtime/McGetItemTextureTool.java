package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ContentToolBinding;

final class McGetItemTextureTool {
    static final ToolDeclaration<ItemTextureArguments> DECLARATION = ToolDeclaration.of("mc_get_item_texture", ItemTextureArguments.class);

    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("getItemTexture");

    private McGetItemTextureTool() {
    }

    static ContentToolBinding<ItemTextureArguments> binding(MediaToolSupport support) {
        return DECLARATION.bind((arguments, _) -> support.itemTexture(arguments));
    }
}

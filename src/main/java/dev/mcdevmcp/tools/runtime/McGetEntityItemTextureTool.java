package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.BlockingToolHandler;

final class McGetEntityItemTextureTool {
    static final ToolDeclaration<EntityItemTextureArguments> DECLARATION = ToolDeclaration.of("mc_get_entity_item_texture", EntityItemTextureArguments.class);

    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("getEntityItemTexture");

    private McGetEntityItemTextureTool() {
    }

    static BlockingToolHandler<EntityItemTextureArguments> handler(MediaToolSupport support) {
        return (arguments, _) -> support.entityItemTexture(arguments);
    }
}

package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ContentToolBinding;

final class McGetEntityItemTextureTool {
    static final ToolDeclaration<EntityItemTextureArguments> DECLARATION = ToolDeclaration.of("mc_get_entity_item_texture", EntityItemTextureArguments.class);

    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("getEntityItemTexture");

    private McGetEntityItemTextureTool() {
    }

    static ContentToolBinding<EntityItemTextureArguments> binding(MediaToolSupport support) {
        return DECLARATION.bind((arguments, _) -> support.entityItemTexture(arguments));
    }
}

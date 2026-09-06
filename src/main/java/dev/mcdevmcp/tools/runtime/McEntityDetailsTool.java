package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.bridge.payload.EntityDetailsPayload;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ContentToolBinding;

final class McEntityDetailsTool {
    static final ToolDeclaration<EntityDetailsArguments> DECLARATION = ToolDeclaration.of("mc_entity_details", EntityDetailsArguments.class);

    private static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("entityDetails");

    private McEntityDetailsTool() {
    }

    static ContentToolBinding<EntityDetailsArguments> binding(RuntimeToolSupport support) {
        return DECLARATION.bind((arguments, _) -> support.container(ENDPOINT, new EntityDetailsPayload(arguments.entityId())));
    }
}

package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.bridge.payload.EntityDetailsPayload;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.BlockingToolHandler;

final class McEntityDetailsTool {
    static final ToolDeclaration<EntityDetailsArguments> DECLARATION = ToolDeclaration.of("mc_entity_details", EntityDetailsArguments.class);

    private static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("entityDetails");

    private McEntityDetailsTool() {
    }

    static BlockingToolHandler<EntityDetailsArguments> handler(RuntimeToolSupport support) {
        return (arguments, _) -> support.container(ENDPOINT, new EntityDetailsPayload(arguments.entityId()));
    }
}

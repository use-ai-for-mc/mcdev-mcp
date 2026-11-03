package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.bridge.payload.NearbyEntitiesPayload;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.BlockingToolHandler;

final class McNearbyEntitiesTool {
    static final ToolDeclaration<NearbyEntitiesArguments> DECLARATION = ToolDeclaration.of("mc_nearby_entities", NearbyEntitiesArguments.class);

    private static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("nearbyEntities");

    private McNearbyEntitiesTool() {
    }

    static BlockingToolHandler<NearbyEntitiesArguments> handler(RuntimeToolSupport support) {
        return (arguments, _) -> support.container(ENDPOINT, new NearbyEntitiesPayload(arguments.range(), arguments.limit(), arguments.includeIcons()));
    }
}

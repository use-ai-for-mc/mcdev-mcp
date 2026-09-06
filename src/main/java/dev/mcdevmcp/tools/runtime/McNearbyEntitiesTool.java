package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.bridge.payload.NearbyEntitiesPayload;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ContentToolBinding;

final class McNearbyEntitiesTool {
    static final ToolDeclaration<NearbyEntitiesArguments> DECLARATION = ToolDeclaration.of("mc_nearby_entities", NearbyEntitiesArguments.class);

    private static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("nearbyEntities");

    private McNearbyEntitiesTool() {
    }

    static ContentToolBinding<NearbyEntitiesArguments> binding(RuntimeToolSupport support) {
        return DECLARATION.bind((arguments, _) -> support.container(ENDPOINT, new NearbyEntitiesPayload(arguments.range(), arguments.limit(), arguments.includeIcons())));
    }
}

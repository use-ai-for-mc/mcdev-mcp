package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.bridge.payload.NearbyBlocksPayload;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ContentToolBinding;

final class McNearbyBlocksTool {
    static final ToolDeclaration<NearbyBlocksArguments> DECLARATION = ToolDeclaration.of("mc_nearby_blocks", NearbyBlocksArguments.class);

    private static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("nearbyBlocks");

    private McNearbyBlocksTool() {
    }

    static ContentToolBinding<NearbyBlocksArguments> binding(RuntimeToolSupport support) {
        return DECLARATION.bind((arguments, _) -> support.container(ENDPOINT, new NearbyBlocksPayload(arguments.range(), arguments.limit())));
    }
}

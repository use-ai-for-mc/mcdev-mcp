package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.bridge.payload.NearbyBlocksPayload;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.BlockingToolHandler;

final class McNearbyBlocksTool {
    static final ToolDeclaration<NearbyBlocksArguments> DECLARATION = ToolDeclaration.of("mc_nearby_blocks", NearbyBlocksArguments.class);

    private static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("nearbyBlocks");

    private McNearbyBlocksTool() {
    }

    static BlockingToolHandler<NearbyBlocksArguments> handler(RuntimeToolSupport support) {
        return (arguments, _) -> support.container(ENDPOINT, new NearbyBlocksPayload(arguments.range(), arguments.limit()));
    }
}

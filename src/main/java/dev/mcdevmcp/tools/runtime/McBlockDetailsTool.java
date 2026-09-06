package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.bridge.payload.BlockDetailsPayload;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ContentToolBinding;

final class McBlockDetailsTool {
    static final ToolDeclaration<BlockDetailsArguments> DECLARATION = ToolDeclaration.of("mc_block_details", BlockDetailsArguments.class);

    private static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("blockDetails");

    private McBlockDetailsTool() {
    }

    static ContentToolBinding<BlockDetailsArguments> binding(RuntimeToolSupport support) {
        return DECLARATION.bind((arguments, _) -> support.container(ENDPOINT, new BlockDetailsPayload(arguments.position().x(), arguments.position().y(), arguments.position().z())));
    }
}

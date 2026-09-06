package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.bridge.payload.SetBlockGlowPayload;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ContentToolBinding;

final class McSetBlockGlowTool {
    static final ToolDeclaration<BlockGlowArguments> DECLARATION = ToolDeclaration.of("mc_set_block_glow", BlockGlowArguments.class);

    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("setBlockGlow");

    private McSetBlockGlowTool() {
    }

    static ContentToolBinding<BlockGlowArguments> binding(MediaToolSupport support) {
        return DECLARATION.bind((arguments, _) -> support.acknowledgement(ENDPOINT, new SetBlockGlowPayload(arguments.position().x(), arguments.position().y(), arguments.position().z(), arguments.glow())));
    }
}

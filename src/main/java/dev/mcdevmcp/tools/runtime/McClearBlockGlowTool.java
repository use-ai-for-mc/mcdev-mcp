package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ContentToolBinding;

final class McClearBlockGlowTool {
    static final ToolDeclaration<RuntimeEmptyArguments> DECLARATION = ToolDeclaration.of("mc_clear_block_glow", RuntimeEmptyArguments.class);

    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("clearBlockGlow");

    private McClearBlockGlowTool() {
    }

    static ContentToolBinding<RuntimeEmptyArguments> binding(MediaToolSupport support) {
        return DECLARATION.bind((_, _) -> support.acknowledgement(ENDPOINT, RuntimeToolSupport.EMPTY_PAYLOAD));
    }
}

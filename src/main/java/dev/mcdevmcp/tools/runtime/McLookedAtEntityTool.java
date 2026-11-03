package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.BlockingToolHandler;

final class McLookedAtEntityTool {
    static final ToolDeclaration<LookedAtEntityArguments> DECLARATION = ToolDeclaration.of("mc_looked_at_entity", LookedAtEntityArguments.class);

    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("lookedAtEntity");

    private McLookedAtEntityTool() {
    }

    static BlockingToolHandler<LookedAtEntityArguments> handler(RuntimeToolSupport support) {
        return (arguments, _) -> support.lookedAtEntity(arguments);
    }
}

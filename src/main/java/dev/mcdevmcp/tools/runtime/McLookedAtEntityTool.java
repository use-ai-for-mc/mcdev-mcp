package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ContentToolBinding;

final class McLookedAtEntityTool {
    static final ToolDeclaration<LookedAtEntityArguments> DECLARATION = ToolDeclaration.of("mc_looked_at_entity", LookedAtEntityArguments.class);

    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("lookedAtEntity");

    private McLookedAtEntityTool() {
    }

    static ContentToolBinding<LookedAtEntityArguments> binding(RuntimeToolSupport support) {
        return DECLARATION.bind((arguments, _) -> support.lookedAtEntity(arguments));
    }
}

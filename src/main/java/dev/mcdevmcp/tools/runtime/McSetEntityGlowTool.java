package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.bridge.payload.SetEntityGlowPayload;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ContentToolBinding;

final class McSetEntityGlowTool {
    static final ToolDeclaration<EntityGlowArguments> DECLARATION = ToolDeclaration.of("mc_set_entity_glow", EntityGlowArguments.class);

    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("setEntityGlow");

    private McSetEntityGlowTool() {
    }

    static ContentToolBinding<EntityGlowArguments> binding(MediaToolSupport support) {
        return DECLARATION.bind((arguments, _) -> support.acknowledgement(ENDPOINT, new SetEntityGlowPayload(arguments.entityId(), arguments.glow())));
    }
}

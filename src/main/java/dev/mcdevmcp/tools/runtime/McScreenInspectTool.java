package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.bridge.payload.ScreenInspectPayload;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ContentToolBinding;

final class McScreenInspectTool {
    static final ToolDeclaration<ScreenInspectArguments> DECLARATION = ToolDeclaration.of("mc_screen_inspect", ScreenInspectArguments.class);

    private static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("screenInspect");

    private McScreenInspectTool() {
    }

    static ContentToolBinding<ScreenInspectArguments> binding(RuntimeToolSupport support) {
        return DECLARATION.bind((arguments, _) -> support.container(ENDPOINT, new ScreenInspectPayload(arguments.includeIcons())));
    }
}

package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ContentToolBinding;

final class McScreenshotTool {
    static final ToolDeclaration<ScreenshotArguments> DECLARATION = ToolDeclaration.of("mc_screenshot", ScreenshotArguments.class);

    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("screenshot");

    private McScreenshotTool() {
    }

    static ContentToolBinding<ScreenshotArguments> binding(MediaToolSupport support) {
        return DECLARATION.bind((arguments, _) -> support.screenshot(arguments));
    }
}

package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.BlockingToolHandler;

final class McScreenshotTool {
    static final ToolDeclaration<ScreenshotArguments> DECLARATION = ToolDeclaration.of("mc_screenshot", ScreenshotArguments.class);

    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("screenshot");

    private McScreenshotTool() {
    }

    static BlockingToolHandler<ScreenshotArguments> handler(MediaToolSupport support) {
        return (arguments, _) -> support.screenshot(arguments);
    }
}

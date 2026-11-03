package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.BlockingToolHandler;

final class McRecordVideoTool {
    static final ToolDeclaration<RecordVideoArguments> DECLARATION = ToolDeclaration.of("mc_record_video", RecordVideoArguments.class);

    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("record_video");

    private McRecordVideoTool() {
    }

    static BlockingToolHandler<RecordVideoArguments> handler(MediaToolSupport support) {
        return (arguments, _) -> support.recordVideo(arguments);
    }
}

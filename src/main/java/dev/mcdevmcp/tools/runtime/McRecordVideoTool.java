package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ContentToolBinding;

final class McRecordVideoTool {
    static final ToolDeclaration<RecordVideoArguments> DECLARATION = ToolDeclaration.of("mc_record_video", RecordVideoArguments.class);

    static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("record_video");

    private McRecordVideoTool() {
    }

    static ContentToolBinding<RecordVideoArguments> binding(MediaToolSupport support) {
        return DECLARATION.bind((arguments, _) -> support.recordVideo(arguments));
    }
}

package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ContentToolBinding;

final class McSnapshotTool {
    static final ToolDeclaration<RuntimeEmptyArguments> DECLARATION = ToolDeclaration.of("mc_snapshot", RuntimeEmptyArguments.class);

    private static final BridgeEndpoint ENDPOINT = new BridgeEndpoint("snapshot");

    private McSnapshotTool() {
    }

    static ContentToolBinding<RuntimeEmptyArguments> binding(RuntimeToolSupport support) {
        return DECLARATION.bind((_, _) -> support.container(ENDPOINT, RuntimeToolSupport.EMPTY_PAYLOAD));
    }
}

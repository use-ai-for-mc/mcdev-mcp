package dev.mcdevmcp.support;

import dev.mcdevmcp.mcp.tool.api.ToolCancellation;

@FunctionalInterface
public interface Cancellation extends ToolCancellation {
    static Cancellation none() {
        return () -> false;
    }
}

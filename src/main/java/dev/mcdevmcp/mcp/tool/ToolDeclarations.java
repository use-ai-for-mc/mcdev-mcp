package dev.mcdevmcp.mcp.tool;

import dev.mcdevmcp.tools.runtime.RuntimeToolModule;
import dev.mcdevmcp.tools.statictool.StaticToolModule;

import java.util.ArrayList;
import java.util.List;

/**
 * The complete typed MCP declaration registry, independent of runtime activation.
 */
public final class ToolDeclarations {
    private ToolDeclarations() {
    }

    public static List<ToolDeclaration<?>> all() {
        var declarations = new ArrayList<ToolDeclaration<?>>();
        declarations.addAll(StaticToolModule.declarations());
        declarations.addAll(RuntimeToolModule.declarations());
        return List.copyOf(declarations);
    }
}

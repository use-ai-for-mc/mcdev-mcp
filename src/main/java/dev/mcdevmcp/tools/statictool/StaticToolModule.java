package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.storage.PlatformPaths;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StaticToolModule {
    private StaticToolModule() {
    }

    public static List<ToolDeclaration<?>> declarations() {
        return List.of(McVersionTool.DECLARATION, McSearchTool.DECLARATION, McGetClassTool.DECLARATION, McGetMethodTool.DECLARATION, McFindRefsTool.DECLARATION, McListClassesTool.DECLARATION, McListPackagesTool.DECLARATION, McFindHierarchyTool.DECLARATION);
    }

    public static Map<String, ToolBinding<?>> handlers(PlatformPaths paths) {
        var support = new StaticToolSupport(paths);
        var handlers = new LinkedHashMap<String, ToolBinding<?>>();
        add(handlers, McVersionTool.DECLARATION, McVersionTool.binding(support));
        add(handlers, McSearchTool.DECLARATION, McSearchTool.binding(support));
        add(handlers, McGetClassTool.DECLARATION, McGetClassTool.binding(support));
        add(handlers, McGetMethodTool.DECLARATION, McGetMethodTool.binding(support));
        add(handlers, McFindRefsTool.DECLARATION, McFindRefsTool.binding(support));
        add(handlers, McListClassesTool.DECLARATION, McListClassesTool.binding(support));
        add(handlers, McListPackagesTool.DECLARATION, McListPackagesTool.binding(support));
        add(handlers, McFindHierarchyTool.DECLARATION, McFindHierarchyTool.binding(support));
        return Map.copyOf(handlers);
    }

    private static void add(Map<String, ToolBinding<?>> handlers, ToolDeclaration<?> declaration, ToolBinding<?> binding) {
        if (handlers.putIfAbsent(declaration.name(), binding) != null) {
            throw new IllegalStateException("Duplicate static tool binding: " + declaration.name());
        }
    }
}
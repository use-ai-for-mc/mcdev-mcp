package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.storage.model.ClassSymbol;

import java.util.stream.Collectors;

final class McFindHierarchyTool {
    static final ToolDeclaration<FindHierarchyArguments> DECLARATION = ToolDeclaration.of("mc_find_hierarchy", FindHierarchyArguments.class);

    private static final LimitSpec LIMIT = new LimitSpec(200, 5000);

    private McFindHierarchyTool() {
    }

    static ToolBinding<FindHierarchyArguments> binding(StaticToolSupport support) {
        return DECLARATION.bindBlocking((arguments, _) -> support.execute("mc_find_hierarchy", () -> {
            var version = support.resolve(arguments.version());
            var limit = LIMIT.normalize(arguments.limit());
            int queryLimit = limit.value() + 1;
            var rows = support.repository(version).hierarchy(arguments.className(), arguments.direction() == HierarchyDirection.subclasses, queryLimit);
            boolean truncated = rows.size() >= limit.value();
            if (truncated) {
                rows = rows.subList(0, limit.value());
            }
            String direction = arguments.direction().wireValue();
            String className = arguments.className();
            if (rows.isEmpty()) {
                return ToolResult.text("No " + direction + " found for " + className);
            }
            String heading = arguments.direction() == HierarchyDirection.subclasses ? "Subclasses" : "Implementors";
            String renderedRows = rows.stream().map(ClassSymbol::binaryName).collect(Collectors.joining("\n"));
            return ToolResult.text(heading + " of " + className + ":\n" + renderedRows + StaticTools.truncationNote(rows.size(), truncated, limit, direction));
        }));
    }
}

package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.storage.model.ClassSymbol;

import java.util.stream.Collectors;

final class McListClassesTool {
    static final ToolDeclaration<ListClassesArguments> DECLARATION = ToolDeclaration.of("mc_list_classes", ListClassesArguments.class);

    private static final LimitSpec LIMIT = new LimitSpec(200, 5000);

    private McListClassesTool() {
    }

    static ToolBinding<ListClassesArguments> binding(StaticToolSupport support) {
        return DECLARATION.bindBlocking((arguments, _) -> support.execute("mc_list_classes", () -> {
            var version = support.resolve(arguments.version());
            var limit = LIMIT.normalize(arguments.limit());
            int queryLimit = limit.value() + 1;
            var rows = support.repository(version).classesUnder(arguments.packageName(), queryLimit);
            boolean truncated = rows.size() >= limit.value();
            if (truncated) {
                rows = rows.subList(0, limit.value());
            }
            if (rows.isEmpty()) {
                return ToolResult.text("No classes found under package \"" + arguments.packageName() + "\"");
            }
            String renderedRows = rows.stream().map(ClassSymbol::binaryName).collect(Collectors.joining("\n"));
            return ToolResult.text("Classes under \"" + arguments.packageName() + "\":\n" + renderedRows + StaticTools.truncationNote(rows.size(), truncated, limit, "class(es)"));
        }));
    }
}

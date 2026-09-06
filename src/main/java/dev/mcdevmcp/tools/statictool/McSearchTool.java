package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.storage.model.ClassSymbol;
import dev.mcdevmcp.storage.model.ElementKindCodec;
import dev.mcdevmcp.storage.model.SearchHit;
import dev.mcdevmcp.storage.model.SearchHitKind;

import java.util.stream.Collectors;

final class McSearchTool {
    static final ToolDeclaration<SearchArguments> DECLARATION = ToolDeclaration.of("mc_search", SearchArguments.class);

    private static final LimitSpec LIMIT = new LimitSpec(50, 1000);

    private McSearchTool() {
    }

    static ToolBinding<SearchArguments> binding(StaticToolSupport support) {
        return DECLARATION.bindBlocking((arguments, _) -> support.execute("mc_search", () -> {
            String query = arguments.query();
            var version = support.resolve(arguments.version());
            var limit = LIMIT.normalize(arguments.limit());
            int effectiveLimit = limit.value();
            String type = arguments.type() == null ? null : arguments.type().wireValue();
            var rows = support.repository(version).search(query, type, effectiveLimit + 1);
            boolean truncated = rows.size() >= effectiveLimit;
            if (truncated) {
                rows = rows.subList(0, effectiveLimit);
            }
            if (rows.isEmpty()) {
                String suffix = arguments.type() == null ? "" : " (type: " + arguments.type().wireValue() + ")";
                return ToolResult.text("No results found for \"" + query + "\"" + suffix);
            }
            String renderedRows = rows.stream().map(McSearchTool::render).collect(Collectors.joining("\n"));
            return ToolResult.text("Found " + rows.size() + " result(s):\n" + renderedRows + StaticTools.truncationNote(rows.size(), truncated, limit, "result(s)"));
        }));
    }

    private static String render(SearchHit hit) {
        ClassSymbol owner = hit.owner();
        if (hit.kind() == SearchHitKind.CLASS) {
            return renderClass(hit, owner);
        }
        if (hit.kind() == SearchHitKind.FIELD) {
            var field = hit.field().orElseThrow();
            return "[field] " + owner.binaryName() + "#" + field.name() + ": " + StaticToolSupport.modifiers(field.modifiers()) + field.type() + " " + field.name();
        }
        var method = hit.method().orElseThrow();
        String parameters = hit.parameters().stream().map(parameter -> parameter.type() + " " + parameter.name()).collect(Collectors.joining(", "));
        return "[method] " + owner.binaryName() + "#" + method.name() + ": " + StaticToolSupport.modifiers(method.modifiers()) + StaticToolSupport.returnType(method.returnType().orElse(null)) + " " + method.name() + "(" + parameters + ") (line " + method.startLine() + ")";
    }

    private static String renderClass(SearchHit hit, ClassSymbol owner) {
        String superclass = owner.superclassBinaryName().map(value -> " extends " + value).orElse("");
        String interfaces = "";
        if (!owner.interfaceBinaryNames().isEmpty()) {
            interfaces = " implements " + String.join(", ", owner.interfaceBinaryNames().stream().limit(3).toList());
            if (owner.interfaceBinaryNames().size() > 3) {
                interfaces += " (+" + (owner.interfaceBinaryNames().size() - 3) + ")";
            }
        }
        return "[" + ElementKindCodec.wireName(owner.kind()) + "] " + owner.binaryName() + superclass + interfaces + " (" + hit.fieldCount() + " fields, " + hit.methodCount() + " methods)";
    }
}

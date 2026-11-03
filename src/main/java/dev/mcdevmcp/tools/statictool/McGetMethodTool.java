package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ContentToolResult;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.storage.symbols.SymbolRepository;
import dev.mcdevmcp.storage.model.ClassSymbol;
import dev.mcdevmcp.storage.model.MethodSymbol;

import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

final class McGetMethodTool {
    static final ToolDeclaration<GetMethodArguments> DECLARATION = ToolDeclaration.of("mc_get_method", GetMethodArguments.class);

    private static final String CONSTRUCTOR_NAME = "<init>";

    private McGetMethodTool() {
    }

    static ToolBinding<GetMethodArguments> binding(StaticToolSupport support) {
        return DECLARATION.bindBlocking((arguments, _) -> support.execute("mc_get_method", () -> {
            String className = arguments.className();
            String methodName = arguments.methodName();
            Integer fromLine = arguments.fromLine();
            Integer toLine = arguments.toLine();
            if (fromLine != null && toLine != null && fromLine > toLine) {
                throw new StaticToolException("'fromLine' must not be after 'toLine'");
            }
            boolean ranged = fromLine != null || toLine != null;
            try (var lease = support.read(arguments.version())) {
                SymbolRepository repository = support.repository(lease);
                ClassSymbol type = repository.classByName(className);
                if (type == null) {
                    return ToolResult.text(missingText(className, methodName) + StaticToolSupport.similarClasses(repository, className));
                }
                String source;
                try {
                    source = support.fullSource(lease, type);
                } catch (NoSuchFileException exception) {
                    return missing(className, methodName);
                }
                if (source.isEmpty()) {
                    return missing(className, methodName);
                }
                List<MethodSymbol> overloads = repository.methodsNamed(type.id(), methodName);
                if (overloads.isEmpty() && methodName.equals(type.simpleName())) {
                    overloads = repository.methodsNamed(type.id(), CONSTRUCTOR_NAME);
                }
                if (overloads.isEmpty()) {
                    return missing(className, methodName);
                }
                String[] lines = source.split("\\n", -1);
                List<String> rendered = new ArrayList<>();
                for (MethodSymbol method : overloads) {
                    int firstLine;
                    int lastLine;
                    String shown = "";
                    if (ranged) {
                        int first = Math.max(method.startLine(), fromLine == null ? method.startLine() : fromLine);
                        int last = Math.min(method.endLine(), toLine == null ? method.endLine() : toLine);
                        if (first > last) {
                            continue;
                        }
                        firstLine = Math.max(0, first - 1);
                        lastLine = Math.min(lines.length, last);
                        shown = " (showing " + first + "-" + last + ")";
                    }
                    else {
                        firstLine = Math.max(0, method.startLine() - 3);
                        lastLine = Math.min(lines.length, method.endLine() + 3);
                    }
                    String header = header(type, method, StaticToolSupport.parameters(repository.parameters(method.id())), shown);
                    rendered.add(header + String.join("\n", Arrays.copyOfRange(lines, firstLine, lastLine)));
                }
                if (rendered.isEmpty()) {
                    String spans = overloads.stream().map(method -> method.startLine() + "-" + method.endLine()).collect(Collectors.joining(", "));
                    return ToolResult.text("No lines of " + type.binaryName() + "#" + overloads.getFirst().name() + " fall in " + (fromLine == null ? "1" : fromLine) + "-" + (toLine == null ? "end" : toLine) + "; its lines are " + spans);
                }
                String overloadNote = overloads.size() == 1 ? "" : "// " + overloads.size() + " methods of " + type.binaryName() + " are named " + overloads.getFirst().name() + (rendered.size() == overloads.size() ? "" : "; the requested lines cover " + rendered.size() + " of them") + "\n\n";
                return ToolResult.text(overloadNote + String.join("\n\n", rendered));
            }
        }));
    }

    private static String header(ClassSymbol type, MethodSymbol method, String parameters, String shown) {
        return "// Method: " + type.binaryName() + "#" + method.name() + "\n" + "// Signature: " + StaticToolSupport.signature(type, method, parameters) + "\n" + "// Modifiers: " + StaticToolSupport.modifiers(method.modifiers()).stripTrailing() + "\n" + "// Lines: " + method.startLine() + "-" + method.endLine() + shown + "\n\n" + type.superclassBinaryName().map(value -> "// Class extends: " + value + "\n\n").orElse("");
    }

    private static ContentToolResult<Void> missing(String className, String methodName) {
        return ToolResult.text(missingText(className, methodName));
    }

    private static String missingText(String className, String methodName) {
        return "Method \"" + methodName + "\" not found in class " + className;
    }
}
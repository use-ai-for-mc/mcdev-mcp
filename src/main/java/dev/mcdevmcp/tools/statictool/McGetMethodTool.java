package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ContentToolResult;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.storage.h2.SymbolRepository;
import dev.mcdevmcp.storage.model.ClassSymbol;
import dev.mcdevmcp.storage.model.MethodSymbol;

import java.util.Arrays;
import java.util.stream.Collectors;

final class McGetMethodTool {
    static final ToolDeclaration<GetMethodArguments> DECLARATION = ToolDeclaration.of("mc_get_method", GetMethodArguments.class);

    private McGetMethodTool() {
    }

    static ToolBinding<GetMethodArguments> binding(StaticToolSupport support) {
        return DECLARATION.bindBlocking((arguments, _) -> support.execute("mc_get_method", () -> {
            String className = arguments.className();
            String methodName = arguments.methodName();
            var version = support.resolve(arguments.version());
            SymbolRepository repository = support.repository(version);
            ClassSymbol type = repository.classByName(className);
            if (type == null) {
                return missing(className, methodName);
            }
            String source;
            try {
                source = support.fullSource(version, type);
            } catch (java.nio.file.NoSuchFileException exception) {
                return missing(className, methodName);
            }
            if (source.isEmpty()) {
                return missing(className, methodName);
            }
            MethodSymbol method = repository.methodNamed(type.id(), methodName);
            if (method == null) {
                return missing(className, methodName);
            }
            String parameters = repository.parameters(method.id()).stream().map(parameter -> parameter.type() + " " + parameter.name()).collect(Collectors.joining(", "));
            String header = header(type, className, method, parameters);
            String[] lines = source.split("\\n", -1);
            int firstLine = Math.max(0, method.startLine() - 3);
            int lastLine = Math.min(lines.length, method.endLine() + 3);
            return ToolResult.text(header + String.join("\n", Arrays.copyOfRange(lines, firstLine, lastLine)));
        }));
    }

    private static String header(ClassSymbol type, String className, MethodSymbol method, String parameters) {
        return "// Method: " + className + "#" + method.name() + "\n" + "// Signature: " + StaticToolSupport.returnType(method.returnType().orElse(null)) + " " + method.name() + "(" + parameters + ")\n" + "// Modifiers: " + StaticToolSupport.modifiers(method.modifiers()).stripTrailing() + "\n" + "// Lines: " + method.startLine() + "-" + method.endLine() + "\n\n" + type.superclassBinaryName().map(value -> "// Class extends: " + value + "\n\n").orElse("");
    }

    private static ContentToolResult<Void> missing(String className, String methodName) {
        return ToolResult.text("Method \"" + methodName + "\" not found in class " + className);
    }
}

package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.storage.h2.SymbolRepository;
import dev.mcdevmcp.storage.model.ClassSymbol;
import dev.mcdevmcp.storage.model.ElementKindCodec;
import dev.mcdevmcp.storage.model.FieldSymbol;
import dev.mcdevmcp.storage.model.MethodSymbol;

import java.util.List;
import java.util.stream.Collectors;

final class McGetClassTool {
    static final ToolDeclaration<GetClassArguments> DECLARATION = ToolDeclaration.of("mc_get_class", GetClassArguments.class);

    private McGetClassTool() {
    }

    static ToolBinding<GetClassArguments> binding(StaticToolSupport support) {
        return DECLARATION.bindBlocking((arguments, _) -> support.execute("mc_get_class", () -> {
            String className = arguments.className();
            var version = support.resolve(arguments.version());
            SymbolRepository repository = support.repository(version);
            ClassSymbol type = repository.classByName(className);
            if (type == null) {
                return ToolResult.text("Class not found: " + className);
            }
            String source;
            try {
                source = support.fullSource(version, type);
            } catch (java.nio.file.NoSuchFileException exception) {
                return ToolResult.text("Class not found: " + className);
            }
            if (source.isEmpty()) {
                return ToolResult.text("Class not found: " + className);
            }
            var fields = repository.fields(type.id());
            var methods = repository.methods(type.id());
            String header = header(type, className, fields.size(), methods.size());
            String body = switch (arguments.view()) {
                case full -> source;
                case fields -> fields(fields);
                case methods -> methods(repository, methods);
                case summary -> fields(fields) + "\n" + methods(repository, methods);
            };
            return ToolResult.text(header + body);
        }));
    }

    private static String header(ClassSymbol type, String className, int fieldCount, int methodCount) {
        String extendsLine = type.superclassBinaryName().map(value -> "// Extends: " + value + "\n").orElse("");
        String implementsLine = type.interfaceBinaryNames().isEmpty() ? "" : "// Implements: " + String.join(", ", type.interfaceBinaryNames()) + "\n";
        return "// " + ElementKindCodec.wireName(type.kind()) + " " + className + "\n" + extendsLine + implementsLine + "// Fields: " + fieldCount + ", Methods: " + methodCount + "\n\n";
    }

    private static String fields(List<FieldSymbol> fields) {
        if (fields.isEmpty()) {
            return "// (no fields)\n";
        }
        String declarations = fields.stream().map(field -> StaticToolSupport.modifiers(field.modifiers()) + field.type() + " " + field.name() + ";").collect(Collectors.joining("\n"));
        return "// Fields:\n" + declarations + "\n";
    }

    private static String methods(SymbolRepository repository, List<MethodSymbol> methods) throws java.io.IOException, java.sql.SQLException {
        if (methods.isEmpty()) {
            return "// (no methods)\n";
        }
        var lines = new java.util.ArrayList<String>();
        for (var method : methods) {
            String parameters = repository.parameters(method.id()).stream().map(parameter -> parameter.type() + " " + parameter.name()).collect(Collectors.joining(", "));
            lines.add(StaticToolSupport.modifiers(method.modifiers()) + StaticToolSupport.returnType(method.returnType().orElse(null)) + " " + method.name() + "(" + parameters + ");");
        }
        return "// Methods:\n" + String.join("\n", lines) + "\n";
    }
}

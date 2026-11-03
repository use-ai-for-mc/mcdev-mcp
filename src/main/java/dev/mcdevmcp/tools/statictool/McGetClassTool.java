package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.storage.symbols.SymbolRepository;
import dev.mcdevmcp.storage.model.ClassSymbol;
import dev.mcdevmcp.storage.model.ElementKindCodec;
import dev.mcdevmcp.storage.model.FieldSymbol;
import dev.mcdevmcp.storage.model.MethodSymbol;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

final class McGetClassTool {
    static final ToolDeclaration<GetClassArguments> DECLARATION = ToolDeclaration.of("mc_get_class", GetClassArguments.class);

    private McGetClassTool() {
    }

    static ToolBinding<GetClassArguments> binding(StaticToolSupport support) {
        return DECLARATION.bindBlocking((arguments, _) -> support.execute("mc_get_class", () -> {
            String className = arguments.className();
            try (var lease = support.read(arguments.version())) {
                SymbolRepository repository = support.repository(lease);
                ClassSymbol type = repository.classByName(className);
                if (type == null) {
                    return ToolResult.text("Class not found: " + className + StaticToolSupport.similarClasses(repository, className));
                }
                String source;
                try {
                    source = support.fullSource(lease, type);
                } catch (NoSuchFileException exception) {
                    return ToolResult.text("Class not found: " + className);
                }
                if (source.isEmpty()) {
                    return ToolResult.text("Class not found: " + className);
                }
                var fields = repository.fields(type.id());
                var methods = repository.methods(type.id());
                Optional<ClassSymbol> enclosing = repository.enclosingType(type);
                String declaredIn = enclosing.map(value -> "// Declared in: " + value.binaryName() + " (lines " + type.startLine() + "-" + type.endLine() + ")").orElse("");
                String header = header(type, declaredIn, repository.memberTypes(type.binaryName()), fields.size(), methods.size());
                String body = switch (arguments.view()) {
                    case full -> enclosing.isPresent() ? declaration(source, type) : source;
                    case fields -> fields(fields);
                    case methods -> methods(repository, type, methods);
                    case summary -> fields(fields) + "\n" + methods(repository, type, methods);
                };
                return ToolResult.text(header + body);
            }
        }));
    }

    private static String header(ClassSymbol type, String declaredIn, List<ClassSymbol> memberTypes, int fieldCount, int methodCount) {
        String declaredInLine = declaredIn.isEmpty() ? "" : declaredIn + "\n";
        String extendsLine = type.superclassBinaryName().map(value -> "// Extends: " + value + "\n").orElse("");
        String implementsLine = type.interfaceBinaryNames().isEmpty() ? "" : "// Implements: " + String.join(", ", type.interfaceBinaryNames()) + "\n";
        String memberTypesLine = memberTypes.isEmpty() ? "" : "// Member types: " + memberTypes.stream().map(ClassSymbol::binaryName).collect(Collectors.joining(", ")) + "\n";
        return "// " + ElementKindCodec.wireName(type.kind()) + " " + type.binaryName() + "\n" + declaredInLine + extendsLine + implementsLine + memberTypesLine + "// Fields: " + fieldCount + ", Methods: " + methodCount + "\n\n";
    }

    /**
     * The lines of a member type's own declaration, without the rest of the file that holds it.
     */
    private static String declaration(String source, ClassSymbol type) {
        String[] lines = source.split("\\n", -1);
        int firstLine = Math.clamp(type.startLine() - 1, 0, lines.length);
        int lastLine = Math.clamp(type.endLine(), firstLine, lines.length);
        return String.join("\n", Arrays.copyOfRange(lines, firstLine, lastLine)) + "\n";
    }

    private static String fields(List<FieldSymbol> fields) {
        if (fields.isEmpty()) {
            return "// (no fields)\n";
        }
        String declarations = fields.stream().map(field -> StaticToolSupport.modifiers(field.modifiers()) + field.type() + " " + field.name() + ";").collect(Collectors.joining("\n"));
        return "// Fields:\n" + declarations + "\n";
    }

    private static String methods(SymbolRepository repository, ClassSymbol type, List<MethodSymbol> methods) throws IOException {
        if (methods.isEmpty()) {
            return "// (no methods)\n";
        }
        var lines = new ArrayList<String>();
        for (var method : methods) {
            lines.add(StaticToolSupport.modifiers(method.modifiers()) + StaticToolSupport.signature(type, method, StaticToolSupport.parameters(repository.parameters(method.id()))) + ";");
        }
        return "// Methods:\n" + String.join("\n", lines) + "\n";
    }
}

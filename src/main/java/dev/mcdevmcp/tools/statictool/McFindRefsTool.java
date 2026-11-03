package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.storage.callgraph.CallgraphRepository;
import dev.mcdevmcp.storage.model.ClassSymbol;
import dev.mcdevmcp.storage.model.MethodReference;
import dev.mcdevmcp.storage.symbols.SymbolRepository;
import dev.mcdevmcp.support.AppVersion;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

final class McFindRefsTool {
    static final ToolDeclaration<FindRefsArguments> DECLARATION = ToolDeclaration.of("mc_find_refs", FindRefsArguments.class);

    static final LimitSpec LIMIT = new LimitSpec(100, 5000);
    private static final int MAX_CAUSE_LENGTH = 500;
    private static final int COUNTED_CALLERS = 100;
    private static final int MAX_INHERITING_TYPES = 5000;

    private McFindRefsTool() {
    }

    static ToolBinding<FindRefsArguments> binding(StaticToolSupport support) {
        return DECLARATION.bindBlocking((arguments, _) -> support.execute("mc_find_refs", () -> {
            try (var lease = support.read(arguments.version())) {
                var version = lease.version();
                String direction = arguments.direction().wireValue();
                String className = arguments.className();
                String methodName = arguments.methodName();
                CallgraphRepository.PublicationStatus publicationStatus = CallgraphRepository.publicationStatus(lease.boundary().require(lease.resolvedPaths().callgraphBundle(version)));
                if (publicationStatus == CallgraphRepository.PublicationStatus.CORRUPT) {
                    return ToolResult.text("Version " + version.value() + " has corrupt callgraph data.\n\n" + "STOP and ask the USER to run this command in their terminal:\n" + "  " + AppVersion.commandLine() + " callgraph -v " + version.value() + "\n\n" + "Or for full reinitialization:\n  " + AppVersion.commandLine() + " init -v " + version.value());
                }
                if (publicationStatus == CallgraphRepository.PublicationStatus.ABSENT) {
                    return ToolResult.text("Version " + version.value() + " does not have callgraph data.\n\n" + "STOP and ask the USER to run this command in their terminal:\n" + "  " + AppVersion.commandLine() + " callgraph -v " + version.value() + "\n\n" + "Or for full reinitialization:\n  " + AppVersion.commandLine() + " init -v " + version.value());
                }
                if (publicationStatus == CallgraphRepository.PublicationStatus.OUTDATED) {
                    return ToolResult.text("Version " + version.value() + " has callgraph data from an earlier mcdev-mcp, which this one cannot read.\n\n" + "STOP and ask the USER to run this command in their terminal:\n" + "  " + AppVersion.commandLine() + " callgraph -v " + version.value());
                }
                var limit = LIMIT.normalize(arguments.limit());
                int queryLimit = limit.value() + 1;
                SymbolRepository symbols = support.repository(lease);
                ClassSymbol type = symbols.classByName(className);
                String owner = type == null ? className : type.binaryName();
                CallgraphRepository callgraph = support.callgraphRepository(lease);
                List<MethodReference> fetched;
                String note;
                try {
                    if (arguments.direction().field()) {
                        FieldReferences references = fieldReferences(symbols, callgraph, type, owner, methodName, arguments.direction(), queryLimit);
                        fetched = references.references();
                        note = type == null ? StaticToolSupport.similarClasses(symbols, className) : fieldNote(symbols, type, methodName, references.inheritingOwners());
                    }
                    else {
                        fetched = arguments.direction() == ReferenceDirection.callers ? callgraph.callers(owner, methodName, queryLimit) : callgraph.callees(owner, methodName, queryLimit);
                        note = type == null ? StaticToolSupport.similarClasses(symbols, className) : declarationNote(symbols, callgraph, type, methodName, arguments.direction());
                    }
                } catch (IOException | RuntimeException exception) {
                    return ToolResult.error("Failed to query callgraph for " + className + "#" + methodName + " (" + direction + "): " + boundedCause(exception));
                }
                if (fetched.isEmpty()) {
                    return ToolResult.text("No " + direction + " found for " + className + "#" + methodName + note);
                }
                int fetchedCount = fetched.size();
                boolean truncated = fetchedCount > limit.value();
                List<MethodReference> shown = truncated ? fetched.subList(0, limit.value()) : fetched;
                String rows = shown.stream().map(McFindRefsTool::render).collect(Collectors.joining("\n"));
                return ToolResult.text("Found " + fetchedCount + " " + direction + ":\n" + rows + StaticTools.truncationNote(shown.size(), fetchedCount, truncated, limit, direction) + note);
            }
        }));
    }

    /**
     * What the symbol index knows that the edges alone do not show: that the class declares no such
     * method, and which supertypes declare it. A call made through a supertype is an edge to that type.
     */
    private static String declarationNote(SymbolRepository symbols, CallgraphRepository callgraph, ClassSymbol type, String methodName, ReferenceDirection direction) throws IOException {
        boolean declared = !symbols.methodsNamed(type.id(), methodName).isEmpty();
        boolean compilerNamed = methodName.startsWith("<") || methodName.startsWith("lambda$");
        List<String> supertypes = new ArrayList<>();
        for (String supertype : supertypes(symbols, type)) {
            ClassSymbol indexed = symbols.classByName(supertype);
            int callers = callgraph.callers(supertype, methodName, COUNTED_CALLERS + 1).size();
            if (callers > 0 || indexed != null && !symbols.methodsNamed(indexed.id(), methodName).isEmpty()) {
                supertypes.add(supertype + "#" + methodName + " (" + (callers > COUNTED_CALLERS ? COUNTED_CALLERS + "+" : callers) + " callers)");
            }
        }
        String missing = declared || compilerNamed ? "" : "\n" + type.binaryName() + " declares no method named " + methodName + ".";
        if (supertypes.isEmpty() || declared && direction == ReferenceDirection.callees) {
            return missing;
        }
        String heading = declared ? "Calls made through a supertype are recorded under that type:" : "Supertypes that declare it:";
        return missing + "\n" + heading + "\n" + supertypes.stream().map(value -> "  " + value).collect(Collectors.joining("\n"));
    }

    /**
     * The field's readers or writers. Bytecode names a field by the class it was accessed through, so an
     * access from a subclass, or through a subclass-typed reference, is recorded under that subclass; the
     * subclasses that inherit the field are searched too, and those that declare their own field of that
     * name are not.
     */
    private static FieldReferences fieldReferences(SymbolRepository symbols, CallgraphRepository callgraph, ClassSymbol type, String owner, String fieldName, ReferenceDirection direction, int queryLimit) throws IOException {
        List<String> owners = new ArrayList<>(List.of(owner));
        if (type != null) {
            owners.addAll(inheritingTypes(symbols, type, fieldName));
        }
        List<MethodReference> references = new ArrayList<>();
        int inheritingOwners = 0;
        for (String current : owners) {
            int remaining = queryLimit - references.size();
            if (remaining < 1) {
                break;
            }
            List<MethodReference> found = direction == ReferenceDirection.readers ? callgraph.readers(current, fieldName, remaining) : callgraph.writers(current, fieldName, remaining);
            if (!found.isEmpty() && !current.equals(owner)) {
                inheritingOwners++;
            }
            references.addAll(found);
        }
        return new FieldReferences(List.copyOf(references), inheritingOwners);
    }

    /**
     * The subtypes that inherit the type's field of that name, nearest first, without those that hide it
     * with a field of their own and their subtypes.
     */
    private static List<String> inheritingTypes(SymbolRepository symbols, ClassSymbol type, String fieldName) throws IOException {
        Set<String> seen = new LinkedHashSet<>(List.of(type.binaryName()));
        Deque<ClassSymbol> pending = new ArrayDeque<>(List.of(type));
        while (!pending.isEmpty() && seen.size() <= MAX_INHERITING_TYPES) {
            ClassSymbol current = pending.removeFirst();
            List<ClassSymbol> direct = new ArrayList<>(symbols.hierarchy(current.binaryName(), true, MAX_INHERITING_TYPES));
            direct.addAll(symbols.hierarchy(current.binaryName(), false, MAX_INHERITING_TYPES));
            for (ClassSymbol subtype : direct) {
                if (!declaresField(symbols, subtype, fieldName) && seen.add(subtype.binaryName())) {
                    pending.addLast(subtype);
                }
            }
        }
        seen.remove(type.binaryName());
        return List.copyOf(seen);
    }

    private static boolean declaresField(SymbolRepository symbols, ClassSymbol type, String fieldName) throws IOException {
        return symbols.fields(type.id()).stream().anyMatch(field -> field.name().equals(fieldName));
    }

    /**
     * What the symbol index adds to a field's accesses: that the class declares no such field, and how many
     * subclasses the accesses were recorded under.
     */
    private static String fieldNote(SymbolRepository symbols, ClassSymbol type, String fieldName, int inheritingOwners) throws IOException {
        String missing = "";
        if (!declaresField(symbols, type, fieldName)) {
            String declaring = null;
            for (String supertype : supertypes(symbols, type)) {
                ClassSymbol indexed = symbols.classByName(supertype);
                if (indexed != null && declaresField(symbols, indexed, fieldName)) {
                    declaring = supertype;
                    break;
                }
            }
            missing = "\n" + type.binaryName() + " declares no field named " + fieldName + (declaring == null ? "." : "; it inherits " + declaring + "#" + fieldName + ". Query " + declaring + " to see the accesses through every type.");
        }
        String inherited = inheritingOwners == 0 ? "" : "\nIncludes accesses recorded under " + inheritingOwners + (inheritingOwners == 1 ? " subtype that inherits" : " subtypes that inherit") + " the field.";
        return missing + inherited;
    }

    /**
     * Every supertype reachable from the type, nearest first. An unindexed supertype is named but not followed.
     */
    private static List<String> supertypes(SymbolRepository symbols, ClassSymbol type) throws IOException {
        Set<String> seen = new LinkedHashSet<>();
        Deque<ClassSymbol> pending = new ArrayDeque<>(List.of(type));
        while (!pending.isEmpty()) {
            ClassSymbol current = pending.removeFirst();
            List<String> direct = new ArrayList<>(current.interfaceBinaryNames());
            current.superclassBinaryName().ifPresent(direct::addFirst);
            for (String supertype : direct) {
                if (seen.add(supertype)) {
                    ClassSymbol indexed = symbols.classByName(supertype);
                    if (indexed != null) {
                        pending.addLast(indexed);
                    }
                }
            }
        }
        seen.remove(type.binaryName());
        return List.copyOf(seen);
    }

    private static String render(MethodReference reference) {
        Integer line = reference.lineNumber();
        return reference.displayName() + (line == null || line == 0 ? "" : " (line " + line + ")");
    }

    private value record FieldReferences(List<MethodReference> references, int inheritingOwners) {
    }

    private static String boundedCause(Exception exception) {
        String message = exception.getMessage();
        String value = message == null ? exception.toString() : message;
        value = value.replace('\r', ' ').replace('\n', ' ').trim();
        return value.length() <= MAX_CAUSE_LENGTH ? value : value.substring(0, MAX_CAUSE_LENGTH) + "...";
    }
}

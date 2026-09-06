package dev.mcdevmcp.tools.statictool;

import dev.mcdevmcp.mcp.tool.ToolDeclaration;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.storage.callgraph.CallgraphRepository;
import dev.mcdevmcp.storage.model.MethodReference;
import dev.mcdevmcp.support.AppVersion;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

final class McFindRefsTool {
    static final ToolDeclaration<FindRefsArguments> DECLARATION = ToolDeclaration.of("mc_find_refs", FindRefsArguments.class);

    static final LimitSpec LIMIT = new LimitSpec(100, 5000);
    private static final int MAX_CAUSE_LENGTH = 500;

    private McFindRefsTool() {
    }

    static ToolBinding<FindRefsArguments> binding(StaticToolSupport support) {
        return DECLARATION.bindBlocking((arguments, _) -> support.execute("mc_find_refs", () -> {
            var version = support.resolve(arguments.version());
            String direction = arguments.direction().wireValue();
            String className = arguments.className();
            String methodName = arguments.methodName();
            CallgraphRepository.PublicationStatus publicationStatus = CallgraphRepository.publicationStatus(support.paths().callgraphBundle(version));
            if (publicationStatus == CallgraphRepository.PublicationStatus.CORRUPT) {
                return ToolResult.text("Version " + version.value() + " has corrupt callgraph data.\n\n" + "STOP and ask the USER to run this command in their terminal:\n" + "  java -jar " + AppVersion.executableJarName() + " callgraph -v " + version.value() + "\n\n" + "Or for full reinitialization:\n  java -jar " + AppVersion.executableJarName() + " init -v " + version.value());
            }
            if (publicationStatus == CallgraphRepository.PublicationStatus.ABSENT) {
                return ToolResult.text("Version " + version.value() + " does not have callgraph data.\n\n" + "STOP and ask the USER to run this command in their terminal:\n" + "  java -jar " + AppVersion.executableJarName() + " callgraph -v " + version.value() + "\n\n" + "Or for full reinitialization:\n  java -jar " + AppVersion.executableJarName() + " init -v " + version.value());
            }
            var limit = LIMIT.normalize(arguments.limit());
            int queryLimit = limit.value() + 1;
            List<MethodReference> fetched;
            try {
                fetched = arguments.direction() == ReferenceDirection.callers ? support.callgraphRepository(version).callers(arguments.className(), arguments.methodName(), queryLimit) : support.callgraphRepository(version).callees(arguments.className(), arguments.methodName(), queryLimit);
            } catch (IOException | RuntimeException exception) {
                return ToolResult.error("Failed to query callgraph for " + className + "#" + methodName + " (" + direction + "): " + boundedCause(exception));
            }
            if (fetched.isEmpty()) {
                return ToolResult.text("No " + direction + " found for " + className + "#" + methodName);
            }
            int fetchedCount = fetched.size();
            boolean truncated = fetchedCount > limit.value();
            List<MethodReference> shown = truncated ? fetched.subList(0, limit.value()) : fetched;
            String rows = shown.stream().map(McFindRefsTool::render).collect(Collectors.joining("\n"));
            return ToolResult.text("Found " + fetchedCount + " " + direction + ":\n" + rows + StaticTools.truncationNote(shown.size(), fetchedCount, truncated, limit, direction));
        }));
    }

    private static String render(MethodReference reference) {
        Integer line = reference.lineNumber();
        return reference.displayName() + (line == null || line == 0 ? "" : " (line " + line + ")");
    }

    private static String boundedCause(Exception exception) {
        String message = exception.getMessage();
        String value = message == null ? exception.toString() : message;
        value = value.replace('\r', ' ').replace('\n', ' ').trim();
        return value.length() <= MAX_CAUSE_LENGTH ? value : value.substring(0, MAX_CAUSE_LENGTH) + "...";
    }
}

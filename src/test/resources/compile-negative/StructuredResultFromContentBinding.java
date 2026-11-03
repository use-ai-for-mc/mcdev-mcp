package compilenegative;

import dev.mcdevmcp.mcp.tool.api.*;

final class StructuredResultFromContentBinding {
    private static final ToolInput<Arguments> INPUT = ToolInput.of(Arguments.class, RecordInputSchemaFactory.standard());

    private static ToolBinding<Arguments> invalidContentBinding() {
        return ToolBinding.content(INPUT, (_, _) -> ToolHandlers.completed(ToolResult.structured(new Summary(), "structured")));
    }

    private record Arguments() {
    }

    private record Summary() {
    }
}
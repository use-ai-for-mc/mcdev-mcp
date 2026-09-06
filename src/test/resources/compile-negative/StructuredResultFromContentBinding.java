package compilenegative;

import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolHandlers;
import dev.mcdevmcp.mcp.tool.api.ToolInput;
import dev.mcdevmcp.mcp.tool.api.ToolResult;

final class StructuredResultFromContentBinding {
    private static final ToolInput<Arguments> INPUT = ToolInput.of(Arguments.class, dev.mcdevmcp.mcp.tool.api.RecordInputSchemaFactory.standard());

    private static ToolBinding<Arguments> invalidContentBinding() {
        return ToolBinding.content(INPUT, (_, _) -> ToolHandlers.completed(ToolResult.structured(new Summary(), "structured")));
    }

    private record Arguments() {
    }

    private record Summary() {
    }
}

package compilenegative;

import dev.mcdevmcp.mcp.tool.api.JsonValueSchema;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolHandlers;
import dev.mcdevmcp.mcp.tool.api.ToolInput;
import dev.mcdevmcp.mcp.tool.api.ToolOutput;
import dev.mcdevmcp.mcp.tool.api.ToolOutputBinding;
import dev.mcdevmcp.mcp.tool.api.ToolResult;

import java.util.Map;

final class IncorrectStructuredOutputBinding {
    private static final ToolInput<Arguments> INPUT = ToolInput.of(Arguments.class, dev.mcdevmcp.mcp.tool.api.RecordInputSchemaFactory.standard());
    private static final ToolOutput<Expected> OUTPUT = ToolOutput.of(Expected.class, JsonValueSchema.of(Map.of("type", "object")));

    private static ToolOutputBinding<Arguments, Expected> wrongResultType() {
        return ToolBinding.output(INPUT, OUTPUT, (_, _) -> ToolHandlers.completed(ToolResult.structured(new Wrong(), "wrong")));
    }

    private record Arguments() {
    }

    private record Expected() {
    }

    private record Wrong() {
    }
}

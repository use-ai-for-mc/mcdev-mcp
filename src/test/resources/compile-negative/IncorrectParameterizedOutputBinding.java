package compilenegative;

import dev.mcdevmcp.mcp.tool.api.*;
import io.modelcontextprotocol.json.TypeRef;

import java.util.List;
import java.util.Map;

final class IncorrectParameterizedOutputBinding {
    private static final ToolInput<Arguments> INPUT = ToolInput.of(Arguments.class, RecordInputSchemaFactory.standard());
    private static final ToolOutput<List<String>> OUTPUT = ToolOutput.of(new TypeRef<List<String>>() {
    }, JsonValueSchema.of(Map.of("type", "array")));

    private static ToolOutputBinding<Arguments, List<String>> wrongResultType() {
        return ToolBinding.output(INPUT, OUTPUT, (_, _) -> ToolHandlers.completed(ToolResult.structured(List.of(1), "wrong")));
    }

    private record Arguments() {
    }
}
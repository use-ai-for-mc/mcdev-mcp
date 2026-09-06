package compilepositive;

import dev.mcdevmcp.mcp.tool.api.BlockingToolOutputHandler;
import dev.mcdevmcp.mcp.tool.api.ContentToolBinding;
import dev.mcdevmcp.mcp.tool.api.JsonValueSchema;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolHandlers;
import dev.mcdevmcp.mcp.tool.api.ToolInput;
import dev.mcdevmcp.mcp.tool.api.ToolOutput;
import dev.mcdevmcp.mcp.tool.api.ToolOutputBinding;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import io.modelcontextprotocol.json.TypeRef;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

final class CorrectTypedOutputBindings {
    private static final ToolInput<Arguments> INPUT = ToolInput.of(Arguments.class, dev.mcdevmcp.mcp.tool.api.RecordInputSchemaFactory.standard());
    private static final ToolOutput<Summary> SUMMARY_OUTPUT = ToolOutput.of(Summary.class, JsonValueSchema.of(Map.of("type", "object")));
    private static final ToolOutput<List<String>> LIST_OUTPUT = ToolOutput.of(new TypeRef<List<String>>() {
    }, JsonValueSchema.of(Map.of("type", "array", "items", Map.of("type", "string"))));

    static ToolOutputBinding<Arguments, Summary> structured() {
        return ToolBinding.output(INPUT, SUMMARY_OUTPUT, (_, _) -> ToolHandlers.completed(ToolResult.structured(new Summary("ok"), "ok")));
    }

    static ToolOutputBinding<Arguments, Summary> contentSuccess() {
        return ToolBinding.output(INPUT, SUMMARY_OUTPUT, (_, _) -> ToolHandlers.completed(ToolResult.text("content")));
    }

    static ToolOutputBinding<Arguments, Summary> contentError() {
        return ToolBinding.output(INPUT, SUMMARY_OUTPUT, (_, _) -> CompletableFuture.completedFuture(ToolResult.error("content error")));
    }

    static ToolOutputBinding<Arguments, Summary> asynchronous() {
        return ToolBinding.output(INPUT, SUMMARY_OUTPUT, (_, _) -> CompletableFuture.completedFuture(ToolResult.text("async")));
    }

    static ToolOutputBinding<Arguments, Summary> blocking() {
        BlockingToolOutputHandler<Arguments, Summary> handler = (_, _) -> ToolResult.text("blocking");
        return ToolBinding.blocking(INPUT, SUMMARY_OUTPUT, handler);
    }

    static ToolOutputBinding<Arguments, List<String>> parameterized() {
        return ToolBinding.output(INPUT, LIST_OUTPUT, (_, _) -> ToolHandlers.completed(ToolResult.structured(List.of("item"), "item")));
    }

    static ContentToolBinding<Arguments> contentOnly() {
        return ToolBinding.content(INPUT, (_, _) -> ToolHandlers.completed(ToolResult.text("content only")));
    }

    private record Arguments(String value) {
    }

    private record Summary(String value) {
    }
}

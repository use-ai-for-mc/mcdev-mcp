package dev.mcdevmcp.mcp.tool.api.smoke;

import dev.mcdevmcp.mcp.tool.api.RecordInputSchemaFactory;
import dev.mcdevmcp.mcp.tool.api.StructuredToolResult;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolCancellation;
import dev.mcdevmcp.mcp.tool.api.ToolHandlers;
import dev.mcdevmcp.mcp.tool.api.ToolInput;
import dev.mcdevmcp.mcp.tool.api.ToolOutput;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.mcp.tool.api.JsonValueSchema;
import dev.mcdevmcp.mcp.tool.api.JsonType;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;

public final class JpmsSmokeMain {
    private JpmsSmokeMain() {
    }

    static void main() throws Exception {
        var mapper = McpJsonDefaults.getMapper();
        var input = ToolInput.of(Payload.class, RecordInputSchemaFactory.standard());
        var decoded = input.decode(mapper, Map.of("value", "jpms-ok"));
        if (!"jpms-ok".equals(decoded.value())) {
            throw new IllegalStateException("JPMS mapper round trip returned an unexpected value");
        }
        var output = ToolOutput.of(Payload.class, JsonValueSchema.of(Map.of("type", "object", "properties", Map.of("value", Map.of("type", "string")))));
        var outputBinding = ToolBinding.output(input, output, (payload, _) -> ToolHandlers.completed(ToolResult.text(payload.value())));
        if (outputBinding.output().orElseThrow() != output) {
            throw new IllegalStateException("JPMS output metadata was not retained");
        }
        ToolResult<?> bound = outputBinding.invoke(mapper, Map.of("value", "binding-ok"), ToolCancellation.none()).toCompletableFuture().resultNow();
        if (bound.isError() || !"binding-ok".equals(((McpSchema.TextContent) bound.content().getFirst()).text())) {
            throw new IllegalStateException("JPMS typed binding returned an unexpected result");
        }
        var genericDecoded = JsonType.of(Payload.class).decode(mapper, Map.of("value", "json-ok"));
        if (!"json-ok".equals(genericDecoded.value())) {
            throw new IllegalStateException("JPMS generic JSON conversion returned an unexpected value");
        }
        StructuredToolResult<Payload> result = ToolResult.structured(decoded, "jpms-ok");
        if (!mapper.writeValueAsString(result.structuredContent()).contains("jpms-ok")) {
            throw new IllegalStateException("JPMS structured result did not serialize its typed value");
        }
        if (McpJsonDefaults.getSchemaValidator() == null) {
            throw new IllegalStateException("JPMS schema validator provider was not resolved");
        }
    }
}

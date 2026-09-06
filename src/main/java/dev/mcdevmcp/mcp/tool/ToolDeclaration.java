package dev.mcdevmcp.mcp.tool;

import dev.mcdevmcp.mcp.tool.api.BlockingToolHandler;
import dev.mcdevmcp.mcp.tool.api.ContentToolBinding;
import dev.mcdevmcp.mcp.tool.api.RecordInputSchemaFactory;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolHandler;
import dev.mcdevmcp.mcp.tool.api.ToolInput;
import dev.mcdevmcp.mcp.tool.api.ToolOutput;
import dev.mcdevmcp.mcp.tool.api.ToolOutputBinding;
import dev.mcdevmcp.mcp.tool.api.ToolOutputHandler;
import dev.mcdevmcp.mcp.tool.api.BlockingToolOutputHandler;

import java.util.Objects;

/**
 * The single typed declaration for one MCP tool.
 */
public final class ToolDeclaration<A> {
    private final String name;
    private final ToolInput<A> input;
    private final ToolAvailability availability;

    private ToolDeclaration(String name, ToolInput<A> input, ToolAvailability availability) {
        this.name = requireText(name);
        this.input = Objects.requireNonNull(input, "Tool input");
        this.availability = Objects.requireNonNull(availability, "Tool availability");
    }

    public static <A> ToolDeclaration<A> of(String name, Class<A> inputType) {
        return of(name, inputType, ToolAvailability.ALWAYS);
    }

    public static <A> ToolDeclaration<A> of(String name, Class<A> inputType, ToolAvailability availability) {
        return new ToolDeclaration<>(name, ToolInput.of(Objects.requireNonNull(inputType, "inputType"), RecordInputSchemaFactory.standard()), availability);
    }

    public String name() {
        return name;
    }

    public ToolInput<A> input() {
        return input;
    }

    public ToolAvailability availability() {
        return availability;
    }

    public ContentToolBinding<A> bind(ToolHandler<A> handler) {
        return ToolBinding.content(input, handler);
    }

    public <O> ToolOutputBinding<A, O> bind(ToolOutput<O> output, ToolOutputHandler<A, O> handler) {
        return ToolBinding.output(input, output, handler);
    }

    public ContentToolBinding<A> bindBlocking(BlockingToolHandler<A> handler) {
        return ToolBinding.blocking(input, handler);
    }

    public <O> ToolOutputBinding<A, O> bindBlocking(ToolOutput<O> output, BlockingToolOutputHandler<A, O> handler) {
        return ToolBinding.blocking(input, output, handler);
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Tool name must not be blank");
        }
        return value;
    }
}

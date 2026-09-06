package dev.mcdevmcp.mcp.tool;

import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolInput;
import dev.mcdevmcp.mcp.tool.api.ToolOutput;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record ToolDefinition(String name, String description, ToolBinding<?> binding, ToolAvailability availability) {
    public ToolDefinition {
        name = requireText(name, "Tool name");
        description = requireText(description, "Tool description");
        Objects.requireNonNull(binding, "Tool binding");
        Objects.requireNonNull(availability, "Tool availability");
    }

    public ToolInput<?> input() {
        return binding.input();
    }

    public Map<String, Object> inputSchema() {
        return binding.input().schema().value();
    }

    public Optional<ToolOutput<?>> output() {
        return binding.output();
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}

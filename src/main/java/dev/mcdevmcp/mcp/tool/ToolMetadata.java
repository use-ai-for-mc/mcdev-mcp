package dev.mcdevmcp.mcp.tool;

public record ToolMetadata(String name, String description) {
    public ToolMetadata {
        name = requireText(name, "Tool metadata name");
        description = requireText(description, "Tool metadata description");
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
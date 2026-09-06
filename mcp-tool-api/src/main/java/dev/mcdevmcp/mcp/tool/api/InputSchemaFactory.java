package dev.mcdevmcp.mcp.tool.api;

@FunctionalInterface
public interface InputSchemaFactory {
    /**
     * Generates a schema in the directly validated subset accepted by {@link JsonObjectSchema}.
     */
    JsonObjectSchema generate(JsonType<?> type);
}
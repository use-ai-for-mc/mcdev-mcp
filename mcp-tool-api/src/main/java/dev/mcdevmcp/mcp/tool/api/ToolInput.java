package dev.mcdevmcp.mcp.tool.api;

import io.modelcontextprotocol.json.McpJsonMapper;

import java.util.Map;
import java.util.Objects;

public final class ToolInput<A> {
    private final JsonType<A> type;
    private final JsonObjectSchema schema;

    private ToolInput(JsonType<A> type, JsonObjectSchema schema) {
        this.type = Objects.requireNonNull(type, "type");
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    public static <A> ToolInput<A> of(Class<A> type, InputSchemaFactory factory) {
        JsonType<A> jsonType = JsonType.of(Objects.requireNonNull(type, "type"));
        return new ToolInput<>(jsonType, Objects.requireNonNull(factory, "factory").generate(jsonType));
    }

    public JsonType<A> type() {
        return type;
    }

    public JsonObjectSchema schema() {
        return schema;
    }

    public A decode(McpJsonMapper mapper, Map<String, Object> arguments) {
        McpJsonMapper requiredMapper = Objects.requireNonNull(mapper, "mapper");
        Map<String, Object> requiredArguments = Objects.requireNonNull(arguments, "arguments");
        schema.validateInputTypes(requiredArguments);
        try {
            return type.decode(requiredMapper, requiredArguments);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(deserializationMessage(exception), exception);
        }
    }

    private static String deserializationMessage(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ToolInputValidationException validation) {
                String message = validation.getMessage();
                if (message != null && !message.isBlank()) {
                    return message;
                }
                break;
            }
            current = current.getCause();
        }
        return "Unable to deserialize tool input";
    }
}

package dev.mcdevmcp.mcp.tool.api;

import java.io.Serial;

/**
 * Marks a deliberately user-facing semantic input validation failure.
 */
public final class ToolInputValidationException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public ToolInputValidationException(String message) {
        super(requireMessage(message));
    }

    public ToolInputValidationException(String message, Throwable cause) {
        super(requireMessage(message), cause);
    }

    private static String requireMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Tool input validation message must not be blank");
        }
        return message;
    }
}

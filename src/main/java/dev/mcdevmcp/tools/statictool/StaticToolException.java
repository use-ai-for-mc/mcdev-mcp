package dev.mcdevmcp.tools.statictool;

import java.io.Serial;

class StaticToolException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    StaticToolException(String message) {
        super(message);
    }
}

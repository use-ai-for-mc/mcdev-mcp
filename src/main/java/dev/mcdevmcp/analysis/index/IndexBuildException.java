package dev.mcdevmcp.analysis.index;

import java.io.Serial;

public final class IndexBuildException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    public IndexBuildException(String message) {
        super(message);
    }

    public IndexBuildException(String message, Throwable cause) {
        super(message, cause);
    }
}

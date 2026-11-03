package dev.mcdevmcp.storage.migration;

import java.io.IOException;
import java.io.Serial;
import java.nio.file.Path;

public final class InvalidSourceStampException extends IOException {
    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidSourceStampException(Path stamp, Exception cause) {
        super("Invalid source preparation stamp: " + stamp, cause);
    }
}

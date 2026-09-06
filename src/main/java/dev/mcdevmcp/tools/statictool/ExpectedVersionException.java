package dev.mcdevmcp.tools.statictool;

import java.io.Serial;

final class ExpectedVersionException extends StaticToolException {
    @Serial
    private static final long serialVersionUID = 1L;

    ExpectedVersionException(String message) {
        super(message);
    }
}

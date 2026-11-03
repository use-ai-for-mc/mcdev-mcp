package dev.mcdevmcp.mcp.transport;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

final class NonClosingOutputStream extends FilterOutputStream {
    NonClosingOutputStream(OutputStream output) {
        super(Objects.requireNonNull(output, "output"));
    }

    @Override
    public void close() throws IOException {
        flush();
    }
}

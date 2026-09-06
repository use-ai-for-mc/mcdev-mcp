package dev.mcdevmcp.mcp.transport;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

final class EofTrackingInputStream extends FilterInputStream {
    private final CountDownLatch inputClosed;

    EofTrackingInputStream(InputStream input, CountDownLatch inputClosed) {
        super(Objects.requireNonNull(input, "input"));
        this.inputClosed = Objects.requireNonNull(inputClosed, "inputClosed");
    }

    @Override
    public int read() throws IOException {
        return signalEndOfStream(super.read());
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public int read(byte[] bytes, int offset, int length) throws IOException {
        return signalEndOfStream(super.read(bytes, offset, length));
    }

    @Override
    public void close() {
        inputClosed.countDown();
    }

    private int signalEndOfStream(int value) {
        if (value < 0) {
            inputClosed.countDown();
        }
        return value;
    }
}

package dev.mcdevmcp.storage.h2;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Objects;

@SuppressWarnings("ClassCanBeRecord")
final class DatabaseFileHandle implements AutoCloseable {
    private final FileChannel channel;
    private final boolean reservationCreated;

    DatabaseFileHandle(FileChannel channel, boolean reservationCreated) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.reservationCreated = reservationCreated;
    }

    FileChannel channel() {
        return channel;
    }

    boolean reservationCreated() {
        return reservationCreated;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
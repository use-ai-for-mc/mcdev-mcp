package dev.mcdevmcp.storage.bundle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Objects;

public final class ChannelRangeInputStream extends InputStream {
    private final FileChannel channel;
    private long remaining;

    public ChannelRangeInputStream(FileChannel channel, long offset, long length) throws IOException {
        this.channel = Objects.requireNonNull(channel, "channel");
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("Channel range must not be negative");
        }
        channel.position(offset);
        remaining = length;
    }

    @Override
    public int read() throws IOException {
        byte[] single = new byte[1];
        int read = read(single, 0, 1);
        return read < 0 ? -1 : Byte.toUnsignedInt(single[0]);
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (remaining == 0) {
            return -1;
        }
        int requested = (int) Math.min(length, remaining);
        int read = channel.read(ByteBuffer.wrap(bytes, offset, requested));
        if (read < 0) {
            throw new IOException("Unexpected end of bundle artifact range");
        }
        remaining -= read;
        return read;
    }
}

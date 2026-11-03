package dev.mcdevmcp.storage.bundle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public final class JsonlLineReader implements AutoCloseable {
    private final InputStream input;
    private final int maximumLineBytes;
    private final byte[] buffer = new byte[8 * 1024];
    private int offset;
    private int limit;
    private long bytesRead;
    private boolean finished;

    public JsonlLineReader(InputStream input, int maximumLineBytes) {
        this.input = Objects.requireNonNull(input, "input");
        if (maximumLineBytes < 1) {
            throw new IllegalArgumentException("maximumLineBytes must be positive");
        }
        this.maximumLineBytes = maximumLineBytes;
    }

    public byte[] next() throws IOException {
        if (finished) {
            return null;
        }
        var line = new ByteArrayOutputStream();
        while (true) {
            int value = readByte();
            if (value < 0) {
                finished = true;
                if (line.size() == 0) {
                    return null;
                }
                throw new IOException("JSONL artifact is missing its final LF");
            }
            bytesRead = Math.addExact(bytesRead, 1);
            if (value == '\n') {
                byte[] bytes = line.toByteArray();
                if (bytes.length == 0) {
                    throw new IOException("JSONL artifact contains an empty record");
                }
                if (bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf) {
                    throw new IOException("JSONL artifact must not contain a UTF-8 BOM");
                }
                return bytes;
            }
            if (value == '\r') {
                throw new IOException("JSONL artifact must use LF-only line endings");
            }
            if (line.size() == maximumLineBytes) {
                throw new IOException("JSONL record exceeds " + maximumLineBytes + " bytes");
            }
            line.write(value);
        }
    }

    public long bytesRead() {
        return bytesRead;
    }

    private int readByte() throws IOException {
        if (offset == limit) {
            limit = input.read(buffer);
            offset = 0;
            if (limit < 0) {
                return -1;
            }
        }
        return Byte.toUnsignedInt(buffer[offset++]);
    }

    @Override
    public void close() throws IOException {
        input.close();
    }
}

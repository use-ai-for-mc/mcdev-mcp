package dev.mcdevmcp.storage.callgraph;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;

import java.io.IOException;
import java.util.Arrays;

final class CallgraphJson {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();

    private CallgraphJson() {
    }

    static byte[] bytes(Object value) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(value);
        for (byte current : bytes) {
            if (current == '\r' || current == '\n') {
                throw new IOException("JSON mapper emitted non-compact JSON");
            }
        }
        return bytes;
    }

    static byte[] line(Object value) throws IOException {
        byte[] bytes = bytes(value);
        byte[] line = Arrays.copyOf(bytes, Math.addExact(bytes.length, 1));
        line[bytes.length] = '\n';
        return line;
    }

    static <T> T read(byte[] bytes, Class<T> type) throws IOException {
        return MAPPER.readValue(bytes, type);
    }

    static <T> T readCanonical(byte[] bytes, Class<T> type, String label) throws IOException {
        T value = read(bytes, type);
        if (!Arrays.equals(bytes, bytes(value))) {
            throw new IOException(label + " does not use the exact canonical typed JSON schema");
        }
        return value;
    }
}
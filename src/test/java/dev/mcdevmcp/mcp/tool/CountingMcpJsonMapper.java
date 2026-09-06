package dev.mcdevmcp.mcp.tool;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;

import java.io.IOException;

public final class CountingMcpJsonMapper implements McpJsonMapper {
    private final McpJsonMapper delegate;
    private int convertValueCalls;

    public CountingMcpJsonMapper(McpJsonMapper delegate) {
        this.delegate = delegate;
    }

    public int convertValueCalls() {
        return convertValueCalls;
    }

    @Override
    public <T> T readValue(String content, Class<T> type) throws IOException {
        return delegate.readValue(content, type);
    }

    @Override
    public <T> T readValue(byte[] content, Class<T> type) throws IOException {
        return delegate.readValue(content, type);
    }

    @Override
    public <T> T readValue(String content, TypeRef<T> type) throws IOException {
        return delegate.readValue(content, type);
    }

    @Override
    public <T> T readValue(byte[] content, TypeRef<T> type) throws IOException {
        return delegate.readValue(content, type);
    }

    @Override
    public <T> T convertValue(Object value, Class<T> type) {
        convertValueCalls++;
        return delegate.convertValue(value, type);
    }

    @Override
    public <T> T convertValue(Object value, TypeRef<T> type) {
        convertValueCalls++;
        return delegate.convertValue(value, type);
    }

    @Override
    public String writeValueAsString(Object value) throws IOException {
        return delegate.writeValueAsString(value);
    }

    @Override
    public byte[] writeValueAsBytes(Object value) throws IOException {
        return delegate.writeValueAsBytes(value);
    }
}

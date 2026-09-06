package dev.mcdevmcp.support;

import io.modelcontextprotocol.json.McpJsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class JsonResourceReader {
    private final McpJsonMapper mapper;

    public JsonResourceReader(McpJsonMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public <T> T read(String resource, Class<T> type) {
        Objects.requireNonNull(type, "type");
        try (InputStream input = resource(resource)) {
            return mapper.readValue(input.readAllBytes(), type);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read JSON resource: " + resource, exception);
        }
    }

    public String readText(String resource) {
        try (InputStream input = resource(resource)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read resource: " + resource, exception);
        }
    }

    private InputStream resource(String resource) {
        Objects.requireNonNull(resource, "resource");
        InputStream input = JsonResourceReader.class.getResourceAsStream(resource);
        if (input == null) {
            throw new IllegalStateException("Missing classpath resource: " + resource);
        }
        return input;
    }
}
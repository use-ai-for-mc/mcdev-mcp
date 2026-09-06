package dev.mcdevmcp.mcp.tool.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

record WireVersion(String value) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    WireVersion {
        if (value.isBlank()) {
            throw new IllegalArgumentException("Version must not be blank");
        }
    }

    @JsonValue
    String wireValue() {
        return value;
    }
}
package dev.mcdevmcp.mcp.tool.api;

import com.fasterxml.jackson.annotation.JsonProperty;

enum BodyEnum {
    @JsonProperty("active") ACTIVE {
        @Override
        boolean active() {
            return true;
        }
    };

    abstract boolean active();
}
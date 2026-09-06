package dev.mcdevmcp.mcp.tool.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

enum WireMode {
    FAST("quick"), THOROUGH("deep");

    private final String wireValue;

    WireMode(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    static WireMode fromWireValue(String wireValue) {
        for (WireMode mode : values()) {
            if (mode.wireValue.equals(wireValue)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown wire mode: " + wireValue);
    }

    @JsonValue
    String wireValue() {
        return wireValue;
    }
}
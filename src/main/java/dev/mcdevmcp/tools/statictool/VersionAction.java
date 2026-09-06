package dev.mcdevmcp.tools.statictool;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

enum VersionAction {
    set, list;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static VersionAction fromWireValue(String value) {
        return switch (value) {
            case "set" -> set;
            case "list" -> list;
            default -> throw new IllegalArgumentException("Unsupported version action: " + value);
        };
    }

    @JsonValue
    public String wireValue() {
        return name();
    }
}

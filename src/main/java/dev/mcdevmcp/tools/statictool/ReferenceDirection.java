package dev.mcdevmcp.tools.statictool;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

enum ReferenceDirection {
    callers, callees, readers, writers;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ReferenceDirection fromWireValue(String value) {
        return switch (value) {
            case "callers" -> callers;
            case "callees" -> callees;
            case "readers" -> readers;
            case "writers" -> writers;
            default -> throw new IllegalArgumentException("Unsupported reference direction: " + value);
        };
    }

    @JsonValue
    public String wireValue() {
        return name();
    }

    boolean field() {
        return this == readers || this == writers;
    }
}

package dev.mcdevmcp.tools.statictool;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

enum ClassView {
    summary, methods, fields, full;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ClassView fromWireValue(String value) {
        return switch (value) {
            case "summary" -> summary;
            case "methods" -> methods;
            case "fields" -> fields;
            case "full" -> full;
            default -> throw new IllegalArgumentException("Unsupported class view: " + value);
        };
    }

    @JsonValue
    public String wireValue() {
        return name();
    }
}

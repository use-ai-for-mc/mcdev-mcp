package dev.mcdevmcp.tools.statictool;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

enum HierarchyDirection {
    subclasses, implementors;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static HierarchyDirection fromWireValue(String value) {
        return switch (value) {
            case "subclasses" -> subclasses;
            case "implementors" -> implementors;
            default -> throw new IllegalArgumentException("Unsupported hierarchy direction: " + value);
        };
    }

    @JsonValue
    public String wireValue() {
        return name();
    }
}

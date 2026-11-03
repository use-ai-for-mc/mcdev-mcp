package dev.mcdevmcp.tools.statictool;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

enum SearchType {
    CLASS("class"), METHOD("method"), FIELD("field");

    private final String wireValue;

    SearchType(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static SearchType fromWireValue(String value) {
        return switch (value) {
            case "class" -> CLASS;
            case "method" -> METHOD;
            case "field" -> FIELD;
            default -> throw new IllegalArgumentException("Unsupported search type: " + value);
        };
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}

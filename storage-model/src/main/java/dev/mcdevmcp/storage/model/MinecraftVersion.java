package dev.mcdevmcp.storage.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import dev.mcdevmcp.mcp.tool.api.ToolInputValidationException;

public value record MinecraftVersion(String value) {
    public MinecraftVersion {
        PortablePathComponent.requireValid(value, "Invalid Minecraft version path component: ");
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static MinecraftVersion fromJson(String value) {
        if (value.isEmpty()) {
            return null;
        }
        try {
            return new MinecraftVersion(value);
        } catch (IllegalArgumentException exception) {
            throw new ToolInputValidationException(exception.getMessage(), exception);
        }
    }

    @JsonValue
    public String wireValue() {
        return value;
    }
}

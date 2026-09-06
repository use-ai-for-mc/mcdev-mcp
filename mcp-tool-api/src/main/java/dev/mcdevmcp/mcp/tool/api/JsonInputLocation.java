package dev.mcdevmcp.mcp.tool.api;

import java.util.Objects;

final class JsonInputLocation {
    private final String display;
    private final boolean root;

    private JsonInputLocation(String display, boolean root) {
        this.display = Objects.requireNonNull(display, "display");
        this.root = root;
    }

    static JsonInputLocation root() {
        return new JsonInputLocation("input", true);
    }

    JsonInputLocation property(String name) {
        String requiredName = Objects.requireNonNull(name, "name");
        return new JsonInputLocation(root ? requiredName : display + '.' + requiredName, false);
    }

    JsonInputLocation element(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("JSON array index must not be negative");
        }
        return new JsonInputLocation(display + '[' + index + ']', false);
    }

    @Override
    public String toString() {
        return display;
    }
}

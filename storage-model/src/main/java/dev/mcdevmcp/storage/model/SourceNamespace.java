package dev.mcdevmcp.storage.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;
import java.util.Objects;

public enum SourceNamespace {
    MINECRAFT("minecraft"), FABRIC("fabric");

    private final String wireName;

    SourceNamespace(String wireName) {
        this.wireName = wireName;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static SourceNamespace fromWireName(String wireName) {
        Objects.requireNonNull(wireName, "wireName");
        for (SourceNamespace namespace : values()) {
            if (namespace.wireName.equals(wireName.toLowerCase(Locale.ROOT))) {
                return namespace;
            }
        }
        throw new IllegalArgumentException("Unsupported source namespace: " + wireName);
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @Override
    public String toString() {
        return wireName;
    }
}

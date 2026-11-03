package dev.mcdevmcp.storage.model;

import javax.lang.model.element.ElementKind;
import java.util.Map;
import java.util.Objects;

public final class ElementKindCodec {
    private static final Map<ElementKind, String> WIRE_NAMES = Map.of(ElementKind.CLASS, "class", ElementKind.INTERFACE, "interface", ElementKind.ENUM, "enum", ElementKind.RECORD, "record", ElementKind.ANNOTATION_TYPE, "annotation");

    private ElementKindCodec() {
    }

    public static ElementKind fromWireName(String wireName) {
        Objects.requireNonNull(wireName, "wireName");
        return WIRE_NAMES.entrySet().stream().filter(entry -> entry.getValue().equals(wireName)).map(Map.Entry::getKey).findFirst().orElseThrow(() -> new IllegalArgumentException("Unsupported type kind: " + wireName));
    }

    public static String wireName(ElementKind kind) {
        String wireName = WIRE_NAMES.get(Objects.requireNonNull(kind, "kind"));
        if (wireName == null) {
            throw new IllegalArgumentException("Unsupported type kind: " + kind);
        }
        return wireName;
    }
}
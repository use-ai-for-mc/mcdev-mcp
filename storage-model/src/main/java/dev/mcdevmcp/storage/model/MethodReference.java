package dev.mcdevmcp.storage.model;

import java.util.Objects;

public value record MethodReference(String className, String methodName, String descriptor, Integer lineNumber, long edgeId) {
    public MethodReference {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(methodName, "methodName");
        if (edgeId < 1) {
            throw new IllegalArgumentException("edgeId must be positive");
        }
    }

    public String displayName() {
        return className + "." + methodName + (descriptor == null ? "" : descriptor);
    }
}
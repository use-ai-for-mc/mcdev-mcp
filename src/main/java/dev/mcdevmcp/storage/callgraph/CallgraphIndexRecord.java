package dev.mcdevmcp.storage.callgraph;

import java.util.Objects;

public value record CallgraphIndexRecord(String className, String methodName, long byteOffset, long byteLength, long rowCount) {
    public CallgraphIndexRecord {
        className = requireIdentity(className, "className");
        methodName = requireIdentity(methodName, "methodName");
        if (byteOffset < 0 || byteLength < 1 || rowCount < 1) {
            throw new IllegalArgumentException("Callgraph index ranges and row counts must be positive");
        }
    }

    private static String requireIdentity(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
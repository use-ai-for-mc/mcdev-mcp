package dev.mcdevmcp.storage.callgraph;

import java.util.Objects;

public record CallgraphDataRecord(long edgeId, String callerClass, String callerMethod, String callerDescriptor, String calleeClass, String calleeMethod, String calleeDescriptor, Integer lineNumber) {
    public CallgraphDataRecord {
        if (edgeId < 1) {
            throw new IllegalArgumentException("edgeId must be positive");
        }
        callerClass = requireIdentity(callerClass, "callerClass");
        callerMethod = requireIdentity(callerMethod, "callerMethod");
        calleeClass = requireIdentity(calleeClass, "calleeClass");
        calleeMethod = requireIdentity(calleeMethod, "calleeMethod");
    }

    private static String requireIdentity(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
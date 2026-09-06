package dev.mcdevmcp.analysis.callgraph;

import java.lang.constant.MethodTypeDesc;
import java.util.Objects;

public record CallEdge(String callerClass, String callerMethod, String callerDescriptor, String calleeClass, String calleeMethod, String calleeDescriptor, Integer lineNumber, long encounterOrder) {
    public CallEdge {
        callerClass = requireIdentity(callerClass, "callerClass");
        callerMethod = requireIdentity(callerMethod, "callerMethod");
        callerDescriptor = requireDescriptor(callerDescriptor, "callerDescriptor");
        calleeClass = requireIdentity(calleeClass, "calleeClass");
        calleeMethod = requireIdentity(calleeMethod, "calleeMethod");
        calleeDescriptor = requireDescriptor(calleeDescriptor, "calleeDescriptor");
        if (encounterOrder < 0) {
            throw new IllegalArgumentException("encounterOrder must not be negative");
        }
    }

    private static String requireIdentity(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String requireDescriptor(String value, String name) {
        Objects.requireNonNull(value, name);
        try {
            MethodTypeDesc.ofDescriptor(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(name + " must be a canonical method descriptor: " + value, exception);
        }
        return value;
    }
}
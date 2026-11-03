package dev.mcdevmcp.analysis.callgraph;

import dev.mcdevmcp.storage.callgraph.CallgraphEdgeKind;

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.Objects;

/**
 * A call from one method to another, or a field read or written by a method; a field's descriptor is
 * its type.
 */
public value record CallEdge(CallgraphEdgeKind kind, String callerClass, String callerMethod, String callerDescriptor, String calleeClass, String calleeMethod, String calleeDescriptor, Integer lineNumber, long encounterOrder) {
    public CallEdge {
        Objects.requireNonNull(kind, "kind");
        callerClass = requireIdentity(callerClass, "callerClass");
        callerMethod = requireIdentity(callerMethod, "callerMethod");
        callerDescriptor = requireDescriptor(callerDescriptor, "callerDescriptor");
        calleeClass = requireIdentity(calleeClass, "calleeClass");
        calleeMethod = requireIdentity(calleeMethod, "calleeMethod");
        calleeDescriptor = kind == CallgraphEdgeKind.CALL ? requireDescriptor(calleeDescriptor, "calleeDescriptor") : requireFieldDescriptor(calleeDescriptor);
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

    private static String requireFieldDescriptor(String value) {
        Objects.requireNonNull(value, "calleeDescriptor");
        try {
            if (value.equals("V") || !ClassDesc.ofDescriptor(value).descriptorString().equals(value)) {
                throw new IllegalArgumentException("Not a field type");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("calleeDescriptor must be a canonical field descriptor: " + value, exception);
        }
        return value;
    }
}

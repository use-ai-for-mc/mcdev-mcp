package dev.mcdevmcp.analysis.index.pipeline;


import java.util.Objects;

record ParsedParameter(int ordinal, String name, String type, boolean varargs, SourceRange range) {
    ParsedParameter {
        if (ordinal < 0) {
            throw new IllegalArgumentException("Parameter ordinal must not be negative");
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(range, "range");
    }
}
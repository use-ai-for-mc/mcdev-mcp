package dev.mcdevmcp.analysis.index.pipeline;


import javax.lang.model.element.Modifier;
import java.util.Objects;
import java.util.Set;

record ParsedField(int ordinal, String name, String type, Set<Modifier> modifiers, SourceRange range) {
    ParsedField {
        if (ordinal < 0) {
            throw new IllegalArgumentException("Field ordinal must not be negative");
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        modifiers = Set.copyOf(modifiers);
        Objects.requireNonNull(range, "range");
    }
}
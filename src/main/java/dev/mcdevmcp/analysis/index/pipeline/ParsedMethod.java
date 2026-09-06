package dev.mcdevmcp.analysis.index.pipeline;


import javax.lang.model.element.Modifier;
import java.lang.constant.MethodTypeDesc;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

record ParsedMethod(int ordinal, String name, MethodTypeDesc descriptor, Optional<String> returnType, Set<Modifier> modifiers, boolean constructor, List<ParsedParameter> parameters, SourceRange range) {
    ParsedMethod {
        if (ordinal < 0) {
            throw new IllegalArgumentException("Method ordinal must not be negative");
        }
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(descriptor, "descriptor");
        returnType = Optional.ofNullable(returnType).orElseThrow(() -> new NullPointerException("returnType"));
        modifiers = Set.copyOf(modifiers);
        parameters = List.copyOf(parameters);
        Objects.requireNonNull(range, "range");
    }
}
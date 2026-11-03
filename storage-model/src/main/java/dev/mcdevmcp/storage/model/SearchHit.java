package dev.mcdevmcp.storage.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public value record SearchHit(SearchHitKind kind, ClassSymbol owner, Optional<FieldSymbol> field, Optional<MethodSymbol> method, List<ParameterSymbol> parameters, int fieldCount, int methodCount) {
    public SearchHit {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(owner, "owner");
        field = Optional.ofNullable(field).orElseThrow(() -> new NullPointerException("field"));
        method = Optional.ofNullable(method).orElseThrow(() -> new NullPointerException("method"));
        parameters = List.copyOf(parameters);
        if ((kind == SearchHitKind.FIELD) != field.isPresent() || (kind == SearchHitKind.METHOD) != method.isPresent()) {
            throw new IllegalArgumentException("Search hit payload does not match kind");
        }
    }
}
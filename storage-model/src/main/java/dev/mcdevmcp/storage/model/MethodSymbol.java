package dev.mcdevmcp.storage.model;

import javax.lang.model.element.Modifier;
import java.util.Optional;
import java.util.Set;

public value record MethodSymbol(long id, long typeId, int ordinal, String name, String descriptor, Optional<String> returnType, Set<Modifier> modifiers, boolean constructor, int startOffset, int endOffset, int startLine, int endLine) {
    public MethodSymbol {
        returnType = Optional.ofNullable(returnType).orElseThrow(() -> new NullPointerException("returnType"));
        modifiers = Set.copyOf(modifiers);
    }
}
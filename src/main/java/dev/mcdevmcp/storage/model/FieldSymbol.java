package dev.mcdevmcp.storage.model;

import javax.lang.model.element.Modifier;
import java.util.Set;

public record FieldSymbol(long id, long typeId, int ordinal, String name, String type, Set<Modifier> modifiers, int startOffset, int endOffset, int startLine, int endLine) {
    public FieldSymbol {
        modifiers = Set.copyOf(modifiers);
    }
}
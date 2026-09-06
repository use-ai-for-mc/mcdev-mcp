package dev.mcdevmcp.analysis.index.pipeline;

import javax.lang.model.element.Modifier;
import java.util.List;

record IndexedFieldSnapshot(long id, long typeId, int ordinal, String name, String type, List<Modifier> modifiers, SourceRange range) {
    IndexedFieldSnapshot {
        modifiers = List.copyOf(modifiers);
    }
}
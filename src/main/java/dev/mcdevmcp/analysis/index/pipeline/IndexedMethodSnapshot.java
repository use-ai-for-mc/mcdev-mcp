package dev.mcdevmcp.analysis.index.pipeline;

import javax.lang.model.element.Modifier;
import java.lang.constant.MethodTypeDesc;
import java.util.List;
import java.util.Optional;

record IndexedMethodSnapshot(long id, long typeId, int ordinal, String name, MethodTypeDesc descriptor, Optional<String> returnType, List<Modifier> modifiers, boolean constructor, SourceRange range) {
    IndexedMethodSnapshot {
        modifiers = List.copyOf(modifiers);
    }
}
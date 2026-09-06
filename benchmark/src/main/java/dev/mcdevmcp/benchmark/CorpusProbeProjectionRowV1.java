package dev.mcdevmcp.benchmark;

import dev.mcdevmcp.storage.model.ClassSymbol;
import dev.mcdevmcp.storage.model.ElementKindCodec;
import dev.mcdevmcp.storage.model.FieldSymbol;
import dev.mcdevmcp.storage.model.MethodReference;
import dev.mcdevmcp.storage.model.MethodSymbol;
import dev.mcdevmcp.storage.model.ParameterSymbol;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Fixed-shape row in the version-one cross-language semantic probe projection.
 */
public record CorpusProbeProjectionRowV1(String symbolKind, String superclassBinaryName, List<String> interfaceBinaryNames, String sourcePath, String memberName, String memberType, String returnType, List<CorpusProbeParameterV1> parameters, List<String> modifiers, Integer lineStart, Integer lineEnd, String referenceClassName, String referenceMethodName, String referenceDescriptor, Integer referenceLineNumber) {
    static final Comparator<CorpusProbeProjectionRowV1> REFERENCE_ORDER = Comparator.comparing(CorpusProbeProjectionRowV1::referenceClassName).thenComparing(CorpusProbeProjectionRowV1::referenceMethodName).thenComparing(CorpusProbeProjectionRowV1::referenceDescriptor).thenComparing(CorpusProbeProjectionRowV1::referenceLineNumber, Comparator.nullsFirst(Comparator.naturalOrder()));

    public CorpusProbeProjectionRowV1 {
        interfaceBinaryNames = List.copyOf(interfaceBinaryNames);
        parameters = List.copyOf(parameters);
        modifiers = List.copyOf(modifiers);
    }

    static CorpusProbeProjectionRowV1 classRow(ClassSymbol value) {
        return new CorpusProbeProjectionRowV1(ElementKindCodec.wireName(value.kind()), value.superclassBinaryName().orElse(null), value.interfaceBinaryNames(), portable(value.sourcePath().toString()), null, null, null, List.of(), List.of(), null, null, null, null, null, null);
    }

    static CorpusProbeProjectionRowV1 fieldRow(FieldSymbol value) {
        return new CorpusProbeProjectionRowV1(null, null, List.of(), null, value.name(), value.type(), null, List.of(), modifiers(value), null, null, null, null, null, null);
    }

    static CorpusProbeProjectionRowV1 methodRow(MethodSymbol value, List<ParameterSymbol> parameters) {
        return new CorpusProbeProjectionRowV1(null, null, List.of(), null, value.name(), null, value.returnType().orElse(null), parameters.stream().map(CorpusProbeParameterV1::from).toList(), value.modifiers().stream().map(modifier -> modifier.name().toLowerCase(Locale.ROOT)).sorted().toList(), value.startLine(), value.endLine(), null, null, null, null);
    }

    static CorpusProbeProjectionRowV1 referenceRow(MethodReference value) {
        return new CorpusProbeProjectionRowV1(null, null, List.of(), null, null, null, null, List.of(), List.of(), null, null, value.className(), value.methodName(), value.descriptor() == null ? "" : value.descriptor(), value.lineNumber());
    }

    private static List<String> modifiers(FieldSymbol value) {
        return value.modifiers().stream().map(modifier -> modifier.name().toLowerCase(Locale.ROOT)).sorted().toList();
    }

    private static String portable(String path) {
        return path.replace('\\', '/');
    }
}

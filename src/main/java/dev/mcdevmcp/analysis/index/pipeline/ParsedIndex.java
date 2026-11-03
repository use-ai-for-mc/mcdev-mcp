package dev.mcdevmcp.analysis.index.pipeline;

import java.util.*;

value record ParsedIndex(List<ParsedType> types, List<String> parsedCompilationUnits, List<IndexDiagnostic> diagnostics) {
    ParsedIndex {
        List<ParsedType> sortedTypes = new ArrayList<>(types);
        sortedTypes.sort(Comparator.comparing(ParsedType::sourceRoot).thenComparing(type -> new PortablePath(type.sourcePath())).thenComparingInt(type -> type.range().startOffset()).thenComparing(ParsedType::binaryName));
        types = List.copyOf(sortedTypes);
        parsedCompilationUnits = List.copyOf(new TreeSet<>(parsedCompilationUnits));
        List<IndexDiagnostic> sortedDiagnostics = new ArrayList<>(diagnostics);
        sortedDiagnostics.sort(IndexDiagnostic.ORDERING);
        diagnostics = List.copyOf(new LinkedHashSet<>(sortedDiagnostics));
    }
}
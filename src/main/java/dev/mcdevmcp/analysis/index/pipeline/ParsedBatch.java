package dev.mcdevmcp.analysis.index.pipeline;


import java.util.List;

record ParsedBatch(List<ParsedType> types, List<String> parsedCompilationUnits, List<IndexDiagnostic> diagnostics) {
    ParsedBatch {
        types = List.copyOf(types);
        parsedCompilationUnits = List.copyOf(parsedCompilationUnits);
        diagnostics = List.copyOf(diagnostics);
    }
}
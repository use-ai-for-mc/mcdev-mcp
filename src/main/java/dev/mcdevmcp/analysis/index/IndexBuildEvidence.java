package dev.mcdevmcp.analysis.index;

import java.util.List;
import java.util.Objects;

/**
 * Stable accounting evidence emitted by the Javac indexing pipeline.
 */
public record IndexBuildEvidence(List<String> discoveredCompilationUnits, List<String> parsedCompilationUnits, List<String> typedCompilationUnits, List<String> typeFreeCompilationUnits, List<String> diagnostics) {
    public IndexBuildEvidence {
        discoveredCompilationUnits = stable(discoveredCompilationUnits, "discoveredCompilationUnits");
        parsedCompilationUnits = stable(parsedCompilationUnits, "parsedCompilationUnits");
        typedCompilationUnits = stable(typedCompilationUnits, "typedCompilationUnits");
        typeFreeCompilationUnits = stable(typeFreeCompilationUnits, "typeFreeCompilationUnits");
        diagnostics = stable(diagnostics, "diagnostics");
    }

    private static List<String> stable(List<String> values, String name) {
        return List.copyOf(Objects.requireNonNull(values, name).stream().sorted().toList());
    }
}
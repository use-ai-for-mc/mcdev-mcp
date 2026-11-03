package dev.mcdevmcp.analysis.index.pipeline;

import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IndexDiagnosticTest {
    private static IndexDiagnostic diagnostic(Path path) {
        return new IndexDiagnostic(Diagnostic.Kind.ERROR, Optional.empty(), path, 0, 1, 1, 1, "code", "message");
    }

    @Test
    void ordersByPortableSlashSeparatedSourcePath() {
        IndexDiagnostic nested = diagnostic(Path.of("a", "B.java"));
        IndexDiagnostic sibling = diagnostic(Path.of("aZ.java"));
        List<IndexDiagnostic> diagnostics = new ArrayList<>(List.of(sibling, nested));

        diagnostics.sort(IndexDiagnostic.ORDERING);

        assertEquals(List.of(nested, sibling), diagnostics);
    }
}

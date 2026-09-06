package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.index.IndexBuildException;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;

final class JavacDiagnostics {
    private static final Set<String> RECOVERABLE_DECLARATION_ERRORS = Set.of("compiler.err.override.weaker.access", "compiler.err.type.annotation.inadmissible");

    private JavacDiagnostics() {
    }

    static List<IndexDiagnostic> classifyDiagnostics(List<Diagnostic<? extends JavaFileObject>> diagnostics, SourceCorpus corpus, Map<URI, List<OffsetRange>> executableBodies, Set<URI> ownedSources) throws IndexBuildException {
        List<IndexDiagnostic> retained = new ArrayList<>();
        List<IndexDiagnostic> fatal = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
            IndexDiagnostic converted = diagnostic(diagnostic, corpus);
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                List<OffsetRange> ranges = diagnostic.getSource() == null ? List.of() : executableBodies.getOrDefault(diagnostic.getSource().toUri(), List.of());
                boolean insideBody = diagnostic.getStartPosition() >= 0 && ranges.stream().anyMatch(range -> range.contains(diagnostic.getStartPosition(), diagnostic.getEndPosition()));
                if (!insideBody && !RECOVERABLE_DECLARATION_ERRORS.contains(diagnostic.getCode())) {
                    fatal.add(converted);
                    continue;
                }
            }
            if (diagnostic.getSource() == null || ownedSources.contains(diagnostic.getSource().toUri())) {
                retained.add(converted);
            }
        }
        if (!fatal.isEmpty()) {
            fatal.sort(IndexDiagnostic.ORDERING);
            throw new IndexBuildException("Fatal Javac diagnostic: " + fatal.getFirst().display());
        }
        retained.sort(IndexDiagnostic.ORDERING);
        return List.copyOf(retained);
    }

    static void failOnSyntaxErrors(List<Diagnostic<? extends JavaFileObject>> diagnostics, SourceCorpus corpus) throws IndexBuildException {
        List<IndexDiagnostic> errors = diagnostics.stream().filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR).map(diagnostic -> diagnostic(diagnostic, corpus)).sorted(IndexDiagnostic.ORDERING).toList();
        if (!errors.isEmpty()) {
            throw new IndexBuildException("Fatal Javac syntax diagnostic: " + errors.getFirst().display());
        }
    }

    private static IndexDiagnostic diagnostic(Diagnostic<? extends JavaFileObject> diagnostic, SourceCorpus corpus) {
        Optional<DecodedSource> source = diagnostic.getSource() == null ? Optional.empty() : Optional.of(corpus.require(diagnostic.getSource().toUri()));
        Path sourcePath = source.map(DecodedSource::relativePath).orElse(Path.of("compiler"));
        return new IndexDiagnostic(diagnostic.getKind(), source.map(DecodedSource::root), sourcePath, diagnostic.getStartPosition(), diagnostic.getEndPosition(), diagnostic.getLineNumber(), diagnostic.getColumnNumber(), diagnostic.getCode(), diagnostic.getMessage(Locale.ROOT));
    }
}

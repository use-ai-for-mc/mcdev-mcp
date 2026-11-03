package dev.mcdevmcp.analysis.index.pipeline;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.SourcePositions;
import dev.mcdevmcp.analysis.index.IndexBuildException;
import dev.mcdevmcp.support.ExecutableBodies;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;

final class JavacDiagnostics {
    private static final Set<String> RECOVERABLE_DECLARATION_ERRORS = Set.of("compiler.err.override.weaker.access", "compiler.err.type.annotation.inadmissible");

    private JavacDiagnostics() {
    }

    static Map<URI, ExecutableBodies> executableBodies(Collection<? extends CompilationUnitTree> units, SourcePositions positions) {
        Map<URI, ExecutableBodies> bodies = new HashMap<>();
        for (CompilationUnitTree unit : units) {
            bodies.put(unit.getSourceFile().toUri(), ExecutableBodies.of(unit, positions));
        }
        return bodies;
    }

    private static ExecutableBodies bodiesOf(Diagnostic<? extends JavaFileObject> diagnostic, Map<URI, ExecutableBodies> executableBodies) {
        return diagnostic.getSource() == null ? ExecutableBodies.none() : executableBodies.getOrDefault(diagnostic.getSource().toUri(), ExecutableBodies.none());
    }

    static List<IndexDiagnostic> classifyDiagnostics(List<Diagnostic<? extends JavaFileObject>> diagnostics, SourceCorpus corpus, Map<URI, ExecutableBodies> executableBodies, Set<URI> ownedSources) throws IndexBuildException {
        List<IndexDiagnostic> retained = new ArrayList<>();
        List<IndexDiagnostic> fatal = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
            IndexDiagnostic converted = diagnostic(diagnostic, corpus);
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                boolean insideBody = bodiesOf(diagnostic, executableBodies).contain(diagnostic);
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

    /**
     * Fails on a syntax error that damages a declaration. One confined to an executable body is
     * what a decompiler leaves where it could not express the code as Java; the declarations
     * around it are intact, so it is retained with the other diagnostics instead.
     */
    static void failOnSyntaxErrors(List<Diagnostic<? extends JavaFileObject>> diagnostics, SourceCorpus corpus, Map<URI, ExecutableBodies> executableBodies) throws IndexBuildException {
        List<IndexDiagnostic> errors = diagnostics.stream().filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR && bodiesOf(diagnostic, executableBodies).escapedBy(diagnostic)).map(diagnostic -> diagnostic(diagnostic, corpus)).sorted(IndexDiagnostic.ORDERING).toList();
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

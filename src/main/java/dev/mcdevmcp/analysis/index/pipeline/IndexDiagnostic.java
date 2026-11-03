package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.index.SourceRoot;

import javax.tools.Diagnostic;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

value record IndexDiagnostic(Diagnostic.Kind kind, Optional<SourceRoot> sourceRoot, Path sourcePath, long startOffset, long endOffset, long line, long column, String code, String message) {
    static final Comparator<IndexDiagnostic> ORDERING = IndexDiagnostic::compare;

    IndexDiagnostic {
        Objects.requireNonNull(kind, "kind");
        sourceRoot = Optional.ofNullable(sourceRoot).orElseThrow(() -> new NullPointerException("sourceRoot"));
        sourcePath = Objects.requireNonNull(sourcePath, "sourcePath").normalize();
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }

    private static int compare(IndexDiagnostic first, IndexDiagnostic second) {
        int rootOrder;
        if (first.sourceRoot.isPresent() && second.sourceRoot.isPresent()) {
            rootOrder = first.sourceRoot.orElseThrow().compareTo(second.sourceRoot.orElseThrow());
        }
        else {
            rootOrder = Boolean.compare(first.sourceRoot.isPresent(), second.sourceRoot.isPresent());
        }
        if (rootOrder != 0) {
            return rootOrder;
        }
        int sourceOrder = new PortablePath(first.sourcePath).compareTo(new PortablePath(second.sourcePath));
        if (sourceOrder != 0) {
            return sourceOrder;
        }
        int offsetOrder = Long.compare(first.startOffset, second.startOffset);
        if (offsetOrder != 0) {
            return offsetOrder;
        }
        int kindOrder = first.kind.compareTo(second.kind);
        if (kindOrder != 0) {
            return kindOrder;
        }
        int codeOrder = first.code.compareTo(second.code);
        return codeOrder != 0 ? codeOrder : first.message.compareTo(second.message);
    }

    String display() {
        return new PortablePath(sourcePath).value() + ":" + line + ":" + column + ": " + message + " [" + code + "]";
    }

}
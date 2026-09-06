package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.index.SourceRoot;

import javax.lang.model.element.ElementKind;
import java.lang.constant.ClassDesc;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

record ParsedType(SourceRoot sourceRoot, Path sourcePath, String packageName, String binaryName, String simpleName, ElementKind kind, Optional<ClassDesc> superclass, List<ClassDesc> interfaces, List<ParsedField> fields, List<ParsedMethod> methods, SourceRange range) {
    ParsedType {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        sourcePath = Objects.requireNonNull(sourcePath, "sourcePath").normalize();
        if (sourcePath.isAbsolute()) {
            throw new IllegalArgumentException("Parsed source path must be relative: " + sourcePath);
        }
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(binaryName, "binaryName");
        Objects.requireNonNull(simpleName, "simpleName");
        Objects.requireNonNull(kind, "kind");
        superclass = Optional.ofNullable(superclass).orElseThrow(() -> new NullPointerException("superclass"));
        interfaces = List.copyOf(interfaces);
        fields = List.copyOf(fields);
        methods = List.copyOf(methods);
        Objects.requireNonNull(range, "range");
    }
}
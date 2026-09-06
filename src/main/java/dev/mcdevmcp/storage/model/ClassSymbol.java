package dev.mcdevmcp.storage.model;

import javax.lang.model.element.ElementKind;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ClassSymbol(long id, SourceNamespace namespace, Optional<FabricApiVersion> fabricApiVersion, String binaryName, String packageName, String simpleName, ElementKind kind, Optional<String> superclassBinaryName, List<String> interfaceBinaryNames, Path sourcePath, int startOffset, int endOffset, int startLine, int endLine) {
    public ClassSymbol {
        Objects.requireNonNull(namespace, "namespace");
        fabricApiVersion = Optional.ofNullable(fabricApiVersion).orElseThrow(() -> new NullPointerException("fabricApiVersion"));
        if (namespace == SourceNamespace.MINECRAFT && fabricApiVersion.isPresent()) {
            throw new IllegalArgumentException("Minecraft symbols must not have a Fabric API version");
        }
        if (namespace == SourceNamespace.FABRIC && fabricApiVersion.isEmpty()) {
            throw new IllegalArgumentException("Fabric symbols must have a Fabric API version");
        }
        Objects.requireNonNull(binaryName, "binaryName");
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(simpleName, "simpleName");
        ElementKindCodec.wireName(kind);
        superclassBinaryName = Optional.ofNullable(superclassBinaryName).orElseThrow(() -> new NullPointerException("superclassBinaryName"));
        interfaceBinaryNames = List.copyOf(interfaceBinaryNames);
        Objects.requireNonNull(sourcePath, "sourcePath");
    }
}
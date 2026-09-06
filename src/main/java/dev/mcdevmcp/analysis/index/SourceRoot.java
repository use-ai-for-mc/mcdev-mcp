package dev.mcdevmcp.analysis.index;

import dev.mcdevmcp.storage.model.FabricApiVersion;
import dev.mcdevmcp.storage.model.SourceNamespace;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record SourceRoot(SourceNamespace namespace, Optional<FabricApiVersion> fabricApiVersion, Path path) implements Comparable<SourceRoot> {
    public SourceRoot {
        Objects.requireNonNull(namespace, "namespace");
        fabricApiVersion = Optional.ofNullable(fabricApiVersion).orElseThrow(() -> new NullPointerException("fabricApiVersion"));
        path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        if (namespace == SourceNamespace.MINECRAFT && fabricApiVersion.isPresent()) {
            throw new IllegalArgumentException("Minecraft source roots must not have a Fabric API version");
        }
        if (namespace == SourceNamespace.FABRIC && fabricApiVersion.isEmpty()) {
            throw new IllegalArgumentException("Fabric source roots must have a Fabric API version");
        }
    }

    @Override
    public int compareTo(SourceRoot other) {
        int namespaceOrder = namespace.compareTo(other.namespace);
        if (namespaceOrder != 0) {
            return namespaceOrder;
        }
        int versionOrder = fabricApiVersion.map(FabricApiVersion::value).orElse("").compareTo(other.fabricApiVersion.map(FabricApiVersion::value).orElse(""));
        if (versionOrder != 0) {
            return versionOrder;
        }
        return path.toString().compareTo(other.path.toString());
    }
}
package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.storage.model.FabricApiVersion;
import dev.mcdevmcp.storage.model.SourceNamespace;

import java.util.Optional;

value record PackageIdentity(SourceNamespace namespace, Optional<FabricApiVersion> fabricApiVersion, String packageName) implements Comparable<PackageIdentity> {
    PackageIdentity(ParsedType type) {
        this(type.sourceRoot().namespace(), type.sourceRoot().fabricApiVersion(), type.packageName());
    }

    @Override
    public int compareTo(PackageIdentity other) {
        int namespaceOrder = namespace.compareTo(other.namespace);
        if (namespaceOrder != 0) {
            return namespaceOrder;
        }
        int versionOrder = fabricApiVersion.map(FabricApiVersion::value).orElse("").compareTo(other.fabricApiVersion.map(FabricApiVersion::value).orElse(""));
        return versionOrder != 0 ? versionOrder : packageName.compareTo(other.packageName);
    }
}
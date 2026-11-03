package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.storage.model.FabricApiVersion;
import dev.mcdevmcp.storage.model.SourceNamespace;

import java.util.Optional;

value record IndexedPackageSnapshot(long id, SourceNamespace namespace, Optional<FabricApiVersion> fabricApiVersion, String fabricApiVersionKey, String name) {
}
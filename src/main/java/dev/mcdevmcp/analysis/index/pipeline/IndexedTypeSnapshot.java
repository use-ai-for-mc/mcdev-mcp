package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.storage.model.SourceNamespace;

import javax.lang.model.element.ElementKind;
import java.lang.constant.ClassDesc;
import java.util.Optional;

record IndexedTypeSnapshot(long id, long packageId, SourceNamespace namespace, Optional<dev.mcdevmcp.storage.model.FabricApiVersion> fabricApiVersion, String fabricApiVersionKey, String binaryName, String simpleName, ElementKind kind, Optional<ClassDesc> superclass, PortablePath sourcePath, SourceRange range) {
}
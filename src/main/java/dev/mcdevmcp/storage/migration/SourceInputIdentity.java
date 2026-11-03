package dev.mcdevmcp.storage.migration;

import dev.mcdevmcp.support.Cancellation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public value record SourceInputIdentity(SourceArtifactIdentity remappedJar, List<SourceArtifactIdentity> classpath) {
    public SourceInputIdentity {
        Objects.requireNonNull(remappedJar, "remappedJar");
        classpath = List.copyOf(classpath);
    }

    public static SourceInputIdentity capture(Path jar, List<Path> classpath, Cancellation cancellation) throws IOException {
        var identities = new ArrayList<SourceArtifactIdentity>();
        for (Path dependency : classpath) {
            identities.add(SourceArtifactIdentity.capture(dependency, cancellation));
        }
        return new SourceInputIdentity(SourceArtifactIdentity.capture(jar, cancellation), identities);
    }
}

package dev.mcdevmcp.analysis.callgraph;

import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.support.Cancellation;
import dev.mcdevmcp.support.ProgressSink;

import java.nio.file.Path;
import java.util.Objects;

public record CallgraphRequest(MinecraftVersion minecraftVersion, Path remappedJar, Path outputBundle, int threads, ProgressSink progress, Cancellation cancellation) {
    public CallgraphRequest {
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        remappedJar = normalize(remappedJar, "remappedJar");
        outputBundle = normalize(outputBundle, "outputBundle");
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be positive: " + threads);
        }
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(cancellation, "cancellation");
    }

    private static Path normalize(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }
}
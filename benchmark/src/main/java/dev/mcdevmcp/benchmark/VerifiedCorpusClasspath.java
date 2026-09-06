package dev.mcdevmcp.benchmark;

import dev.mcdevmcp.storage.model.MinecraftVersion;

import java.nio.file.Path;
import java.util.List;

public record VerifiedCorpusClasspath(Path manifestPath, MinecraftVersion minecraftVersion, List<Path> paths, CorpusClasspathEvidence evidence) {
    public VerifiedCorpusClasspath {
        paths = List.copyOf(paths);
    }

    public void verifyUnchanged(List<Path> outputRoots) throws Exception {
        VerifiedCorpusClasspath after = CorpusClasspathManifest.verify(manifestPath, minecraftVersion, outputRoots);
        if (!evidence.equals(after.evidence()) || !paths.equals(after.paths())) {
            throw new IllegalArgumentException("Immutable corpus classpath changed");
        }
    }

    public void verifyAfterFailure(List<Path> outputRoots, Throwable failure) {
        try {
            verifyUnchanged(outputRoots);
        } catch (Exception | Error integrityFailure) {
            failure.addSuppressed(integrityFailure);
        }
    }
}
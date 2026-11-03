package dev.mcdevmcp.storage.migration;

import dev.mcdevmcp.support.Cancellation;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public value record SourceProducerIdentity(String implementation, String version, SourceArtifactIdentity artifact, Map<String, String> settings, String resourcePolicy) {
    public static final String JAVA_ONLY_RESOURCE_POLICY = "mcdev-java-only-v1: IResultSaver copyFile/copyEntry and archive metadata callbacks intentionally emit no resources";

    public SourceProducerIdentity {
        Objects.requireNonNull(implementation, "implementation");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(artifact, "artifact");
        settings = Collections.unmodifiableMap(new TreeMap<>(settings));
        Objects.requireNonNull(resourcePolicy, "resourcePolicy");
    }

    public static SourceProducerIdentity capture(Class<?> producerClass, String version, Map<String, String> settings, Cancellation cancellation) throws IOException {
        var source = producerClass.getProtectionDomain().getCodeSource();
        if (source == null || !"file".equals(source.getLocation().getProtocol())) {
            throw new IOException("Cannot identify loaded source producer artifact: " + producerClass.getName());
        }
        try {
            return new SourceProducerIdentity(producerClass.getName(), version, captureRuntimeArtifact(Path.of(source.getLocation().toURI()), cancellation), settings, JAVA_ONLY_RESOURCE_POLICY);
        } catch (URISyntaxException exception) {
            throw new IOException("Invalid loaded source producer location", exception);
        }
    }

    private static SourceArtifactIdentity captureRuntimeArtifact(Path artifact, Cancellation cancellation) throws IOException {
        Path logical = artifact.toAbsolutePath().normalize();
        BasicFileAttributes selected = regularArtifact(logical);
        // The JVM supplies this producer location. Pin its physical artifact while allowing system ancestor aliases.
        Path physical = logical.toRealPath();
        BasicFileAttributes pinned = regularArtifact(physical);
        if (differentIdentity(selected, pinned)) {
            throw new IOException("Source producer artifact changed while resolving: " + logical);
        }
        SourceArtifactIdentity identity = SourceArtifactIdentity.capture(physical, cancellation);
        if (!physical.toRealPath().equals(physical) || differentIdentity(pinned, regularArtifact(physical))) {
            throw new IOException("Pinned source producer artifact changed: " + physical);
        }
        return identity;
    }

    private static BasicFileAttributes regularArtifact(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("Source producer artifact must be a regular file, not a link: " + path);
        }
        return attributes;
    }

    private static boolean differentIdentity(BasicFileAttributes first, BasicFileAttributes second) {
        return !Objects.equals(first.fileKey(), second.fileKey()) || !first.creationTime().equals(second.creationTime()) || !first.lastModifiedTime().equals(second.lastModifiedTime()) || first.size() != second.size();
    }
}

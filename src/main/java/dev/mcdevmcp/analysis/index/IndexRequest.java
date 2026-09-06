package dev.mcdevmcp.analysis.index;

import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.support.Cancellation;
import dev.mcdevmcp.support.ProgressSink;

import java.nio.file.Path;
import java.util.*;

public record IndexRequest(MinecraftVersion minecraftVersion, List<SourceRoot> sourceRoots, Path remappedJar, List<Path> classpath, Path outputDatabase, int threads, ProgressSink progress, Cancellation cancellation) {
    public static final String THREADS_ENVIRONMENT_VARIABLE = "MCDEV_INDEX_THREADS";

    public IndexRequest {
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        sourceRoots = normalizeSourceRoots(sourceRoots);
        remappedJar = normalize(remappedJar, "remappedJar");
        classpath = normalizeClasspath(classpath);
        if (classpath.contains(remappedJar)) {
            throw new IllegalArgumentException("Classpath duplicates remappedJar: " + remappedJar);
        }
        outputDatabase = normalize(outputDatabase, "outputDatabase");
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be positive: " + threads);
        }
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(cancellation, "cancellation");
    }

    public static int threadsFromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        int available = Runtime.getRuntime().availableProcessors();
        String configured = environment.get(THREADS_ENVIRONMENT_VARIABLE);
        if (configured == null) {
            return available;
        }
        try {
            int parsed = Integer.parseInt(configured);
            if (parsed < 1) {
                throw invalidThreads(configured, null);
            }
            return Math.min(parsed, available);
        } catch (NumberFormatException exception) {
            throw invalidThreads(configured, exception);
        }
    }

    private static IllegalArgumentException invalidThreads(String configured, Exception cause) {
        return new IllegalArgumentException(THREADS_ENVIRONMENT_VARIABLE + " must be a positive integer, got '" + configured + "'", cause);
    }

    private static List<SourceRoot> normalizeSourceRoots(List<SourceRoot> roots) {
        Objects.requireNonNull(roots, "sourceRoots");
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("sourceRoots must not be empty");
        }
        List<SourceRoot> copy = new ArrayList<>(roots.size());
        Set<Path> paths = new HashSet<>();
        Set<SourceIdentity> identities = new HashSet<>();
        for (SourceRoot root : roots) {
            SourceRoot checked = Objects.requireNonNull(root, "sourceRoots contains null");
            if (!paths.add(checked.path())) {
                throw new IllegalArgumentException("Duplicate source root path: " + checked.path());
            }
            SourceIdentity identity = new SourceIdentity(checked.namespace(), checked.fabricApiVersion());
            if (!identities.add(identity)) {
                throw new IllegalArgumentException("Duplicate source identity: " + checked.namespace() + checked.fabricApiVersion().map(version -> "/" + version.value()).orElse(""));
            }
            copy.add(checked);
        }
        copy.sort(Comparator.naturalOrder());
        return List.copyOf(copy);
    }

    private static List<Path> normalizeClasspath(List<Path> entries) {
        Objects.requireNonNull(entries, "classpath");
        List<Path> copy = new ArrayList<>(entries.size());
        Set<Path> unique = new HashSet<>();
        for (Path entry : entries) {
            Path normalized = normalize(entry, "classpath entry");
            if (!unique.add(normalized)) {
                throw new IllegalArgumentException("Duplicate classpath entry: " + normalized);
            }
            copy.add(normalized);
        }
        return List.copyOf(copy);
    }

    private static Path normalize(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }
}
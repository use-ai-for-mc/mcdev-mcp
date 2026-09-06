package dev.mcdevmcp.app;

import dev.mcdevmcp.analysis.callgraph.CallgraphRequest;
import dev.mcdevmcp.analysis.callgraph.CallgraphScanner;
import dev.mcdevmcp.analysis.callgraph.CallgraphSummary;
import dev.mcdevmcp.analysis.decompile.*;
import dev.mcdevmcp.analysis.index.IndexRequest;
import dev.mcdevmcp.analysis.index.IndexSummary;
import dev.mcdevmcp.analysis.index.SourceIndexer;
import dev.mcdevmcp.analysis.index.SourceRoot;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.SourceNamespace;
import dev.mcdevmcp.support.Cancellation;
import dev.mcdevmcp.support.ProgressSink;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/**
 * Coordinates verified cache layers with the existing index and callgraph capsules.
 */
public final class AnalysisPipeline implements AnalysisOperations {
    private final PlatformPaths paths;
    private final VersionManifestClient manifests;
    private final DownloadService downloads;
    private final MappingConverter mappings;
    private final MinecraftRemapper remapper;
    private final MinecraftDecompiler decompiler;
    private final SourceIndexer indexer;
    private final CallgraphScanner callgraph;
    private final int threads;

    public AnalysisPipeline(PlatformPaths paths, VersionManifestClient manifests, DownloadService downloads, MappingConverter mappings, MinecraftRemapper remapper, MinecraftDecompiler decompiler, SourceIndexer indexer, CallgraphScanner callgraph, int threads) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.manifests = Objects.requireNonNull(manifests, "manifests");
        this.downloads = Objects.requireNonNull(downloads, "downloads");
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.remapper = Objects.requireNonNull(remapper, "remapper");
        this.decompiler = Objects.requireNonNull(decompiler, "decompiler");
        this.indexer = Objects.requireNonNull(indexer, "indexer");
        this.callgraph = Objects.requireNonNull(callgraph, "callgraph");
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be positive");
        }
        this.threads = threads;
    }

    public static AnalysisPipeline production() {
        PlatformPaths paths = PlatformPaths.forEnvironment(System.getProperty("os.name"), System.getenv(), Path.of(System.getProperty("user.home")));
        return production(paths);
    }

    public static AnalysisPipeline production(PlatformPaths paths) {
        return production(paths, System.getenv());
    }

    static AnalysisPipeline production(PlatformPaths paths, Map<String, String> environment) {
        Objects.requireNonNull(paths, "paths");
        int threads = IndexRequest.threadsFromEnvironment(environment);
        return new AnalysisPipeline(paths, VersionManifestClient.production(), DownloadService.production(), new MappingConverter(), new MinecraftRemapper(threads), new MinecraftDecompiler(), new SourceIndexer(), new CallgraphScanner(), threads);
    }

    int parallelism() {
        return threads;
    }

    private static boolean javaSourceCacheMissing(Path root, Cancellation cancellation) throws IOException, InterruptedException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return true;
        }
        try (var files = Files.walk(root)) {
            Iterator<Path> iterator = files.iterator();
            while (iterator.hasNext()) {
                checkCancelled(cancellation);
                Path path = iterator.next();
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.getFileName().toString().endsWith(".java")) {
                    return false;
                }
            }
            return true;
        }
    }

    private static void publishCopy(Path source, Path target, Cancellation cancellation) throws IOException {
        Files.createDirectories(target.getParent());
        Path staging = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            checkCancelled(cancellation);
            try (InputStream input = Files.newInputStream(source);
                 FileChannel output = FileChannel.open(staging, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkCancelled(cancellation);
                    ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, read);
                    while (bytes.hasRemaining()) {
                        if (output.write(bytes) == 0) {
                            Thread.onSpinWait();
                        }
                    }
                }
                output.force(true);
            }
            if (invalidJar(staging, cancellation)) {
                throw new IOException("Official unobfuscated client is not a valid class JAR: " + source);
            }
            checkCancelled(cancellation);
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Cancelled while publishing remapped JAR", exception);
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    private static boolean invalidJar(Path candidate, Cancellation cancellation) throws IOException {
        return !JarArtifactValidator.isValidClassJar(candidate, cancellation);
    }

    private static void checkCancelled(Cancellation cancellation) throws InterruptedException {
        cancellation.throwIfCancelled();
    }

    private static void preserveInterruption(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
            current = current.getCause();
        }
    }

    private static String failureMessage(String summary, Throwable failure) {
        String detail = failure.getMessage();
        return detail == null || detail.isBlank() ? summary : summary + ": " + detail;
    }

    @Override
    public PreparedSources prepareSources(MinecraftVersion version, ProgressSink progress, Cancellation cancellation) {
        try {
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(progress, "progress");
            Objects.requireNonNull(cancellation, "cancellation");
            checkCancelled(cancellation);
            progress.report("metadata", 0, "Resolving Minecraft " + version.value() + " metadata");
            MinecraftDownloads metadata = manifests.resolve(version);
            checkCancelled(cancellation);
            progress.report("metadata", 100, "Resolved Minecraft " + version.value() + " metadata");
            Path jars = paths.versionCache(version).resolve("jars");
            Path client = downloads.download(metadata.client(), jars.resolve("client.jar"), progress, cancellation);
            Path remapped = paths.remappedJar(version);
            Path unobfuscated;
            OfficialUnobfuscatedClient officialUnobfuscatedClient = metadata.officialUnobfuscatedClient();
            if (officialUnobfuscatedClient != null) {
                Path officialClient = downloads.download(officialUnobfuscatedClient.artifact(), jars.resolve("client-unobfuscated.jar"), progress, cancellation);
                if (invalidJar(remapped, cancellation)) {
                    progress.report("remap", 0, "Publishing official unobfuscated client JAR");
                    publishCopy(officialClient, remapped, cancellation);
                    progress.report("remap", 100, "Published official unobfuscated client JAR");
                }
                unobfuscated = officialClient;
            }
            else if (metadata.clientMappings() == null) {
                if (invalidJar(remapped, cancellation)) {
                    progress.report("remap", 0, "Publishing unobfuscated client JAR");
                    publishCopy(client, remapped, cancellation);
                    progress.report("remap", 100, "Published unobfuscated client JAR");
                }
                unobfuscated = client;
            }
            else if (invalidJar(remapped, cancellation)) {
                Path mapping = downloads.download(metadata.clientMappings(), jars.resolve("client.txt"), progress, cancellation);
                checkCancelled(cancellation);
                Path tiny = mappings.convert(mapping, jars.resolve("client.tiny"), progress, cancellation);
                checkCancelled(cancellation);
                remapper.remap(client, tiny, remapped, progress, cancellation);
                checkCancelled(cancellation);
                unobfuscated = remapped;
            }
            else {
                unobfuscated = remapped;
            }
            Path librariesDir = paths.versionCache(version).resolve("libraries");
            for (DownloadArtifact library : metadata.libraries()) {
                checkCancelled(cancellation);
                String fileName = Path.of(library.uri().getPath()).getFileName().toString();
                Path targetLib = librariesDir.resolve(fileName);
                downloads.download(library, targetLib, progress, cancellation);
            }
            Path source = paths.sourceRoot(version);
            if (javaSourceCacheMissing(source, cancellation)) {
                decompiler.decompile(remapped, source, progress, cancellation);
            }
            SourceRoot root = new SourceRoot(SourceNamespace.MINECRAFT, Optional.empty(), source);
            return new PreparedSources(version, List.of(root), client, unobfuscated, remapped);
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException(failureMessage("Unable to prepare Minecraft " + version.value() + " sources", exception), exception);
        }
    }

    @Override
    public IndexSummary rebuildIndex(MinecraftVersion version, ProgressSink progress, Cancellation cancellation) {
        try {
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(progress, "progress");
            Objects.requireNonNull(cancellation, "cancellation");
            checkCancelled(cancellation);
            List<SourceRoot> sourceRoots = cachedSourceRoots(version, cancellation);
            Path remapped = cachedRemappedJar(version, cancellation);
            List<Path> classpath = cachedClasspath(version, cancellation);
            return indexer.build(new IndexRequest(version, sourceRoots, remapped, classpath, paths.symbolDatabase(version), threads, progress, cancellation));
        } catch (Exception exception) {
            preserveInterruption(exception);
            throw new IllegalStateException(failureMessage("Unable to rebuild index for " + version.value(), exception), exception);
        }
    }

    private List<Path> cachedClasspath(MinecraftVersion version, Cancellation cancellation) throws IOException, InterruptedException {
        checkCancelled(cancellation);
        Path librariesDir = paths.versionCache(version).resolve("libraries");
        if (!Files.isDirectory(librariesDir)) {
            return List.of();
        }
        try (var stream = Files.list(librariesDir)) {
            List<Path> classpath = new ArrayList<>();
            for (Path jar : stream.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
                checkCancelled(cancellation);
                if (!invalidJar(jar, cancellation)) {
                    classpath.add(jar.toAbsolutePath().normalize());
                }
            }
            classpath.sort(Comparator.comparing(Path::toString));
            return List.copyOf(classpath);
        }
    }

    @Override
    public CallgraphSummary rebuildCallgraph(MinecraftVersion version, ProgressSink progress, Cancellation cancellation) {
        try {
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(progress, "progress");
            Objects.requireNonNull(cancellation, "cancellation");
            checkCancelled(cancellation);
            cachedSourceRoots(version, cancellation);
            Path remapped = cachedRemappedJar(version, cancellation);
            return callgraph.scan(new CallgraphRequest(version, remapped, paths.callgraphBundle(version), threads, progress, cancellation));
        } catch (IOException | InterruptedException exception) {
            preserveInterruption(exception);
            throw new IllegalStateException(failureMessage("Unable to rebuild callgraph for " + version.value(), exception), exception);
        }
    }

    private List<SourceRoot> cachedSourceRoots(MinecraftVersion version, Cancellation cancellation) throws IOException, InterruptedException {
        Path source = paths.sourceRoot(version);
        if (javaSourceCacheMissing(source, cancellation)) {
            throw new IllegalStateException("No prepared Java source cache for " + version.value() + "; run init first");
        }
        return List.of(new SourceRoot(SourceNamespace.MINECRAFT, Optional.empty(), source));
    }

    private Path cachedRemappedJar(MinecraftVersion version, Cancellation cancellation) throws IOException, InterruptedException {
        checkCancelled(cancellation);
        Path remapped = paths.remappedJar(version);
        if (invalidJar(remapped, cancellation)) {
            throw new IllegalStateException("No prepared remapped JAR cache for " + version.value() + "; run init first");
        }
        return remapped.toAbsolutePath().normalize();
    }
}
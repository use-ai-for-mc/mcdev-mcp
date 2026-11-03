package dev.mcdevmcp.app;

import dev.mcdevmcp.analysis.callgraph.CallgraphRequest;
import dev.mcdevmcp.analysis.callgraph.CallgraphScanner;
import dev.mcdevmcp.analysis.callgraph.CallgraphSummary;
import dev.mcdevmcp.analysis.decompile.*;
import dev.mcdevmcp.analysis.index.*;
import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.bundle.BundleFiles;
import dev.mcdevmcp.storage.migration.*;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.storage.model.SourceNamespace;
import dev.mcdevmcp.storage.symbols.IndexCleaner;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.AppVersion;
import dev.mcdevmcp.support.Cancellation;
import dev.mcdevmcp.support.ProgressSink;
import org.jetbrains.java.decompiler.main.Fernflower;

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
    private final SourceIndexTransactionPublisher sourceTransactions = new SourceIndexTransactionPublisher();
    private final SourceCacheValidator sourceValidator = new SourceCacheValidator();
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
        return production(paths, AppEnvironment.system().values());
    }

    static AnalysisPipeline production(PlatformPaths paths, Map<String, String> environment) {
        Objects.requireNonNull(paths, "paths");
        int threads = IndexRequest.threadsFromEnvironment(environment);
        return new AnalysisPipeline(paths, VersionManifestClient.production(), DownloadService.production(), new MappingConverter(), new MinecraftRemapper(threads), new MinecraftDecompiler(), new SourceIndexer(), new CallgraphScanner(), threads);
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

    private static IOException sourceRefreshRequired(MinecraftVersion version, SourceValidation validation) {
        return new IOException("Cached sources require explicit refresh: " + validationMessage(validation) + ". Originals are unchanged. Run " + AppVersion.commandLine() + " init -v " + version.value() + " --refresh-sources to regenerate with retained originals.");
    }

    private static String validationMessage(SourceValidation validation) {
        String details = String.join("; ", validation.diagnostics().stream().limit(8).toList());
        return validation.status() + (details.isEmpty() ? "" : ": " + details) + (validation.diagnostics().size() > 8 ? "; additional diagnostics: " + (validation.diagnostics().size() - 8) : "");
    }

    int parallelism() {
        return threads;
    }

    private PreparedSources prepareArtifacts(PlatformPaths paths, CachePathBoundary boundary, MinecraftVersion version, ProgressSink progress, Cancellation cancellation) {
        try {
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(progress, "progress");
            Objects.requireNonNull(cancellation, "cancellation");
            checkCancelled(cancellation);
            progress.report("metadata", 0, "Resolving Minecraft " + version.value() + " metadata");
            MinecraftDownloads metadata = manifests.resolve(version);
            checkCancelled(cancellation);
            progress.report("metadata", 100, "Resolved Minecraft " + version.value() + " metadata");
            Path jars = boundary.require(paths.versionCache(version).resolve("jars"));
            Path client = downloads.download(metadata.client(), boundary.require(jars.resolve("client.jar")), progress, cancellation);
            Path remapped = boundary.require(paths.remappedJar(version));
            Path unobfuscated;
            OfficialUnobfuscatedClient officialUnobfuscatedClient = metadata.officialUnobfuscatedClient();
            if (officialUnobfuscatedClient != null) {
                Path officialClient = downloads.download(officialUnobfuscatedClient.artifact(), boundary.require(jars.resolve("client-unobfuscated.jar")), progress, cancellation);
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
                Path mapping = downloads.download(metadata.clientMappings(), boundary.require(jars.resolve("client.txt")), progress, cancellation);
                checkCancelled(cancellation);
                Path tiny = mappings.convert(mapping, boundary.require(jars.resolve("client.tiny")), progress, cancellation);
                checkCancelled(cancellation);
                remapper.remap(client, tiny, remapped, progress, cancellation);
                checkCancelled(cancellation);
                unobfuscated = remapped;
            }
            else {
                unobfuscated = remapped;
            }
            Path librariesDir = boundary.require(paths.versionCache(version).resolve("libraries"));
            for (DownloadArtifact library : metadata.libraries()) {
                checkCancelled(cancellation);
                String fileName = Path.of(library.uri().getPath()).getFileName().toString();
                Path targetLib = boundary.require(librariesDir.resolve(fileName));
                downloads.download(library, targetLib, progress, cancellation);
            }
            Path source = boundary.require(paths.sourceRoot(version));
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
    public InitializationResult initialize(MinecraftVersion version, SourceRefreshPolicy refreshPolicy, ProgressSink progress, Cancellation cancellation) {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(refreshPolicy, "refreshPolicy");
        Objects.requireNonNull(progress, "progress");
        Objects.requireNonNull(cancellation, "cancellation");
        try (var lease = VersionOperationLease.write(paths, version)) {
            PlatformPaths paths = lease.resolvedPaths();
            CachePathBoundary boundary = lease.boundary();
            sourceTransactions.recover(paths, version, lease);
            checkCancelled(cancellation);
            SourceIndexSnapshot before = sourceTransactions.captureBefore(paths, version, lease, cancellation);
            PreparedSources prepared = prepareArtifacts(paths, boundary, version, progress, cancellation);
            List<Path> classpath = cachedClasspath(paths, boundary, version, cancellation);
            SourceInputIdentity inputs = SourceInputIdentity.capture(prepared.remappedJar(), classpath, cancellation);
            SourceValidation validation = sourceValidator.validate(paths.sourceRoot(version), prepared.remappedJar(), cancellation);
            Optional<SourcePreparationStamp> previous;
            try {
                previous = SourceProvenance.read(paths.versionCache(version).resolve("source-preparation.json"));
            } catch (InvalidSourceStampException invalidStamp) {
                previous = Optional.empty();
                progress.report("migration", 0, "Source stamp is invalid; existing sources retain external ownership");
            }
            SourceSelection selection = SourceProvenance.select(before.source(), validation, previous.orElse(null), inputs, refreshPolicy == SourceRefreshPolicy.EXPLICIT_REFRESH);
            if (selection == SourceSelection.REQUIRE_REFRESH) {
                throw sourceRefreshRequired(version, validation);
            }
            Path transaction = boundary.require(paths.cacheRoot().resolve("migrations").resolve(version.value()).resolve(UUID.randomUUID().toString()));
            boolean handedOver = false;
            try {
                Path candidate = Files.createDirectories(boundary.require(transaction.resolve("candidate")));
                Path candidateSource = paths.sourceRoot(version);
                SourceTreeInventory candidateInventory = before.source();
                SourcePreparationStamp stamp;
                SourcePublicationMode mode;
                if (selection == SourceSelection.GENERATE) {
                    if (before.source().present()) {
                        progress.report("migration", 0, "Regenerating sources with retained originals; do not edit this cache during migration");
                    }
                    candidateSource = boundary.require(candidate.resolve("client"));
                    decompiler.decompile(prepared.remappedJar(), candidateSource, progress, cancellation);
                    validation = sourceValidator.validate(candidateSource, prepared.remappedJar(), cancellation);
                    if (!validation.valid()) {
                        throw new IOException("Generated sources failed complete source validation: " + validationMessage(validation));
                    }
                    candidateInventory = SourceTreeInventory.capture(candidateSource, boundary, cancellation);
                    SourceProducerIdentity producer = SourceProducerIdentity.capture(Fernflower.class, "1.12.0", MinecraftDecompiler.settings(), cancellation);
                    stamp = SourceProvenance.generated(inputs, candidateInventory, validation, producer);
                    mode = SourcePublicationMode.REPLACE_SOURCES;
                }
                else {
                    stamp = SourceProvenance.observed(inputs, candidateInventory, validation, previous.orElse(null));
                    mode = SourcePublicationMode.REUSE_SOURCES;
                }
                Path candidateDatabase = boundary.require(candidate.resolve("index/symbols.jsonl"));
                IndexRequest request = new IndexRequest(version, List.of(new SourceRoot(SourceNamespace.MINECRAFT, Optional.empty(), candidateSource)), prepared.remappedJar(), classpath, candidateDatabase, threads, progress, cancellation);
                IndexSummary summary = indexer.build(request, new PublishedSourceRoot(paths.sourceRoot(version)));
                Path candidateStamp = boundary.require(candidate.resolve("source-preparation.json"));
                SourceProvenance.write(candidateStamp, stamp);
                if (!inputs.equals(SourceInputIdentity.capture(prepared.remappedJar(), classpath, cancellation))) {
                    throw new IOException("Source/index inputs changed while staging; original source and index are preserved");
                }
                handedOver = true;
                SourcePublicationResult published = sourceTransactions.publish(paths, version, lease, transaction, candidateSource, candidateDatabase, candidateStamp, before, mode, cancellation);
                boolean hadOriginal = before.source().present() || before.index().entries().stream().anyMatch(entry -> entry.kind() == SourceEntryKind.FILE) || before.stamp().present();
                return new InitializationResult(prepared, summary, published.sourceInventory().sha256(), hadOriginal ? Optional.of(published.retainedMigration()) : Optional.empty());
            } finally {
                if (!handedOver) {
                    discardStaging(transaction);
                }
            }
        } catch (Exception failure) {
            preserveInterruption(failure);
            throw new IllegalStateException(failureMessage("Unable to initialize Minecraft " + version.value(), failure), failure);
        }
    }

    /**
     * Removes what a failed initialization staged before the publisher took ownership of it.
     */
    private static void discardStaging(Path transaction) {
        try {
            BundleFiles.deleteTree(transaction);
        } catch (IOException ignored) {
            // What cannot be removed now stays for 'clean'; the failure being reported is the one that matters.
        }
    }

    @Override
    public IndexSummary rebuildIndex(MinecraftVersion version, ProgressSink progress, Cancellation cancellation) {
        try (var lease = VersionOperationLease.write(paths, version)) {
            PlatformPaths paths = lease.resolvedPaths();
            CachePathBoundary boundary = lease.boundary();
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(progress, "progress");
            Objects.requireNonNull(cancellation, "cancellation");
            sourceTransactions.recover(paths, version, lease);
            checkCancelled(cancellation);
            Path source = boundary.require(paths.sourceRoot(version));
            SourceTreeInventory sourceInventory = SourceTreeInventory.capture(source, boundary, cancellation);
            if (!sourceInventory.present()) {
                throw new IllegalStateException("Minecraft %s not decompiled. Run 'init -v %s' first.".formatted(version.value(), version.value()));
            }
            Path remapped = cachedRemappedJar(paths, boundary, version, cancellation);
            SourceValidation validation = sourceValidator.validate(source, remapped, cancellation);
            if (!validation.valid()) {
                throw sourceRefreshRequired(version, validation);
            }
            List<Path> classpath = cachedClasspath(paths, boundary, version, cancellation);
            List<SourceRoot> sourceRoots = List.of(new SourceRoot(SourceNamespace.MINECRAFT, Optional.empty(), source));
            Path database = boundary.require(paths.symbolIndex(version));
            SourceTreeInventory.capture(database.getParent(), boundary, cancellation);
            IndexSummary summary = indexer.build(new IndexRequest(version, sourceRoots, remapped, classpath, database, threads, progress, cancellation));
            new IndexCleaner(paths).deleteSupersededDatabase(version);
            return summary;
        } catch (Exception exception) {
            preserveInterruption(exception);
            throw new IllegalStateException(failureMessage("Unable to rebuild index for " + version.value(), exception), exception);
        }
    }

    private List<Path> cachedClasspath(PlatformPaths paths, CachePathBoundary boundary, MinecraftVersion version, Cancellation cancellation) throws IOException, InterruptedException {
        checkCancelled(cancellation);
        Path librariesDir = boundary.require(paths.versionCache(version).resolve("libraries"));
        if (!Files.isDirectory(librariesDir)) {
            return List.of();
        }
        try (var stream = Files.list(librariesDir)) {
            List<Path> classpath = new ArrayList<>();
            for (Path jar : stream.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
                checkCancelled(cancellation);
                boundary.require(jar);
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
        try (var lease = VersionOperationLease.write(paths, version)) {
            PlatformPaths paths = lease.resolvedPaths();
            CachePathBoundary boundary = lease.boundary();
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(progress, "progress");
            Objects.requireNonNull(cancellation, "cancellation");
            sourceTransactions.recover(paths, version, lease);
            checkCancelled(cancellation);
            requireCachedSources(paths, boundary, version, cancellation);
            if (!Files.isRegularFile(boundary.require(paths.symbolIndex(version)), LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Minecraft %s not indexed. Run 'init -v %s' first.".formatted(version.value(), version.value()));
            }
            Path remapped = cachedRemappedJar(paths, boundary, version, cancellation);
            Path bundle = boundary.require(paths.callgraphBundle(version));
            SourceTreeInventory.capture(bundle, boundary, cancellation);
            return callgraph.scan(new CallgraphRequest(version, remapped, bundle, threads, progress, cancellation));
        } catch (IOException | InterruptedException exception) {
            preserveInterruption(exception);
            throw new IllegalStateException(failureMessage("Unable to rebuild callgraph for " + version.value(), exception), exception);
        }
    }

    private void requireCachedSources(PlatformPaths paths, CachePathBoundary boundary, MinecraftVersion version, Cancellation cancellation) throws IOException, InterruptedException {
        Path source = boundary.require(paths.sourceRoot(version));
        SourceTreeInventory.capture(source, boundary, cancellation);
        if (javaSourceCacheMissing(source, cancellation)) {
            throw new IllegalStateException("Minecraft %s not decompiled. Run 'init -v %s' first.".formatted(version.value(), version.value()));
        }
    }

    private Path cachedRemappedJar(PlatformPaths paths, CachePathBoundary boundary, MinecraftVersion version, Cancellation cancellation) throws IOException, InterruptedException {
        checkCancelled(cancellation);
        Path remapped = boundary.require(paths.remappedJar(version));
        if (invalidJar(remapped, cancellation)) {
            throw new IllegalStateException("No prepared remapped JAR cache for " + version.value() + "; run init first");
        }
        return remapped.toAbsolutePath().normalize();
    }
}
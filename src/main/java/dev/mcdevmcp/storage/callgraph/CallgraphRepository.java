package dev.mcdevmcp.storage.callgraph;

import dev.mcdevmcp.storage.bundle.BundleFiles;
import dev.mcdevmcp.storage.bundle.BundleLock;
import dev.mcdevmcp.storage.bundle.ChannelRangeInputStream;
import dev.mcdevmcp.storage.bundle.JsonlLineReader;
import dev.mcdevmcp.storage.model.MethodReference;
import dev.mcdevmcp.storage.model.MinecraftVersion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.*;

/**
 * Generation-aware, bounded read access to an immutable indexed callgraph bundle.
 */
public final class CallgraphRepository {
    private static final Duration READ_LOCK_TIMEOUT = Duration.ofSeconds(30);
    private final Path bundle;
    private final MinecraftVersion expectedVersion;
    private final Duration lockTimeout;
    private volatile GenerationCache cache;

    public CallgraphRepository(Path bundle) {
        this(bundle, null, READ_LOCK_TIMEOUT);
    }

    public CallgraphRepository(Path bundle, MinecraftVersion expectedVersion) {
        this(bundle, Objects.requireNonNull(expectedVersion, "expectedVersion"), READ_LOCK_TIMEOUT);
    }

    CallgraphRepository(Path bundle, MinecraftVersion expectedVersion, Duration lockTimeout) {
        this.bundle = Objects.requireNonNull(bundle, "bundle").toAbsolutePath().normalize();
        this.expectedVersion = expectedVersion;
        this.lockTimeout = Objects.requireNonNull(lockTimeout, "lockTimeout");
    }

    public static boolean isPublished(Path bundle) {
        return publicationStatus(bundle) != PublicationStatus.ABSENT;
    }

    public static PublicationStatus publicationStatus(Path bundle) {
        Path root = Objects.requireNonNull(bundle, "bundle").toAbsolutePath().normalize();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return PublicationStatus.ABSENT;
        }
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return PublicationStatus.CORRUPT;
        }
        Path pointer = root.resolve("current.json");
        if (!Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)) {
            return PublicationStatus.ABSENT;
        }
        try {
            CallgraphBundleValidator.validateArtifacts(root);
            return PublicationStatus.PUBLISHED;
        } catch (IOException | RuntimeException exception) {
            return PublicationStatus.CORRUPT;
        }
    }

    private static long add(long left, long right, String message) throws IOException {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IOException(message, exception);
        }
    }

    private static <T> T parse(byte[] bytes, String artifactFileName, Class<T> type) throws IOException {
        try {
            return CallgraphJson.readCanonical(bytes, type, artifactFileName);
        } catch (IOException | RuntimeException exception) {
            throw new IOException("Invalid typed JSON record in " + artifactFileName, exception);
        }
    }

    public List<MethodReference> callers(String className, String methodName, int limitPlusOne) throws IOException {
        return references(className, methodName, limitPlusOne, CallgraphDirection.CALLERS);
    }

    public List<MethodReference> callees(String className, String methodName, int limitPlusOne) throws IOException {
        return references(className, methodName, limitPlusOne, CallgraphDirection.CALLEES);
    }

    private List<MethodReference> references(String className, String methodName, int limitPlusOne, CallgraphDirection direction) throws IOException {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(methodName, "methodName");
        if (limitPlusOne < 1 || limitPlusOne > 5001) {
            throw new IllegalArgumentException("limitPlusOne must be between 1 and 5001");
        }
        try (var lock = BundleLock.read(bundle, lockTimeout)) {
            if (!lock.isHeld()) {
                throw new IOException("Failed to acquire shared bundle lock");
            }
            CallgraphBundleLayout.ResolvedGeneration resolved = CallgraphBundleLayout.resolve(bundle);
            if (expectedVersion != null && !expectedVersion.value().equals(resolved.manifest().minecraftVersion())) {
                throw new IOException("Callgraph manifest version does not match requested Minecraft version");
            }
            GenerationCache generation = generation(resolved);
            CallgraphIndexRecord range = generation.index(direction).get(new CallgraphDirection.LookupKey(className, methodName));
            if (range == null) {
                return List.of();
            }
            return readRange(generation, direction, range, limitPlusOne);
        }
    }

    private GenerationCache generation(CallgraphBundleLayout.ResolvedGeneration resolved) {
        GenerationCache current = cache;
        if (current != null && current.identity().equals(resolved.identity())) {
            return current;
        }
        synchronized (this) {
            current = cache;
            if (current == null || !current.identity().equals(resolved.identity())) {
                current = new GenerationCache(resolved.identity(), resolved.path(), resolved.manifest());
                cache = current;
            }
            return current;
        }
    }

    private List<MethodReference> readRange(GenerationCache generation, CallgraphDirection direction, CallgraphIndexRecord range, int limitPlusOne) throws IOException {
        CallgraphFileMetadata metadata = CallgraphBundleLayout.metadata(generation.manifest(), direction.dataArtifact());
        Path data = direction.dataArtifact().resolve(generation.path());
        BundleFiles.requireRegularFile(bundle, data);
        if (Files.size(data) != metadata.byteLength()) {
            throw new IOException("Callgraph data byte length no longer matches its manifest");
        }
        long rangeEnd = add(range.byteOffset(), range.byteLength(), "Callgraph query range overflow");
        if (rangeEnd > metadata.byteLength()) {
            throw new IOException("Callgraph query range exceeds its data artifact");
        }
        long rows = Math.min(range.rowCount(), limitPlusOne);
        List<MethodReference> references = new ArrayList<>((int) rows);
        CallgraphDataRecord previous = null;
        try (FileChannel channel = FileChannel.open(data, StandardOpenOption.READ);
             InputStream rangeInput = new ChannelRangeInputStream(channel, range.byteOffset(), range.byteLength());
             var lines = new JsonlLineReader(rangeInput, CallgraphBundleLayout.MAXIMUM_JSONL_LINE_BYTES)) {
            for (long row = 0; row < rows; row++) {
                byte[] line = lines.next();
                if (line == null) {
                    throw new IOException("Callgraph data ended inside the accessed index range");
                }
                CallgraphDataRecord record = parse(line, direction.dataFileName(), CallgraphDataRecord.class);
                if (!direction.lookupClass(record).equals(range.className()) || !direction.lookupMethod(record).equals(range.methodName())) {
                    throw new IOException("Accessed callgraph row does not match its index key");
                }
                if (previous != null && direction.comparator().compare(previous, record) >= 0) {
                    throw new IOException("Accessed callgraph rows are not strictly monotonic");
                }
                references.add(direction.result(record));
                previous = record;
            }
            if (rows == range.rowCount() && (lines.next() != null || lines.bytesRead() != range.byteLength())) {
                throw new IOException("Accessed callgraph range contains unexpected trailing data");
            }
        }
        return List.copyOf(references);
    }

    public enum PublicationStatus {
        ABSENT, PUBLISHED, CORRUPT
    }

    private final class GenerationCache {
        private final String identity;
        private final Path path;
        private final CallgraphManifest manifest;
        private volatile NavigableMap<CallgraphDirection.LookupKey, CallgraphIndexRecord> callers;
        private volatile NavigableMap<CallgraphDirection.LookupKey, CallgraphIndexRecord> callees;

        private GenerationCache(String identity, Path path, CallgraphManifest manifest) {
            this.identity = identity;
            this.path = path;
            this.manifest = manifest;
        }

        private String identity() {
            return identity;
        }

        private Path path() {
            return path;
        }

        private CallgraphManifest manifest() {
            return manifest;
        }

        private NavigableMap<CallgraphDirection.LookupKey, CallgraphIndexRecord> index(CallgraphDirection direction) throws IOException {
            NavigableMap<CallgraphDirection.LookupKey, CallgraphIndexRecord> current = direction == CallgraphDirection.CALLERS ? callers : callees;
            if (current != null) {
                return current;
            }
            synchronized (this) {
                current = direction == CallgraphDirection.CALLERS ? callers : callees;
                if (current == null) {
                    current = loadIndex(direction);
                    if (direction == CallgraphDirection.CALLERS) {
                        callers = current;
                    }
                    else {
                        callees = current;
                    }
                }
                return current;
            }
        }

        private NavigableMap<CallgraphDirection.LookupKey, CallgraphIndexRecord> loadIndex(CallgraphDirection direction) throws IOException {
            CallgraphFileMetadata indexMetadata = CallgraphBundleLayout.metadata(manifest, direction.indexArtifact());
            CallgraphFileMetadata dataMetadata = CallgraphBundleLayout.metadata(manifest, direction.dataArtifact());
            Path index = direction.indexArtifact().resolve(path);
            BundleFiles.requireRegularFile(bundle, index);
            if (Files.size(index) != indexMetadata.byteLength()) {
                throw new IOException("Callgraph index byte length no longer matches its manifest");
            }
            var entries = new TreeMap<CallgraphDirection.LookupKey, CallgraphIndexRecord>();
            long rows = 0;
            long indexedDataRows = 0;
            long previousEnd = 0;
            CallgraphDirection.LookupKey previousKey = null;
            try (InputStream input = Files.newInputStream(index);
                 var lines = new JsonlLineReader(input, CallgraphBundleLayout.MAXIMUM_JSONL_LINE_BYTES)) {
                byte[] line;
                while ((line = lines.next()) != null) {
                    CallgraphIndexRecord record = parse(line, direction.indexFileName(), CallgraphIndexRecord.class);
                    CallgraphDirection.LookupKey key = new CallgraphDirection.LookupKey(record.className(), record.methodName());
                    if (previousKey != null && previousKey.compareTo(key) >= 0) {
                        throw new IOException("Callgraph index keys are not strictly increasing");
                    }
                    if (entries.putIfAbsent(key, record) != null) {
                        throw new IOException("Duplicate callgraph index key " + key);
                    }
                    if (record.byteOffset() != previousEnd) {
                        throw new IOException("Callgraph index byte ranges are not contiguous");
                    }
                    previousEnd = add(record.byteOffset(), record.byteLength(), "Callgraph index range overflow");
                    if (previousEnd > dataMetadata.byteLength()) {
                        throw new IOException("Callgraph index range exceeds its data artifact");
                    }
                    indexedDataRows = add(indexedDataRows, record.rowCount(), "Callgraph indexed row count overflow");
                    rows = add(rows, 1, "Callgraph index row count overflow");
                    previousKey = key;
                }
            }
            if (rows != indexMetadata.recordCount() || indexedDataRows != dataMetadata.recordCount() || previousEnd != dataMetadata.byteLength()) {
                throw new IOException("Callgraph index does not match manifest counts or data length");
            }
            return Collections.unmodifiableNavigableMap(entries);
        }
    }
}

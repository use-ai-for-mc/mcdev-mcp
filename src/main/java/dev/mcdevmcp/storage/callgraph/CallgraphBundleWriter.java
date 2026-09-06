package dev.mcdevmcp.storage.callgraph;

import dev.mcdevmcp.storage.bundle.BundleFiles;
import dev.mcdevmcp.storage.bundle.BundleHashes;
import dev.mcdevmcp.storage.bundle.BundleLock;
import dev.mcdevmcp.storage.bundle.JsonlLineReader;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.support.Cancellation;

import java.io.IOException;
import java.lang.constant.MethodTypeDesc;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

public final class CallgraphBundleWriter implements AutoCloseable {
    public static final Duration WRITE_LOCK_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_RUN_RECORDS = 2048;
    private static final int DEFAULT_MERGE_FAN_IN = 32;
    private static final long MAXIMUM_BUFFER_BYTES = 8L * 1024 * 1024;

    private final Path root;
    private final Path staging;
    private final Path work;
    private final StagingLease stagingLease;
    private final MinecraftVersion minecraftVersion;
    private final String remappedJarSha256;
    private final Cancellation cancellation;
    private final int runRecords;
    private final int mergeFanIn;
    private final PointerPublisher pointerPublisher;
    private final List<CallgraphDataRecord> buffer;
    private final EnumMap<CallgraphDirection, List<Path>> runs = new EnumMap<>(CallgraphDirection.class);
    private int nextRun;
    private long bufferBytes;
    private boolean published;
    private boolean closed;

    public CallgraphBundleWriter(Path bundle, MinecraftVersion minecraftVersion, String remappedJarSha256, Cancellation cancellation) throws IOException {
        this(bundle, minecraftVersion, remappedJarSha256, cancellation, DEFAULT_RUN_RECORDS, DEFAULT_MERGE_FAN_IN);
    }

    CallgraphBundleWriter(Path bundle, MinecraftVersion minecraftVersion, String remappedJarSha256, Cancellation cancellation, int runRecords, int mergeFanIn) throws IOException {
        this(bundle, minecraftVersion, remappedJarSha256, cancellation, runRecords, mergeFanIn, BundleFiles::atomicReplace);
    }

    CallgraphBundleWriter(Path bundle, MinecraftVersion minecraftVersion, String remappedJarSha256, Cancellation cancellation, int runRecords, int mergeFanIn, PointerPublisher pointerPublisher) throws IOException {
        root = Objects.requireNonNull(bundle, "bundle").toAbsolutePath().normalize();
        this.minecraftVersion = Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        this.remappedJarSha256 = Objects.requireNonNull(remappedJarSha256, "remappedJarSha256");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        if (CallgraphBundleLayout.isInvalidSha256(remappedJarSha256)) {
            throw new IllegalArgumentException("remappedJarSha256 must be a lowercase SHA-256");
        }
        if (runRecords < 1 || mergeFanIn < 2) {
            throw new IllegalArgumentException("External sort bounds must be positive and fan-in must be at least two");
        }
        this.runRecords = runRecords;
        this.mergeFanIn = mergeFanIn;
        this.pointerPublisher = Objects.requireNonNull(pointerPublisher, "pointerPublisher");
        buffer = new ArrayList<>(runRecords);
        Files.createDirectories(root);
        BundleFiles.requireDirectory(root, root);
        StagingLease acquiredLease = StagingLease.create(root);
        stagingLease = acquiredLease;
        staging = acquiredLease.staging();
        work = BundleFiles.safeChild(staging, "work");
        try {
            Files.createDirectory(staging);
            Files.createDirectory(work);
            for (CallgraphDirection direction : CallgraphDirection.values()) {
                runs.put(direction, new ArrayList<>());
            }
        } catch (IOException | RuntimeException exception) {
            try {
                BundleFiles.deleteTree(staging);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            try {
                acquiredLease.close();
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    private static boolean isStagingMarkerName(String name) {
        return name.endsWith(".lock") && isStagingDirectoryName(name.substring(0, name.length() - ".lock".length()));
    }

    private static boolean isStagingDirectoryName(String name) {
        String prefix = ".staging-";
        if (!name.startsWith(prefix)) {
            return false;
        }
        String identifier = name.substring(prefix.length());
        try {
            return UUID.fromString(identifier).toString().equals(identifier);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static void requireCanonicalDescriptor(String descriptor, String name) {
        Objects.requireNonNull(descriptor, name);
        try {
            MethodTypeDesc.ofDescriptor(descriptor);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid " + name + ": " + descriptor, exception);
        }
    }

    private static long add(long left, long right) throws IOException {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IOException("Callgraph external-sort byte count overflow", exception);
        }
    }

    private static void writeIndex(FileChannel channel, CallgraphDirection.LookupKey key, long offset, long length, long rows) throws IOException {
        write(channel, CallgraphJson.line(new CallgraphIndexRecord(key.className(), key.methodName(), offset, length, rows)));
    }

    private static void write(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer);
            if (written < 0) {
                throw new IOException("Unexpected end while writing callgraph JSONL");
            }
        }
    }

    public void accept(CallgraphDataRecord record) throws IOException, InterruptedException {
        accept(record, true);
    }

    void acceptLegacy(CallgraphDataRecord record) throws IOException, InterruptedException {
        accept(record, false);
    }

    private void accept(CallgraphDataRecord record, boolean generated) throws IOException, InterruptedException {
        requireOpen();
        cancellation.throwIfCancelled();
        CallgraphDataRecord value = Objects.requireNonNull(record, "record");
        if (generated) {
            requireCanonicalDescriptor(value.callerDescriptor(), "callerDescriptor");
            requireCanonicalDescriptor(value.calleeDescriptor(), "calleeDescriptor");
        }
        int encodedBytes = CallgraphJson.line(value).length;
        if (encodedBytes > CallgraphBundleLayout.MAXIMUM_JSONL_LINE_BYTES) {
            throw new IOException("Callgraph data record exceeds the maximum JSONL line size");
        }
        if (!buffer.isEmpty() && add(bufferBytes, encodedBytes) > MAXIMUM_BUFFER_BYTES) {
            spill();
        }
        buffer.add(value);
        bufferBytes = add(bufferBytes, encodedBytes);
        if (buffer.size() == runRecords || bufferBytes >= MAXIMUM_BUFFER_BYTES) {
            spill();
        }
    }

    public String publish(int classCount, int methodCount, long edgeCount) throws IOException, InterruptedException {
        requireOpen();
        if (classCount < 0 || methodCount < 0 || edgeCount < 0) {
            throw new IllegalArgumentException("Callgraph counts must not be negative");
        }
        spill();
        var outputCounts = new EnumMap<CallgraphDirection, DirectionCounts>(CallgraphDirection.class);
        for (CallgraphDirection direction : CallgraphDirection.values()) {
            outputCounts.put(direction, mergeDirection(direction));
        }
        BundleFiles.deleteTree(work);
        DirectionCounts callers = outputCounts.get(CallgraphDirection.CALLERS);
        DirectionCounts callees = outputCounts.get(CallgraphDirection.CALLEES);
        if (callers.dataRows() != edgeCount || callees.dataRows() != edgeCount) {
            throw new IOException("External sort row count does not match generated edge count");
        }
        List<CallgraphFileMetadata> metadata = List.of(metadata(CallgraphDirection.CALLERS.dataArtifact(), callers.dataRows()), metadata(CallgraphDirection.CALLERS.indexArtifact(), callers.indexRows()), metadata(CallgraphDirection.CALLEES.dataArtifact(), callees.dataRows()), metadata(CallgraphDirection.CALLEES.indexArtifact(), callees.indexRows()));
        var manifest = new CallgraphManifest(CallgraphBundleLayout.FORMAT, CallgraphBundleLayout.SCHEMA_VERSION, minecraftVersion.value(), remappedJarSha256, classCount, methodCount, edgeCount, metadata);
        byte[] manifestBytes = CallgraphJson.bytes(manifest);
        String identity = BundleHashes.sha256(manifestBytes);
        BundleFiles.writeForced(BundleFiles.safeChild(staging, "manifest.json"), manifestBytes);
        CallgraphBundleValidator.validateGeneration(root, staging, identity, manifest, cancellation);
        cancellation.throwIfCancelled();
        return promoteAndPublish(identity, manifest);
    }

    private CallgraphFileMetadata metadata(CallgraphArtifact artifact, long records) throws IOException, InterruptedException {
        return CallgraphBundleValidator.metadata(artifact.resolve(staging), artifact, records, cancellation);
    }

    private String promoteAndPublish(String identity, CallgraphManifest manifest) throws IOException, InterruptedException {
        BundleLock publicationLock = BundleLock.write(root, WRITE_LOCK_TIMEOUT);
        try {
            if (!publicationLock.isHeld()) {
                throw new IOException("Failed to acquire exclusive bundle lock");
            }
            Path generations = BundleFiles.safeChild(root, "generations");
            if (Files.exists(generations, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                BundleFiles.requireDirectory(root, generations);
            }
            else {
                Files.createDirectory(generations);
            }
            cleanupOrphanStaging();
            String previousGeneration = publishedGeneration();
            Path generation = generations.resolve(identity);
            BundleFiles.requireContained(root, generation);
            if (Files.exists(generation, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                BundleFiles.requireDirectory(root, generation);
                CallgraphBundleValidator.validateGeneration(root, generation, identity, manifest, cancellation);
                BundleFiles.deleteTree(staging);
            }
            else {
                BundleFiles.moveNewDirectory(staging, generation);
            }
            cleanupGenerations(previousGeneration, identity);
            cancellation.throwIfCancelled();
            stagingLease.close();
            publishPointer(identity);
            published = true;
        } catch (IOException | InterruptedException | RuntimeException | Error failure) {
            try {
                publicationLock.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        try {
            publicationLock.close();
        } catch (IOException ignored) {
            // Pointer replacement commits publication; lock release cannot roll it back.
        }
        return identity;
    }

    private void publishPointer(String identity) throws IOException {
        Path current = BundleFiles.safeChild(root, "current.json");
        Path temporary = BundleFiles.safeChild(root, "current.tmp");
        Files.deleteIfExists(temporary);
        BundleFiles.writeForced(temporary, CallgraphJson.bytes(new CallgraphPointer(CallgraphBundleLayout.POINTER_FORMAT, CallgraphBundleLayout.SCHEMA_VERSION, identity)));
        try {
            pointerPublisher.replace(temporary, current);
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    private void spill() throws IOException, InterruptedException {
        if (buffer.isEmpty()) {
            return;
        }
        List<CallgraphDataRecord> records = List.copyOf(buffer);
        buffer.clear();
        bufferBytes = 0;
        for (CallgraphDirection direction : CallgraphDirection.values()) {
            cancellation.throwIfCancelled();
            List<CallgraphDataRecord> sorted = new ArrayList<>(records);
            sorted.sort(direction.comparator());
            Path run = work.resolve(direction.name().toLowerCase(java.util.Locale.ROOT) + "-run-" + nextRun + ".jsonl");
            writeRecords(run, sorted);
            runs.get(direction).add(run);
        }
        nextRun++;
    }

    private DirectionCounts mergeDirection(CallgraphDirection direction) throws IOException, InterruptedException {
        List<Path> current = List.copyOf(runs.get(direction));
        int pass = 0;
        while (current.size() > mergeFanIn) {
            List<Path> next = new ArrayList<>();
            for (int offset = 0; offset < current.size(); offset += mergeFanIn) {
                cancellation.throwIfCancelled();
                List<Path> group = current.subList(offset, Math.min(current.size(), offset + mergeFanIn));
                Path output = work.resolve(direction.name().toLowerCase(java.util.Locale.ROOT) + "-pass-" + pass + "-" + next.size() + ".jsonl");
                mergeRuns(direction, group, output, null);
                next.add(output);
            }
            for (Path path : current) {
                Files.delete(path);
            }
            current = List.copyOf(next);
            pass++;
        }
        Path data = BundleFiles.safeChild(staging, direction.dataFileName());
        Path index = BundleFiles.safeChild(staging, direction.indexFileName());
        DirectionCounts counts = mergeRuns(direction, current, data, index);
        for (Path path : current) {
            Files.delete(path);
        }
        return counts;
    }

    private DirectionCounts mergeRuns(CallgraphDirection direction, List<Path> inputs, Path output, Path index) throws IOException, InterruptedException {
        List<RunCursor> cursors = new ArrayList<>();
        var queue = new PriorityQueue<>(Comparator.comparing(RunCursor::record, direction.comparator()).thenComparingInt(RunCursor::ordinal));
        Throwable primaryFailure = null;
        try {
            int ordinal = 0;
            for (Path input : inputs) {
                RunCursor cursor = new RunCursor(input, ordinal++);
                cursors.add(cursor);
                if (cursor.advance()) {
                    queue.add(cursor);
                }
            }
            try (FileChannel dataChannel = FileChannel.open(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                 FileChannel indexChannel = index == null ? null : FileChannel.open(index, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                long dataRows = 0;
                long indexRows = 0;
                CallgraphDirection.LookupKey groupKey = null;
                long groupOffset = 0;
                long groupRows = 0;
                while (!queue.isEmpty()) {
                    cancellation.throwIfCancelled();
                    RunCursor cursor = queue.remove();
                    CallgraphDataRecord record = cursor.record();
                    CallgraphDirection.LookupKey key = direction.lookupKey(record);
                    if (indexChannel != null && !key.equals(groupKey)) {
                        if (groupKey != null) {
                            writeIndex(indexChannel, groupKey, groupOffset, dataChannel.position() - groupOffset, groupRows);
                            indexRows++;
                        }
                        groupKey = key;
                        groupOffset = dataChannel.position();
                        groupRows = 0;
                    }
                    write(dataChannel, CallgraphJson.line(record));
                    dataRows++;
                    groupRows++;
                    if (cursor.advance()) {
                        queue.add(cursor);
                    }
                }
                if (indexChannel != null && groupKey != null) {
                    writeIndex(indexChannel, groupKey, groupOffset, dataChannel.position() - groupOffset, groupRows);
                    indexRows++;
                }
                dataChannel.force(true);
                if (indexChannel != null) {
                    indexChannel.force(true);
                }
                return new DirectionCounts(dataRows, indexRows);
            }
        } catch (IOException | InterruptedException | RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            IOException failure = null;
            for (RunCursor cursor : cursors) {
                try {
                    cursor.close();
                } catch (IOException exception) {
                    if (failure == null) {
                        failure = exception;
                    }
                    else {
                        failure.addSuppressed(exception);
                    }
                }
            }
            if (failure != null) {
                if (primaryFailure == null) {
                    throw failure;
                }
                primaryFailure.addSuppressed(failure);
            }
        }
    }

    private String publishedGeneration() throws IOException {
        Path current = BundleFiles.safeChild(root, "current.json");
        if (!Files.exists(current, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        return CallgraphBundleLayout.resolve(root).identity();
    }

    private void cleanupOrphanStaging() throws IOException, InterruptedException {
        List<Path> children;
        try (var paths = Files.list(root)) {
            children = paths.toList();
        }
        for (Path child : children) {
            cancellation.throwIfCancelled();
            String name = child.getFileName().toString();
            if (isStagingMarkerName(name) && !child.equals(stagingLease.marker())) {
                cleanupStagingLease(child);
            }
        }
        for (Path child : children) {
            cancellation.throwIfCancelled();
            String name = child.getFileName().toString();
            if (!isStagingDirectoryName(name) || child.equals(staging) || !Files.exists(child, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            Path marker = BundleFiles.safeChild(root, name + ".lock");
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            BundleFiles.requireDirectory(root, child);
            BundleFiles.deleteTree(child);
        }
    }

    private void cleanupStagingLease(Path marker) throws IOException {
        try {
            BundleFiles.requireRegularFile(root, marker);
            try (FileChannel channel = FileChannel.open(marker, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                FileLock orphanLock;
                try {
                    orphanLock = channel.tryLock();
                } catch (OverlappingFileLockException ignored) {
                    return;
                }
                if (orphanLock == null) {
                    return;
                }
                try (orphanLock) {
                    String markerName = marker.getFileName().toString();
                    Path orphan = BundleFiles.safeChild(root, markerName.substring(0, markerName.length() - ".lock".length()));
                    if (Files.exists(orphan, LinkOption.NOFOLLOW_LINKS)) {
                        BundleFiles.requireDirectory(root, orphan);
                        BundleFiles.deleteTree(orphan);
                    }
                }
            }
            Files.deleteIfExists(marker);
        } catch (NoSuchFileException ignored) {
            // A concurrently closing writer already removed its lease.
        }
    }

    private void cleanupGenerations(String previousGeneration, String newGeneration) throws IOException, InterruptedException {
        Path generations = BundleFiles.safeChild(root, "generations");
        BundleFiles.requireDirectory(root, generations);
        Set<String> retained = previousGeneration == null || previousGeneration.equals(newGeneration) ? Set.of(newGeneration) : Set.of(previousGeneration, newGeneration);
        try (var children = Files.list(generations)) {
            for (Path child : children.toList()) {
                cancellation.throwIfCancelled();
                String name = child.getFileName().toString();
                if (retained.contains(name)) {
                    continue;
                }
                if (CallgraphBundleLayout.isInvalidSha256(name) || Files.isSymbolicLink(child) || !Files.isDirectory(child, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Unsafe artifact in callgraph generations directory: " + child);
                }
                BundleFiles.requireDirectory(root, child);
                BundleFiles.deleteTree(child);
            }
        }
    }

    private void writeRecords(Path output, List<CallgraphDataRecord> records) throws IOException, InterruptedException {
        try (FileChannel channel = FileChannel.open(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            for (CallgraphDataRecord record : records) {
                cancellation.throwIfCancelled();
                write(channel, CallgraphJson.line(record));
            }
            channel.force(true);
        }
    }

    private void requireOpen() {
        if (closed || published) {
            throw new IllegalStateException("Callgraph bundle writer is no longer open");
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        if (!published) {
            try {
                BundleFiles.deleteTree(staging);
            } catch (IOException exception) {
                failure = exception;
            }
        }
        try {
            stagingLease.close();
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            }
            else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @FunctionalInterface
    interface PointerPublisher {
        @SuppressWarnings("unused")
        void replace(Path source, Path target) throws IOException;
    }

    private static final class RunCursor implements AutoCloseable {
        private final JsonlLineReader lines;
        private final int ordinal;
        private CallgraphDataRecord record;

        private RunCursor(Path path, int ordinal) throws IOException {
            lines = new JsonlLineReader(Files.newInputStream(path), CallgraphBundleLayout.MAXIMUM_JSONL_LINE_BYTES);
            this.ordinal = ordinal;
        }

        private boolean advance() throws IOException {
            byte[] line = lines.next();
            if (line == null) {
                record = null;
                return false;
            }
            try {
                record = CallgraphJson.readCanonical(line, CallgraphDataRecord.class, "External-sort callgraph run");
                return true;
            } catch (IOException | RuntimeException exception) {
                throw new IOException("Invalid external-sort callgraph run", exception);
            }
        }

        private CallgraphDataRecord record() {
            return record;
        }

        private int ordinal() {
            return ordinal;
        }

        @Override
        public void close() throws IOException {
            lines.close();
        }
    }

    private static final class StagingLease implements AutoCloseable {
        private final Path staging;
        private final Path marker;
        private final FileChannel channel;
        private final FileLock lock;
        private boolean closed;

        private StagingLease(Path staging, Path marker, FileChannel channel, FileLock lock) {
            this.staging = staging;
            this.marker = marker;
            this.channel = channel;
            this.lock = lock;
        }

        private static StagingLease create(Path root) throws IOException {
            String name = ".staging-" + UUID.randomUUID();
            Path staging = BundleFiles.safeChild(root, name);
            Path marker = BundleFiles.safeChild(root, name + ".lock");
            FileChannel channel = FileChannel.open(marker, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
            try {
                return new StagingLease(staging, marker, channel, channel.lock());
            } catch (IOException | RuntimeException exception) {
                try {
                    channel.close();
                } catch (IOException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
                try {
                    Files.deleteIfExists(marker);
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
                throw exception;
            }
        }

        private Path staging() {
            return staging;
        }

        private Path marker() {
            return marker;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            IOException failure = null;
            try {
                lock.release();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                channel.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                }
                else {
                    failure.addSuppressed(exception);
                }
            }
            try {
                Files.deleteIfExists(marker);
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                }
                else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private record DirectionCounts(long dataRows, long indexRows) {
    }
}

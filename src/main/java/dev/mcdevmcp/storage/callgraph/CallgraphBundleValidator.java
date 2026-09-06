package dev.mcdevmcp.storage.callgraph;

import dev.mcdevmcp.storage.bundle.BundleFiles;
import dev.mcdevmcp.storage.bundle.BundleHashes;
import dev.mcdevmcp.storage.bundle.BundleLock;
import dev.mcdevmcp.storage.bundle.JsonlLineReader;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.support.Cancellation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

public final class CallgraphBundleValidator {
    private static final Duration READ_LOCK_TIMEOUT = Duration.ofSeconds(30);

    private CallgraphBundleValidator() {
    }

    public static void validate(Path bundle) throws IOException {
        Path root = bundle.toAbsolutePath().normalize();
        try (var lock = BundleLock.read(root, READ_LOCK_TIMEOUT)) {
            if (!lock.isHeld()) {
                throw new IOException("Failed to acquire shared bundle lock");
            }
            CallgraphBundleLayout.ResolvedGeneration generation = CallgraphBundleLayout.resolve(root);
            try {
                validateGeneration(root, generation.path(), generation.identity(), generation.manifest(), Cancellation.none());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Callgraph validation interrupted", exception);
            }
        }
    }

    static void validateArtifacts(Path bundle) throws IOException {
        Path root = bundle.toAbsolutePath().normalize();
        try (var lock = BundleLock.read(root, READ_LOCK_TIMEOUT)) {
            if (!lock.isHeld()) {
                throw new IOException("Failed to acquire shared bundle lock");
            }
            CallgraphBundleLayout.ResolvedGeneration generation = CallgraphBundleLayout.resolve(root);
            try {
                validateArtifacts(root, generation.path(), generation.identity(), generation.manifest(), Cancellation.none());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Callgraph artifact validation interrupted", exception);
            }
        }
    }

    static void validateGeneration(Path root, Path generation, String identity, CallgraphManifest manifest, Cancellation cancellation) throws IOException, InterruptedException {
        validateArtifacts(root, generation, identity, manifest, cancellation);
        validateDirection(generation, manifest, CallgraphDirection.CALLERS, cancellation);
        validateDirection(generation, manifest, CallgraphDirection.CALLEES, cancellation);
    }

    private static void validateArtifacts(Path root, Path generation, String identity, CallgraphManifest manifest, Cancellation cancellation) throws IOException, InterruptedException {
        BundleFiles.requireDirectory(root, generation);
        CallgraphBundleLayout.validateManifestShape(manifest);
        try {
            new MinecraftVersion(manifest.minecraftVersion());
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid Minecraft version in callgraph manifest", exception);
        }
        Path manifestPath = BundleFiles.safeChild(generation, "manifest.json");
        BundleFiles.requireRegularFile(root, manifestPath);
        if (!BundleHashes.sha256(Files.readAllBytes(manifestPath)).equals(identity)) {
            throw new IOException("Callgraph manifest identity mismatch");
        }
        Set<String> expectedFiles = new HashSet<>();
        for (CallgraphArtifact artifact : CallgraphBundleLayout.ARTIFACTS) {
            expectedFiles.add(artifact.fileName());
        }
        expectedFiles.add("manifest.json");
        try (var children = Files.list(generation)) {
            Set<String> actual = new HashSet<>();
            for (Path child : children.toList()) {
                if (Files.isSymbolicLink(child)) {
                    throw new IOException("Callgraph generation contains a symbolic link: " + child);
                }
                actual.add(child.getFileName().toString());
            }
            if (!actual.equals(expectedFiles)) {
                throw new IOException("Callgraph generation artifact set mismatch: " + actual);
            }
        }
        for (CallgraphArtifact artifact : CallgraphBundleLayout.ARTIFACTS) {
            validateArtifact(root, artifact.resolve(generation), CallgraphBundleLayout.metadata(manifest, artifact), cancellation);
        }
    }

    static CallgraphFileMetadata metadata(Path file, CallgraphArtifact artifact, long records, Cancellation cancellation) throws IOException, InterruptedException {
        if (records < 0) {
            throw new IllegalArgumentException("records must not be negative");
        }
        return new CallgraphFileMetadata(artifact, Files.size(file), records, BundleHashes.sha256(file, cancellation));
    }

    private static void validateDirection(Path generation, CallgraphManifest manifest, CallgraphDirection direction, Cancellation cancellation) throws IOException, InterruptedException {
        CallgraphFileMetadata dataMetadata = CallgraphBundleLayout.metadata(manifest, direction.dataArtifact());
        CallgraphFileMetadata indexMetadata = CallgraphBundleLayout.metadata(manifest, direction.indexArtifact());
        Path data = direction.dataArtifact().resolve(generation);
        Path index = direction.indexArtifact().resolve(generation);
        long dataRows = 0;
        long indexRows = 0;
        CallgraphDirection.LookupKey previousKey = null;
        try (InputStream dataInput = Files.newInputStream(data); InputStream indexInput = Files.newInputStream(index);
             var dataLines = new JsonlLineReader(dataInput, CallgraphBundleLayout.MAXIMUM_JSONL_LINE_BYTES);
             var indexLines = new JsonlLineReader(indexInput, CallgraphBundleLayout.MAXIMUM_JSONL_LINE_BYTES)) {
            byte[] indexLine;
            while ((indexLine = indexLines.next()) != null) {
                cancellation.throwIfCancelled();
                CallgraphIndexRecord range = parse(indexLine, CallgraphIndexRecord.class, direction.indexFileName());
                CallgraphDirection.LookupKey key = new CallgraphDirection.LookupKey(range.className(), range.methodName());
                if (previousKey != null && previousKey.compareTo(key) >= 0) {
                    throw new IOException("Callgraph index keys are not strictly increasing");
                }
                if (range.byteOffset() != dataLines.bytesRead()) {
                    throw new IOException("Callgraph index range is not contiguous at " + key);
                }
                long end = add(range.byteOffset(), range.byteLength(), "Callgraph index byte range overflow");
                if (end > dataMetadata.byteLength()) {
                    throw new IOException("Callgraph index range exceeds " + direction.dataFileName());
                }
                long start = dataLines.bytesRead();
                CallgraphDataRecord previousRecord = null;
                for (long row = 0; row < range.rowCount(); row++) {
                    cancellation.throwIfCancelled();
                    byte[] dataLine = dataLines.next();
                    if (dataLine == null) {
                        throw new IOException("Callgraph data ended inside index range " + key);
                    }
                    CallgraphDataRecord record = parse(dataLine, CallgraphDataRecord.class, direction.dataFileName());
                    if (!direction.lookupKey(record).equals(key)) {
                        throw new IOException("Callgraph data lookup key does not match index range " + key);
                    }
                    if (previousRecord != null && direction.comparator().compare(previousRecord, record) >= 0) {
                        throw new IOException("Callgraph data is not strictly monotonic inside index range " + key);
                    }
                    previousRecord = record;
                    dataRows = add(dataRows, 1, "Callgraph data row count overflow");
                }
                if (dataLines.bytesRead() - start != range.byteLength()) {
                    throw new IOException("Callgraph index byte length does not match its data range " + key);
                }
                previousKey = key;
                indexRows = add(indexRows, 1, "Callgraph index row count overflow");
            }
            if (dataLines.next() != null || dataLines.bytesRead() != dataMetadata.byteLength()) {
                throw new IOException("Callgraph index does not cover the complete data artifact");
            }
        }
        if (dataRows != dataMetadata.recordCount() || dataRows != manifest.edgeCount() || indexRows != indexMetadata.recordCount()) {
            throw new IOException("Callgraph manifest record counts do not match validated JSONL records");
        }
    }

    private static void validateArtifact(Path root, Path file, CallgraphFileMetadata metadata, Cancellation cancellation) throws IOException, InterruptedException {
        BundleFiles.requireRegularFile(root, file);
        if (Files.size(file) != metadata.byteLength()) {
            throw new IOException("Callgraph artifact byte length mismatch: " + metadata.artifact());
        }
        if (!BundleHashes.sha256(file, cancellation).equals(metadata.sha256())) {
            throw new IOException("Callgraph artifact SHA-256 mismatch: " + metadata.artifact());
        }
    }

    private static long add(long left, long right, String message) throws IOException {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IOException(message, exception);
        }
    }

    private static <T> T parse(byte[] bytes, Class<T> type, String artifactFileName) throws IOException {
        try {
            return CallgraphJson.readCanonical(bytes, type, artifactFileName);
        } catch (IOException | RuntimeException exception) {
            throw new IOException("Invalid typed JSON record in " + artifactFileName, exception);
        }
    }
}

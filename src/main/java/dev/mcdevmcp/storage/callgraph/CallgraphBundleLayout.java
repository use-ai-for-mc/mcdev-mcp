package dev.mcdevmcp.storage.callgraph;

import dev.mcdevmcp.storage.bundle.BundleFiles;
import dev.mcdevmcp.storage.bundle.BundleHashes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class CallgraphBundleLayout {
    static final String FORMAT = "mcdev-callgraph-jsonl";
    static final String POINTER_FORMAT = "mcdev-callgraph-jsonl-pointer";
    static final int SCHEMA_VERSION = 1;
    static final int MAXIMUM_MANIFEST_BYTES = 1024 * 1024;
    static final int MAXIMUM_POINTER_BYTES = 16 * 1024;
    static final int MAXIMUM_JSONL_LINE_BYTES = 1024 * 1024;
    static final List<CallgraphArtifact> ARTIFACTS = List.of(CallgraphArtifact.CALLERS_DATA, CallgraphArtifact.CALLERS_INDEX, CallgraphArtifact.CALLEES_DATA, CallgraphArtifact.CALLEES_INDEX);

    private CallgraphBundleLayout() {
    }

    static ResolvedGeneration resolve(Path bundle) throws IOException {
        Path root = bundle.toAbsolutePath().normalize();
        BundleFiles.requireDirectory(root, root);
        Path pointerPath = BundleFiles.safeChild(root, "current.json");
        BundleFiles.requireRegularFile(root, pointerPath);
        CallgraphPointer pointer = CallgraphJson.readCanonical(readSmall(pointerPath, MAXIMUM_POINTER_BYTES, "Callgraph pointer"), CallgraphPointer.class, "Callgraph pointer");
        if (!POINTER_FORMAT.equals(pointer.format()) || pointer.schemaVersion() != SCHEMA_VERSION || isInvalidSha256(pointer.generation())) {
            throw new IOException("Invalid callgraph current.json pointer");
        }
        Path generations = BundleFiles.safeChild(root, "generations");
        BundleFiles.requireDirectory(root, generations);
        Path generation = generations.resolve(pointer.generation()).normalize();
        if (!generation.getParent().equals(generations)) {
            throw new IOException("Unsafe callgraph generation path");
        }
        BundleFiles.requireDirectory(root, generation);
        Path manifestPath = BundleFiles.safeChild(generation, "manifest.json");
        BundleFiles.requireRegularFile(root, manifestPath);
        byte[] manifestBytes = readSmall(manifestPath, MAXIMUM_MANIFEST_BYTES, "Callgraph manifest");
        if (!BundleHashes.sha256(manifestBytes).equals(pointer.generation())) {
            throw new IOException("Callgraph generation does not match its manifest SHA-256");
        }
        CallgraphManifest manifest = CallgraphJson.readCanonical(manifestBytes, CallgraphManifest.class, "Callgraph manifest");
        validateManifestShape(manifest);
        return new ResolvedGeneration(pointer.generation(), generation, manifest);
    }

    static void validateManifestShape(CallgraphManifest manifest) throws IOException {
        if (!FORMAT.equals(manifest.format()) || manifest.schemaVersion() != SCHEMA_VERSION || isInvalidSha256(manifest.remappedJarSha256())) {
            throw new IOException("Unsupported callgraph manifest format or schema");
        }
        if (manifest.minecraftVersion().isBlank()) {
            throw new IOException("Callgraph manifest has a blank Minecraft version");
        }
        if (manifest.files().size() != ARTIFACTS.size()) {
            throw new IOException("Callgraph manifest must describe exactly four JSONL artifacts");
        }
        for (int index = 0; index < ARTIFACTS.size(); index++) {
            CallgraphFileMetadata metadata = manifest.files().get(index);
            if (ARTIFACTS.get(index) != metadata.artifact() || isInvalidSha256(metadata.sha256())) {
                throw new IOException("Invalid callgraph artifact metadata at index " + index);
            }
        }
    }

    static CallgraphFileMetadata metadata(CallgraphManifest manifest, CallgraphArtifact artifact) throws IOException {
        for (CallgraphFileMetadata metadata : manifest.files()) {
            if (metadata.artifact() == artifact) {
                return metadata;
            }
        }
        throw new IOException("Callgraph manifest does not describe " + artifact);
    }

    static boolean isInvalidSha256(String value) {
        if (value == null || value.length() != 64) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f'))) {
                return true;
            }
        }
        return false;
    }

    private static byte[] readSmall(Path file, int maximumBytes, String label) throws IOException {
        long size = Files.size(file);
        if (size < 1 || size > maximumBytes) {
            throw new IOException(label + " size is outside the supported range: " + size);
        }
        return Files.readAllBytes(file);
    }

    record ResolvedGeneration(String identity, Path path, CallgraphManifest manifest) {
    }
}
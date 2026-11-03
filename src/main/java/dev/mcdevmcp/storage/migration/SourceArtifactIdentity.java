package dev.mcdevmcp.storage.migration;

import dev.mcdevmcp.storage.bundle.BundleHashes;
import dev.mcdevmcp.support.Cancellation;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HexFormat;
import java.util.Objects;

public value record SourceArtifactIdentity(String path, long size, String sha256) {
    public SourceArtifactIdentity {
        Objects.requireNonNull(path, "path");
        if (size < 0 || sha256.length() != 64 || HexFormat.of().parseHex(sha256).length != 32) {
            throw new IllegalArgumentException("Invalid artifact identity");
        }
    }

    public static SourceArtifactIdentity capture(Path artifact, Cancellation cancellation) throws IOException {
        Path path = artifact.toAbsolutePath().normalize();
        SourceTreeInventory.checkPath(path);
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.isSymbolicLink() || before.isOther()) {
            throw new IOException("Source input must be a regular file: " + path);
        }
        try {
            String hash = BundleHashes.sha256(path, cancellation);
            BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!after.isRegularFile() || after.size() != before.size() || !after.lastModifiedTime().equals(before.lastModifiedTime()) || !Objects.equals(after.fileKey(), before.fileKey())) {
                throw new IOException("Source input changed while hashing: " + path);
            }
            return new SourceArtifactIdentity(path.toString(), after.size(), hash);
        } catch (InterruptedException exception) {
            throw new InterruptedIOException("Source input hashing cancelled");
        }
    }
}
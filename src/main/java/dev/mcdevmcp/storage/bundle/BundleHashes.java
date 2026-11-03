package dev.mcdevmcp.storage.bundle;

import dev.mcdevmcp.support.Cancellation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class BundleHashes {
    private BundleHashes() {
    }

    public static String sha256(Path file, Cancellation cancellation) throws IOException, InterruptedException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(cancellation, "cancellation");
        MessageDigest digest = sha256();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                cancellation.throwIfCancelled();
                digest.update(buffer, 0, read);
            }
        }
        cancellation.throwIfCancelled();
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(Objects.requireNonNull(bytes, "bytes")));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is required by the Java platform", exception);
        }
    }
}
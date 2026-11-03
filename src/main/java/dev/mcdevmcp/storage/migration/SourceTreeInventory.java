package dev.mcdevmcp.storage.migration;

import dev.mcdevmcp.support.Cancellation;
import reactor.util.annotation.NonNull;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Entries use String.compareTo UTF-16 code-unit order, without locale or Unicode normalization.
 * A present inventory always includes its root entry; an absent root has no entries.
 */
public value record SourceTreeInventory(boolean present, List<SourceInventoryEntry> entries, String sha256) {
    public SourceTreeInventory {
        entries = List.copyOf(entries);
        String previous = null;
        for (SourceInventoryEntry entry : entries) {
            if (previous != null && previous.compareTo(entry.relativePath()) >= 0) {
                throw new IllegalArgumentException("Unsorted or duplicate inventory path");
            }
            previous = entry.relativePath();
        }
        if (present == entries.isEmpty() || present && !entries.getFirst().relativePath().isEmpty()) {
            throw new IllegalArgumentException("Invalid inventory root");
        }
        var directories = new HashSet<String>();
        for (SourceInventoryEntry entry : entries) {
            if (!entry.relativePath().isEmpty()) {
                int slash = entry.relativePath().lastIndexOf('/');
                String parent = slash < 0 ? "" : entry.relativePath().substring(0, slash);
                if (!directories.contains(parent)) {
                    throw new IllegalArgumentException("Missing inventory parent directory");
                }
            }
            if (entry.kind() == SourceEntryKind.DIRECTORY) directories.add(entry.relativePath());
        }
        if (!digest(entries).equals(sha256)) throw new IllegalArgumentException("Inventory digest mismatch");
    }

    public static SourceTreeInventory capture(Path root, Cancellation cancellation) throws IOException {
        return capture(root, cancellation, Set.of());
    }

    public static SourceTreeInventory capture(Path root, CachePathBoundary boundary, Cancellation cancellation) throws IOException {
        return capture(root, boundary, cancellation, Set.of());
    }

    static SourceTreeInventory capture(Path root, Cancellation cancellation, Set<String> excluded) throws IOException {
        return capture(root, null, cancellation, excluded);
    }

    static SourceTreeInventory capture(Path root, CachePathBoundary boundary, Cancellation cancellation, Set<String> excluded) throws IOException {
        Path absolute = boundary == null ? root.toAbsolutePath().normalize() : boundary.resolve(root);
        checkPath(absolute, boundary);
        checkCancelled(cancellation);
        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) return of(false, List.of());
        var entries = new ArrayList<SourceInventoryEntry>();
        Files.walkFileTree(absolute, new SimpleFileVisitor<>() {
            @Override
            @NonNull
            public FileVisitResult preVisitDirectory(@NonNull Path dir, @NonNull BasicFileAttributes attrs) throws IOException {
                checkCancelled(cancellation);
                checkPath(dir, boundary);
                entries.add(new SourceInventoryEntry(relative(dir), SourceEntryKind.DIRECTORY, 0, ""));
                return FileVisitResult.CONTINUE;
            }

            @Override
            @NonNull
            public FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attrs) throws IOException {
                checkCancelled(cancellation);
                checkPath(file, boundary);
                String relative = relative(file);
                if (excluded.contains(relative)) return FileVisitResult.CONTINUE;
                if (!attrs.isRegularFile() || attrs.isOther() || attrs.isSymbolicLink()) {
                    throw new IOException("Unsafe inventory file: " + file);
                }
                MessageDigest digest = newDigest();
                long count = 0;
                try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        checkCancelled(cancellation);
                        digest.update(buffer, 0, read);
                        count += read;
                    }
                }
                BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (count != attrs.size() || after.size() != attrs.size() || !after.lastModifiedTime().equals(attrs.lastModifiedTime()) || !Objects.equals(after.fileKey(), attrs.fileKey())) {
                    throw new IOException("File changed during inventory: " + file);
                }
                entries.add(new SourceInventoryEntry(relative, SourceEntryKind.FILE, count, HexFormat.of().formatHex(digest.digest())));
                return FileVisitResult.CONTINUE;
            }

            private String relative(Path path) {
                return absolute.relativize(path).toString().replace('\\', '/');
            }
        });
        entries.sort(Comparator.comparing(SourceInventoryEntry::relativePath));
        return of(true, entries);
    }

    static SourceTreeInventory of(boolean present, List<SourceInventoryEntry> entries) {
        return new SourceTreeInventory(present, entries, digest(entries));
    }

    static void checkCancelled(Cancellation cancellation) throws InterruptedIOException {
        if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Source publication cancelled");
        }
    }

    static void checkPath(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        for (Path cursor = absolute; cursor != null; cursor = cursor.getParent()) {
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attrs = Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attrs.isSymbolicLink() || attrs.isOther() || !cursor.toRealPath().equals(cursor)) {
                    throw new IOException("Link, reparse or redirected migration path: " + cursor);
                }
            }
        }
    }

    private static void checkPath(Path path, CachePathBoundary boundary) throws IOException {
        if (boundary == null) {
            checkPath(path);
        }
        else {
            boundary.require(path);
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String digest(List<SourceInventoryEntry> entries) {
        MessageDigest digest = newDigest();
        try (var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            out.write("mcdev-source-inventory-v1".getBytes(StandardCharsets.UTF_8));
            for (SourceInventoryEntry entry : entries) {
                out.writeByte(entry.kind() == SourceEntryKind.FILE ? 'F' : 'D');
                writeText(out, entry.relativePath());
                if (entry.kind() == SourceEntryKind.FILE) {
                    out.writeLong(entry.size());
                    out.write(HexFormat.of().parseHex(entry.sha256()));
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void writeText(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}

package dev.mcdevmcp.storage.bundle;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

public final class BundleFiles {
    private BundleFiles() {
    }

    public static Path safeChild(Path root, String fileName) throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(fileName, "fileName");
        if (fileName.isEmpty() || !Path.of(fileName).getFileName().toString().equals(fileName)) {
            throw new IOException("Unsafe bundle artifact name: " + fileName);
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path child = normalizedRoot.resolve(fileName).normalize();
        if (!child.getParent().equals(normalizedRoot)) {
            throw new IOException("Unsafe bundle artifact path: " + child);
        }
        return child;
    }

    public static void requireRegularFile(Path root, Path file) throws IOException {
        requireContained(root, file);
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Bundle artifact is not a regular file: " + file);
        }
    }

    public static void requireDirectory(Path root, Path directory) throws IOException {
        requireContained(root, directory);
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Bundle path is not a directory: " + directory);
        }
    }

    public static void requireContained(Path root, Path path) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) {
            throw new IOException("Bundle path escapes its root: " + normalizedPath);
        }
        Path current = normalizedRoot;
        if (Files.exists(current, java.nio.file.LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
            throw new IOException("Bundle path contains a symbolic link: " + current);
        }
        for (Path component : normalizedRoot.relativize(normalizedPath)) {
            current = current.resolve(component);
            if (Files.exists(current, java.nio.file.LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IOException("Bundle path contains a symbolic link: " + current);
            }
        }
    }

    public static void writeForced(Path file, byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                int written = channel.write(buffer);
                if (written < 0) {
                    throw new IOException("Unexpected end while writing bundle artifact");
                }
            }
            channel.force(true);
        }
    }

    @SuppressWarnings("unused")
    public static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    public static void atomicReplace(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public static void moveNewDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    @SuppressWarnings("NullableProblems")
    public static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
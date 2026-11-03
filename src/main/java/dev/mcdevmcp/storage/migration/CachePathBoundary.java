package dev.mcdevmcp.storage.migration;

import dev.mcdevmcp.storage.PlatformPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;

/**
 * Pins a caller-selected cache root while allowing aliases strictly above it.
 */
public final class CachePathBoundary {
    private final Path logicalRoot;
    private final Path physicalRoot;
    private final Object fileKey;
    private final FileTime creationTime;

    private CachePathBoundary(Path logicalRoot, Path physicalRoot, BasicFileAttributes attributes) {
        this.logicalRoot = logicalRoot;
        this.physicalRoot = physicalRoot;
        fileKey = attributes.fileKey();
        creationTime = attributes.creationTime();
    }

    public static CachePathBoundary open(PlatformPaths paths) throws IOException {
        Path logical = Objects.requireNonNull(paths, "paths").cacheRoot().toAbsolutePath().normalize();
        if (!Files.exists(logical, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(logical);
        BasicFileAttributes selected = directory(logical);
        Path physical = logical.toRealPath();
        BasicFileAttributes resolved = directory(physical);
        if (!Objects.equals(selected.fileKey(), resolved.fileKey()) || !selected.creationTime().equals(resolved.creationTime())) {
            throw new IOException("Cache boundary changed while resolving: " + logical);
        }
        CachePathBoundary boundary = new CachePathBoundary(logical, physical, resolved);
        boundary.revalidate();
        return boundary;
    }

    private static BasicFileAttributes directory(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("Cache boundary must be a real directory: " + path);
        }
        return attributes;
    }

    public Path physicalRoot() {
        return physicalRoot;
    }

    public PlatformPaths resolvedPaths() {
        return new PlatformPaths(physicalRoot);
    }

    public void revalidate() throws IOException {
        BasicFileAttributes attributes = directory(physicalRoot);
        if (!physicalRoot.toRealPath().equals(physicalRoot) || !Objects.equals(fileKey, attributes.fileKey()) || !creationTime.equals(attributes.creationTime())) {
            throw new IOException("Pinned cache boundary changed: " + physicalRoot);
        }
    }

    public void require(PlatformPaths paths) throws IOException {
        Path selected = Objects.requireNonNull(paths, "paths").cacheRoot().toAbsolutePath().normalize();
        if (!selected.equals(logicalRoot) && !selected.equals(physicalRoot)) {
            throw new IOException("Cache paths do not belong to the held boundary: " + selected);
        }
        revalidate();
    }

    public Path require(Path child) throws IOException {
        return resolve(child);
    }

    public Path resolve(Path child) throws IOException {
        revalidate();
        Path normalized = Objects.requireNonNull(child, "child").toAbsolutePath().normalize();
        Path relative;
        if (normalized.startsWith(physicalRoot)) {
            relative = physicalRoot.relativize(normalized);
        }
        else if (normalized.startsWith(logicalRoot)) {
            relative = logicalRoot.relativize(normalized);
        }
        else {
            throw new IOException("Path is outside the selected cache boundary: " + child);
        }
        Path resolved = physicalRoot.resolve(relative).normalize();
        Path current = physicalRoot;
        int index = 0;
        for (Path component : relative) {
            current = current.resolve(component);
            index++;
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException absent) {
                continue;
            }
            if (attributes.isSymbolicLink() || attributes.isOther() || !attributes.isDirectory() && (!attributes.isRegularFile() || index < relative.getNameCount()) || !current.toRealPath().equals(current)) {
                throw new IOException("Unsafe cache-relative path: " + current);
            }
        }
        return resolved;
    }
}
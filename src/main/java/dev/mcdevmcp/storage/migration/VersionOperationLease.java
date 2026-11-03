package dev.mcdevmcp.storage.migration;

import dev.mcdevmcp.storage.PlatformPaths;
import dev.mcdevmcp.storage.symbols.IndexLock;
import dev.mcdevmcp.storage.model.MinecraftVersion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Holds one version's source/index generation stable for an entire operation.
 */
public final class VersionOperationLease implements AutoCloseable {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private final CachePathBoundary boundary;
    private final MinecraftVersion version;
    private final IndexLock lock;
    private final boolean write;
    private final Thread owner;
    private boolean closed;

    private VersionOperationLease(CachePathBoundary boundary, MinecraftVersion version, IndexLock lock, boolean write) {
        this.boundary = boundary;
        this.version = version;
        this.lock = lock;
        this.write = write;
        owner = Thread.currentThread();
    }

    public static VersionOperationLease read(PlatformPaths paths, MinecraftVersion version) throws IOException {
        return read(CachePathBoundary.open(paths), version);
    }

    public static VersionOperationLease read(CachePathBoundary boundary, MinecraftVersion version) throws IOException {
        VersionOperationLease lease = acquire(boundary, version, false);
        try {
            lease.requireNoPending(boundary.resolvedPaths(), version);
            return lease;
        } catch (IOException | RuntimeException exception) {
            try {
                lease.close();
            } catch (IOException failure) {
                exception.addSuppressed(failure);
            }
            throw exception;
        }
    }

    public static VersionOperationLease write(PlatformPaths paths, MinecraftVersion version) throws IOException {
        return write(CachePathBoundary.open(paths), version);
    }

    public static VersionOperationLease write(CachePathBoundary boundary, MinecraftVersion version) throws IOException {
        return acquire(boundary, version, true);
    }

    private static VersionOperationLease acquire(CachePathBoundary boundary, MinecraftVersion version, boolean write) throws IOException {
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(version, "version");
        Path key = boundary.resolve(boundary.physicalRoot().resolve("locks/analysis").resolve(version.value()).resolve("operation"));
        boundary.require(key.resolveSibling("operation.lock"));
        IndexLock lock = write ? IndexLock.write(key, TIMEOUT) : IndexLock.read(key, TIMEOUT);
        VersionOperationLease lease = new VersionOperationLease(boundary, version, lock, write);
        try {
            boundary.require(key.resolveSibling("operation.lock"));
            return lease;
        } catch (IOException | RuntimeException exception) {
            try {
                lease.close();
            } catch (IOException failure) {
                exception.addSuppressed(failure);
            }
            throw exception;
        }
    }

    public void require(PlatformPaths paths, MinecraftVersion expectedVersion) throws IOException {
        if (closed || owner != Thread.currentThread() || !version.equals(expectedVersion) || !lock.isHeld()) {
            throw new IllegalStateException("A held operation lease for Minecraft " + expectedVersion + " is required");
        }
        boundary.require(paths);
    }

    public void requireNoPending(PlatformPaths paths, MinecraftVersion expectedVersion) throws IOException {
        require(paths, expectedVersion);
        Path pending = boundary.resolve(resolvedPaths().cacheRoot().resolve("migrations").resolve(expectedVersion.value()).resolve("pending.json"));
        if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
            throw new RecoveryRequiredException(expectedVersion, pending);
        }
    }

    public MinecraftVersion version() {
        return version;
    }

    public CachePathBoundary boundary() {
        return boundary;
    }

    public PlatformPaths resolvedPaths() {
        return boundary.resolvedPaths();
    }

    public boolean isWrite() {
        return write;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        if (owner != Thread.currentThread()) {
            throw new IllegalStateException("Operation lease must be closed by its owner");
        }
        closed = true;
        lock.close();
    }
}
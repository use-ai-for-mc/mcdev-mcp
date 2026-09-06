package dev.mcdevmcp.storage.h2;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

public final class DatabaseLock implements AutoCloseable {
    private static final ConcurrentHashMap<Path, DatabaseLockState> LOCKS = new ConcurrentHashMap<>();
    private static final Duration RETRY_DELAY = Duration.ofMillis(25);
    private static final Duration MAX_MILLISECONDS = Duration.ofMillis(Long.MAX_VALUE);

    private final Lock localLock;
    private final DatabaseLockState state;
    private final boolean shared;
    private final FileChannel channel;
    private final FileLock fileLock;

    private DatabaseLock(Lock localLock, DatabaseLockState state, boolean shared, FileChannel channel, FileLock fileLock) {
        this.localLock = localLock;
        this.state = state;
        this.shared = shared;
        this.channel = channel;
        this.fileLock = fileLock;
    }

    public static DatabaseLock read(Path database, Duration timeout) throws IOException {
        return acquire(database, timeout, true);
    }

    public static DatabaseLock write(Path database, Duration timeout) throws IOException {
        return acquire(database, timeout, false);
    }

    private static DatabaseLock acquire(Path database, Duration timeout, boolean shared) throws IOException {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("lock timeout must not be negative");
        }
        Path normalizedDatabase = database.toAbsolutePath().normalize();
        Path lockPath = normalizedDatabase.resolveSibling(normalizedDatabase.getFileName() + ".lock");
        Files.createDirectories(lockPath.getParent());
        DatabaseLockState state = LOCKS.computeIfAbsent(lockPath, ignored -> new DatabaseLockState());
        Lock localLock = shared ? state.lock.readLock() : state.lock.writeLock();
        DatabaseLockDeadline deadline = DatabaseLockDeadline.after(timeout);
        try {
            if (!localLock.tryLock(deadline.remainingNanos(), TimeUnit.NANOSECONDS)) {
                throw timeoutFailure(shared, timeout);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while acquiring " + mode(shared) + " database lock", exception);
        }
        try {
            if (shared) {
                acquireSharedLock(state, lockPath, deadline, timeout);
                return new DatabaseLock(localLock, state, true, null, null);
            }
            FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            try {
                FileLock fileLock = acquireFileLock(channel, false, deadline, timeout);
                return new DatabaseLock(localLock, state, false, channel, fileLock);
            } catch (IOException | RuntimeException exception) {
                try {
                    channel.close();
                } catch (IOException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
                throw exception;
            }
        } catch (IOException | RuntimeException exception) {
            localLock.unlock();
            throw exception;
        }
    }

    private static void acquireSharedLock(DatabaseLockState state, Path lockPath, DatabaseLockDeadline deadline, Duration timeout) throws IOException {
        try {
            if (!state.sharedGuard.tryLock(deadline.remainingNanos(), TimeUnit.NANOSECONDS)) {
                throw timeoutFailure(true, timeout);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while acquiring shared database lock", exception);
        }
        try {
            if (state.sharedReferences++ > 0) {
                return;
            }
            try {
                state.sharedChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
                state.sharedFileLock = acquireFileLock(state.sharedChannel, true, deadline, timeout);
            } catch (IOException | RuntimeException exception) {
                state.sharedReferences = 0;
                if (state.sharedChannel != null) {
                    try {
                        state.sharedChannel.close();
                    } catch (IOException closeFailure) {
                        exception.addSuppressed(closeFailure);
                    }
                }
                state.sharedChannel = null;
                throw exception;
            }
        } finally {
            state.sharedGuard.unlock();
        }
    }

    private static FileLock acquireFileLock(FileChannel channel, boolean shared, DatabaseLockDeadline deadline, Duration timeout) throws IOException {
        while (true) {
            try {
                FileLock lock = channel.tryLock(0, Long.MAX_VALUE, shared);
                if (lock != null) {
                    return lock;
                }
            } catch (OverlappingFileLockException ignored) {
                // A competing process may release its lock before this timeout expires.
            }
            long remaining = deadline.remainingNanos();
            if (remaining <= 0) {
                throw timeoutFailure(shared, timeout);
            }
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(RETRY_DELAY.toNanos(), remaining));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while acquiring " + mode(shared) + " database lock", exception);
            }
        }
    }

    private static IOException timeoutFailure(boolean shared, Duration timeout) {
        return new IOException("Timed out acquiring " + mode(shared) + " database lock after " + format(timeout) + "; close active queries and retry.");
    }

    private static String mode(boolean shared) {
        return shared ? "shared" : "exclusive";
    }

    private static String format(Duration duration) {
        if (duration.getNano() == 0) {
            long seconds = duration.toSeconds();
            return seconds + (seconds == 1 ? " second" : " seconds");
        }
        if (duration.compareTo(MAX_MILLISECONDS) > 0) {
            return duration.toString();
        }
        return duration.toMillis() + " milliseconds";
    }

    public boolean isHeld() {
        state.sharedGuard.lock();
        try {
            return shared ? state.sharedFileLock != null && state.sharedFileLock.isValid() : fileLock.isValid();
        } finally {
            state.sharedGuard.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            if (shared) {
                releaseSharedLock();
            }
            else {
                try {
                    fileLock.release();
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
            }
        } catch (IOException exception) {
            failure = exception;
        } finally {
            localLock.unlock();
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void releaseSharedLock() throws IOException {
        state.sharedGuard.lock();
        try {
            if (--state.sharedReferences != 0) {
                return;
            }
            IOException failure = null;
            try {
                state.sharedFileLock.release();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                state.sharedChannel.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                }
                else {
                    failure.addSuppressed(exception);
                }
            } finally {
                state.sharedFileLock = null;
                state.sharedChannel = null;
            }
            if (failure != null) {
                throw failure;
            }
        } finally {
            state.sharedGuard.unlock();
        }
    }
}
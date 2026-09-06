package dev.mcdevmcp.storage.h2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseLockTest {
    @TempDir
    Path temporaryDirectory;

    private static DatabaseLock exclusiveLock(ReentrantLock localLock, FileChannel channel, FileLock fileLock) throws Exception {
        var constructor = DatabaseLock.class.getDeclaredConstructor(java.util.concurrent.locks.Lock.class, DatabaseLockState.class, boolean.class, FileChannel.class, FileLock.class);
        constructor.setAccessible(true);
        return constructor.newInstance(localLock, new DatabaseLockState(), false, channel, fileLock);
    }

    @Test
    void zeroTimeoutMakesOneImmediateUncontendedAttempt() throws Exception {
        Path database = temporaryDirectory.resolve("zero.mv.db");

        try (var writer = DatabaseLock.write(database, Duration.ZERO)) {
            assertTrue(writer.isHeld());
        }
        try (var reader = DatabaseLock.read(database, Duration.ZERO)) {
            assertTrue(reader.isHeld());
        }
    }

    @Test
    void hugeTimeoutDoesNotOverflowDeadlineConversion() throws Exception {
        Path database = temporaryDirectory.resolve("huge.mv.db");
        Duration timeout = Duration.ofSeconds(Long.MAX_VALUE, 999_999_999);

        try (var writer = DatabaseLock.write(database, timeout)) {
            assertTrue(writer.isHeld());
        }
    }

    @Test
    void exclusiveCloseClosesTheChannelWhenFileLockReleaseFails() throws Exception {
        var localLock = new ReentrantLock();
        localLock.lock();
        try (FileChannel channel = FileChannel.open(temporaryDirectory.resolve("symbols.mv.db.lock"), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            IOException releaseFailure = new IOException("release failed");
            DatabaseLock lock = exclusiveLock(localLock, channel, new FailingFileLock(channel, releaseFailure));

            InvocationTargetException invocation = assertThrows(InvocationTargetException.class, () -> DatabaseLock.class.getMethod("close").invoke(lock));

            assertSame(releaseFailure, invocation.getCause());
            assertFalse(channel.isOpen());
            assertFalse(localLock.isLocked());
        }
    }

    private static final class FailingFileLock extends FileLock {
        private final IOException failure;

        FailingFileLock(FileChannel channel, IOException failure) {
            super(channel, 0, Long.MAX_VALUE, false);
            this.failure = failure;
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        public void release() throws IOException {
            throw failure;
        }
    }
}

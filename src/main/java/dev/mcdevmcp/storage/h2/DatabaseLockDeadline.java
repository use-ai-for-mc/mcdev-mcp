package dev.mcdevmcp.storage.h2;

import java.time.Duration;

final class DatabaseLockDeadline {
    private static final Duration MAX_NANOSECONDS = Duration.ofNanos(Long.MAX_VALUE);

    private final long startedAt;
    private final long timeoutNanos;

    private DatabaseLockDeadline(long startedAt, long timeoutNanos) {
        this.startedAt = startedAt;
        this.timeoutNanos = timeoutNanos;
    }

    static DatabaseLockDeadline after(Duration timeout) {
        long timeoutNanos = timeout.compareTo(MAX_NANOSECONDS) >= 0 ? Long.MAX_VALUE : timeout.toNanos();
        return new DatabaseLockDeadline(System.nanoTime(), timeoutNanos);
    }

    long remainingNanos() {
        long elapsed = System.nanoTime() - startedAt;
        if (elapsed <= 0) {
            return timeoutNanos;
        }
        return elapsed >= timeoutNanos ? 0 : timeoutNanos - elapsed;
    }
}
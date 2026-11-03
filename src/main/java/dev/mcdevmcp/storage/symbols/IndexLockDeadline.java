package dev.mcdevmcp.storage.symbols;

import java.time.Duration;

value class IndexLockDeadline {
    private static final Duration MAX_NANOSECONDS = Duration.ofNanos(Long.MAX_VALUE);

    private final long startedAt;
    private final long timeoutNanos;

    private IndexLockDeadline(long startedAt, long timeoutNanos) {
        this.startedAt = startedAt;
        this.timeoutNanos = timeoutNanos;
    }

    static IndexLockDeadline after(Duration timeout) {
        long timeoutNanos = timeout.compareTo(MAX_NANOSECONDS) >= 0 ? Long.MAX_VALUE : timeout.toNanos();
        return new IndexLockDeadline(System.nanoTime(), timeoutNanos);
    }

    long remainingNanos() {
        long elapsed = System.nanoTime() - startedAt;
        if (elapsed <= 0) {
            return timeoutNanos;
        }
        return elapsed >= timeoutNanos ? 0 : timeoutNanos - elapsed;
    }
}
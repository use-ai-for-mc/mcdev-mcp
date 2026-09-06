package dev.mcdevmcp.storage.bundle;

import java.time.Duration;

final class BundleLockDeadline {
    private static final Duration MAX_NANOSECONDS = Duration.ofNanos(Long.MAX_VALUE);
    private final long startedAt;
    private final long timeoutNanos;

    private BundleLockDeadline(long startedAt, long timeoutNanos) {
        this.startedAt = startedAt;
        this.timeoutNanos = timeoutNanos;
    }

    static BundleLockDeadline after(Duration timeout) {
        long nanos = timeout.compareTo(MAX_NANOSECONDS) >= 0 ? Long.MAX_VALUE : timeout.toNanos();
        return new BundleLockDeadline(System.nanoTime(), nanos);
    }

    long remainingNanos() {
        long elapsed = System.nanoTime() - startedAt;
        if (elapsed <= 0) {
            return timeoutNanos;
        }
        return elapsed >= timeoutNanos ? 0 : timeoutNanos - elapsed;
    }
}
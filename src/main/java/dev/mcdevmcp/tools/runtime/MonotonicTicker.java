package dev.mcdevmcp.tools.runtime;

@FunctionalInterface
interface MonotonicTicker {
    long readNanos();

    static MonotonicTicker system() {
        return System::nanoTime;
    }
}

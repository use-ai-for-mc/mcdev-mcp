package dev.mcdevmcp.tools.runtime;

@FunctionalInterface
interface MonotonicTicker {
    static MonotonicTicker system() {
        return System::nanoTime;
    }

    long readNanos();
}
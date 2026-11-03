package dev.mcdevmcp.support;

@FunctionalInterface
public interface ProgressSink {
    void report(String stage, int percent, String message);
}

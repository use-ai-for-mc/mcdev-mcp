package dev.mcdevmcp.support;

@FunctionalInterface
@SuppressWarnings("unused")
public interface ProgressSink {
    void report(String stage, int percent, String message);
}

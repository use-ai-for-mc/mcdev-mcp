package dev.mcdevmcp.mcp.tool.api;

@FunctionalInterface
public interface ToolCancellation {
    static ToolCancellation none() {
        return () -> false;
    }

    boolean isCancelled();

    default void throwIfCancelled() throws InterruptedException {
        if (isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Operation cancelled");
        }
    }
}
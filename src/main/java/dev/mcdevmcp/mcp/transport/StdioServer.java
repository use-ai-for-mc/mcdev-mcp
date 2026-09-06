package dev.mcdevmcp.mcp.transport;

import io.modelcontextprotocol.server.McpAsyncServer;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class StdioServer implements AutoCloseable {
    private static final Duration EXECUTOR_STOP_TIMEOUT = Duration.ofSeconds(5);

    private final McpAsyncServer server;
    private final ExecutorService blockingExecutor;
    private final CountDownLatch inputClosed;
    private final AutoCloseable ownedRuntime;
    private final AtomicBoolean closed = new AtomicBoolean();

    StdioServer(McpAsyncServer server, ExecutorService blockingExecutor, CountDownLatch inputClosed, AutoCloseable ownedRuntime) {
        this.server = server;
        this.blockingExecutor = blockingExecutor;
        this.inputClosed = inputClosed;
        this.ownedRuntime = ownedRuntime;
    }

    private static Throwable close(AutoCloseable closeable, Throwable failure) {
        try {
            closeable.close();
        } catch (Throwable exception) {
            if (failure == null) {
                return exception;
            }
            failure.addSuppressed(exception);
        }
        return failure;
    }

    private static Throwable closeExecutor(ExecutorService executor, Throwable failure) {
        executor.shutdown();
        boolean interrupted = false;
        try {
            if (!executor.awaitTermination(EXECUTOR_STOP_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(EXECUTOR_STOP_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS)) {
                    failure = addFailure(failure, new IllegalStateException("MCP blocking executor did not stop"));
                }
            }
        } catch (InterruptedException exception) {
            interrupted = true;
            executor.shutdownNow();
            failure = addFailure(failure, new IllegalStateException("Interrupted while stopping MCP blocking executor", exception));
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return failure;
    }

    private static Throwable addFailure(Throwable failure, Throwable closeFailure) {
        if (failure == null) {
            return closeFailure;
        }
        failure.addSuppressed(closeFailure);
        return failure;
    }

    public void awaitInputClosed() throws InterruptedException {
        inputClosed.await();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Throwable failure = null;
        try {
            server.close();
        } catch (Throwable exception) {
            failure = exception;
        }
        failure = close(ownedRuntime, failure);
        failure = closeExecutor(blockingExecutor, failure);
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure != null) {
            throw new IllegalStateException("Unable to close MCP server", failure);
        }
    }
}
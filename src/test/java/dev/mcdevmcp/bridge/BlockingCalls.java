package dev.mcdevmcp.bridge;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs a blocking call on its own thread so a test can observe it while it waits and
 * cancel it the way a cancelled tool call does: by interrupting it.
 */
public final class BlockingCalls {
    private static final Duration PATIENCE = Duration.ofSeconds(5);

    private BlockingCalls() {
    }

    public static <T> Call<T> start(Callable<T> work) {
        FutureTask<T> task = new FutureTask<>(work);
        Thread thread = Thread.ofVirtual().name("blocking-test-call").start(task);
        return new Call<>(task, thread);
    }

    public static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                fail("timed out waiting for blocking work");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
    }

    /**
     * Waits for a pending opening like the production connector: an interrupted wait
     * gives up and closes a client that finishes opening afterwards.
     */
    static BridgeClient opened(CompletableFuture<BridgeClient> opening) throws InterruptedException {
        try {
            return opening.get();
        } catch (InterruptedException exception) {
            opening.thenAccept(BridgeClient::close);
            throw exception;
        } catch (ExecutionException exception) {
            throw new IllegalStateException(exception.getCause());
        }
    }

    public record Call<T>(FutureTask<T> task, Thread thread) {
        public T get() throws Exception {
            return task.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
        }

        public Throwable failure() throws Exception {
            try {
                task.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
            } catch (ExecutionException exception) {
                return exception.getCause();
            }
            return fail("call completed without failing");
        }

        public boolean cancel() {
            return task.cancel(true);
        }

        public void awaitBlocked() throws InterruptedException {
            awaitUntil(() -> thread.getState() == Thread.State.WAITING || thread.getState() == Thread.State.TIMED_WAITING);
        }

        public void awaitStopped() throws InterruptedException {
            if (!thread.join(PATIENCE)) {
                fail("blocking call did not stop");
            }
        }
    }
}

package dev.mcdevmcp.mcp.tool;

import dev.mcdevmcp.mcp.tool.api.ToolHandler;
import dev.mcdevmcp.mcp.tool.api.BlockingToolHandler;
import dev.mcdevmcp.mcp.tool.api.ToolHandlers;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.support.Cancellation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolHandlersTest {
    @Test
    void blockingHandlerRunsOnAVirtualThreadAndCancellationInterruptsIt() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var virtualThread = new AtomicBoolean();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ToolHandler<TestEmptyArguments> handler = ToolHandlers.blocking(executor, (BlockingToolHandler<TestEmptyArguments>) (_, _) -> {
                virtualThread.set(Thread.currentThread().isVirtual());
                started.countDown();
                try {
                    Thread.sleep(Duration.ofMinutes(1));
                    return ToolResult.text("unexpected");
                } catch (InterruptedException exception) {
                    interrupted.countDown();
                    throw exception;
                }
            });

            var future = handler.handle(new TestEmptyArguments(), Cancellation.none()).toCompletableFuture();
            assertTrue(started.await(5, TimeUnit.SECONDS), "blocking handler did not start");
            assertTrue(future.cancel(true), "blocking handler future was not cancelled");
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "cancellation did not interrupt the virtual thread");
            assertTrue(virtualThread.get(), "blocking handler did not run on a virtual thread");
        }
    }
}

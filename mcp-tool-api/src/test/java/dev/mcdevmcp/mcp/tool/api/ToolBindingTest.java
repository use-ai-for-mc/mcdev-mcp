package dev.mcdevmcp.mcp.tool.api;

import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ToolBindingTest {
    private static final ToolInput<BindingArguments> INPUT = ToolInput.of(BindingArguments.class, RecordInputSchemaFactory.standard());
    private static final ToolOutput<BindingArguments> OUTPUT = ToolOutput.of(BindingArguments.class, JsonValueSchema.of(Map.of("type", "object", "properties", Map.of("value", Map.of("type", "string")))));

    @Test
    void carriesOutputMetadataThroughTypedAndBlockingBindings() {
        var asynchronous = ToolBinding.output(INPUT, OUTPUT, (_, _) -> ToolHandlers.completed(ToolResult.text("ok")));
        assertEquals(OUTPUT, asynchronous.output().orElseThrow());
        assertSame(OUTPUT, asynchronous.declaredOutput());

        var blocking = ToolBinding.blocking(INPUT, OUTPUT, (_, _) -> ToolResult.text("ok"));
        assertEquals(OUTPUT, blocking.output().orElseThrow());
        assertSame(OUTPUT, blocking.declaredOutput());
    }

    @Test
    void blockingExecutorPreservesOutputMetadata() {
        var binding = ToolBinding.blocking(INPUT, OUTPUT, (_, _) -> ToolResult.text("ok"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            assertSame(OUTPUT, binding.withBlockingExecutor(executor).output().orElseThrow());
        }
    }

    @Test
    void outputBindingPreservesStructuredResultAndValueIdentity() {
        BindingArguments value = new BindingArguments("structured");
        StructuredToolResult<BindingArguments> expected = ToolResult.structured(value, "structured");
        ToolOutputBinding<BindingArguments, BindingArguments> binding = ToolBinding.output(INPUT, OUTPUT, (_, _) -> ToolHandlers.completed(expected));

        ToolResult<?> actual = binding.invoke(McpJsonDefaults.getMapper(), Map.of("value", "typed"), ToolCancellation.none()).toCompletableFuture().resultNow();

        assertSame(expected, actual);
        StructuredToolResult<?> structured = assertInstanceOf(StructuredToolResult.class, actual);
        assertSame(value, structured.structuredContent());
        assertSame(OUTPUT, binding.output().orElseThrow());
    }

    @Test
    void outputBindingPreservesAsynchronousFailure() {
        CompletionStage<ToolResult<BindingArguments>> failed = CompletableFuture.failedFuture(new IllegalStateException("output async failure"));
        ToolOutputBinding<BindingArguments, BindingArguments> binding = ToolBinding.output(INPUT, OUTPUT, (_, _) -> failed);

        CompletionException exception = assertThrows(CompletionException.class, () -> binding.invoke(McpJsonDefaults.getMapper(), Map.of("value", "typed"), ToolCancellation.none()).toCompletableFuture().join());

        assertEquals("output async failure", exception.getCause().getMessage());
    }

    @Test
    void blockingOutputBindingPreservesResultAndMetadataOnSuccess() {
        ToolResult<BindingArguments> expected = ToolResult.text("blocking");
        ToolOutputBinding<BindingArguments, BindingArguments> binding = ToolBinding.blocking(INPUT, OUTPUT, (_, _) -> expected);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ToolOutputBinding<BindingArguments, BindingArguments> adapted = binding.withBlockingExecutor(executor);
            ToolResult<BindingArguments> actual = adapted.invoke(McpJsonDefaults.getMapper(), Map.of("value", "typed"), ToolCancellation.none()).toCompletableFuture().join();

            assertSame(expected, actual);
            assertSame(INPUT, adapted.input());
            assertSame(OUTPUT, adapted.declaredOutput());
        }
    }

    @Test
    void blockingOutputBindingPreservesTheExactThrownExceptionCause() {
        IllegalStateException expected = new IllegalStateException("blocking output failure");
        ToolOutputBinding<BindingArguments, BindingArguments> binding = ToolBinding.blocking(INPUT, OUTPUT, (_, _) -> {
            throw expected;
        });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletionException exception = assertThrows(CompletionException.class, () -> binding.withBlockingExecutor(executor).invoke(McpJsonDefaults.getMapper(), Map.of("value", "typed"), ToolCancellation.none()).toCompletableFuture().join());

            assertSame(expected, exception.getCause());
        }
    }

    @Test
    void decodesTheCompleteMapBeforeInvokingTheTypedHandler() {
        var received = new AtomicReference<BindingArguments>();
        var binding = ToolBinding.content(INPUT, (arguments, _) -> {
            received.set(arguments);
            return ToolHandlers.completed(ToolResult.text("ok"));
        });

        ToolResult<?> result = binding.invoke(McpJsonDefaults.getMapper(), Map.of("value", "typed"), ToolCancellation.none()).toCompletableFuture().resultNow();

        assertEquals(new BindingArguments("typed"), received.get());
        assertFalse(result.isError());
    }

    @Test
    void propagatesSynchronousTypedDecodeFailureBeforeCallingTheHandler() {
        var handlerCalled = new AtomicBoolean();
        var binding = ToolBinding.content(INPUT, (BindingArguments _, ToolCancellation _) -> {
            handlerCalled.set(true);
            return ToolHandlers.completed(ToolResult.text("unexpected"));
        });

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> binding.invoke(McpJsonDefaults.getMapper(), Map.of("value", 7), ToolCancellation.none()));

        assertNotNull(exception.getMessage());
        assertFalse(handlerCalled.get());
    }

    @Test
    void preservesAsynchronousHandlerFailure() {
        var binding = ToolBinding.content(INPUT, (_, _) -> CompletableFuture.failedFuture(new IllegalStateException("async failure")));

        CompletionException exception = assertThrows(CompletionException.class, () -> binding.invoke(McpJsonDefaults.getMapper(), Map.of("value", "typed"), ToolCancellation.none()).toCompletableFuture().join());

        assertEquals("async failure", exception.getCause().getMessage());
    }

    @Test
    void rejectsANullBlockingExecutorForATypedHandler() {
        var binding = ToolBinding.content(INPUT, (_, _) -> ToolHandlers.completed(ToolResult.text("ok")));

        assertThrows(NullPointerException.class, () -> binding.withBlockingExecutor(null));
    }

    @Test
    void rejectsANullTypedHandler() {
        assertThrows(NullPointerException.class, () -> ToolBinding.content(INPUT, null));
    }

    @Test
    void returnsAFailedFutureWhenTheBlockingExecutorRejectsSubmission() {
        var binding = ToolBinding.blocking(INPUT, (_, _) -> ToolResult.text("unexpected"));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.shutdown();

            CompletionException exception = assertThrows(CompletionException.class, () -> binding.withBlockingExecutor(executor).invoke(McpJsonDefaults.getMapper(), Map.of("value", "typed"), ToolCancellation.none()).toCompletableFuture().join());

            assertInstanceOf(RejectedExecutionException.class, exception.getCause());
        }
    }

    @Test
    void outputBindingReturnsAFailedFutureWhenItsBlockingExecutorRejectsSubmission() {
        var binding = ToolBinding.blocking(INPUT, OUTPUT, (_, _) -> ToolResult.text("unexpected"));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.shutdown();

            CompletionException exception = assertThrows(CompletionException.class, () -> binding.withBlockingExecutor(executor).invoke(McpJsonDefaults.getMapper(), Map.of("value", "typed"), ToolCancellation.none()).toCompletableFuture().join());

            assertInstanceOf(RejectedExecutionException.class, exception.getCause());
        }
    }

    @Test
    void outputBindingRunsOnVirtualThreadAndCancellationInterruptsIt() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var virtualThread = new AtomicBoolean();
        var binding = ToolBinding.blocking(INPUT, OUTPUT, (_, _) -> {
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

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ToolOutputBinding<BindingArguments, BindingArguments> adapted = binding.withBlockingExecutor(executor);
            assertSame(INPUT, adapted.input());
            assertSame(OUTPUT, adapted.output().orElseThrow());
            var future = adapted.invoke(McpJsonDefaults.getMapper(), Map.of("value", "typed"), ToolCancellation.none()).toCompletableFuture();

            assertTrue(started.await(5, TimeUnit.SECONDS), "blocking output binding did not start");
            assertTrue(future.cancel(true), "blocking output binding future was not cancelled");
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "output binding cancellation did not interrupt the virtual thread");
            assertTrue(virtualThread.get(), "blocking output binding did not run on a virtual thread");
        }
    }

    @Test
    void blockingBindingRunsOnVirtualThreadAndFutureCancellationInterruptsIt() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var virtualThread = new AtomicBoolean();
        var binding = ToolBinding.blocking(INPUT, (_, _) -> {
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

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = binding.withBlockingExecutor(executor).invoke(McpJsonDefaults.getMapper(), Map.of("value", "typed"), ToolCancellation.none()).toCompletableFuture();

            assertTrue(started.await(5, TimeUnit.SECONDS), "blocking binding did not start");
            assertTrue(future.cancel(true), "blocking binding future was not cancelled");
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "cancellation did not interrupt the virtual thread");
            assertTrue(virtualThread.get(), "blocking binding did not run on a virtual thread");
        }
    }
}

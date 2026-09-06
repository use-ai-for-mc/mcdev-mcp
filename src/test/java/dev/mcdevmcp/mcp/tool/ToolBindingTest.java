package dev.mcdevmcp.mcp.tool;

import dev.mcdevmcp.mcp.tool.api.RecordInputSchemaFactory;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolHandlers;
import dev.mcdevmcp.mcp.tool.api.ToolInput;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.mcp.transport.SdkJsonMode;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ToolBindingTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();
    private static final ToolInput<BindingArguments> INPUT = ToolInput.of(BindingArguments.class, RecordInputSchemaFactory.standard());
    private static final Map<String, Object> VALID_ARGUMENTS = Map.of("uri", "https://example.test/tool", "timeoutMs", 1250L, "startedAt", "2026-07-10T12:34:56Z", "mode", "SAFE");

    @Test
    void decodesTheWholeArgumentMapOnceIntoTheTypedInput() {
        var mapper = new CountingMcpJsonMapper(MAPPER);
        var received = new CompletableFuture<BindingArguments>();
        var binding = ToolBinding.content(INPUT, (domain, _) -> {
            received.complete(domain);
            return ToolHandlers.completed(ToolResult.text("ok"));
        });

        var result = binding.invoke(mapper, VALID_ARGUMENTS, Cancellation.none()).toCompletableFuture().resultNow();
        var domain = received.resultNow();

        assertFalse(result.isError());
        assertEquals(1, mapper.convertValueCalls());
        assertEquals("https://example.test/tool", domain.uri());
        assertEquals(1250L, domain.timeoutMs());
        assertEquals(Instant.parse("2026-07-10T12:34:56Z"), domain.startedAt());
        assertEquals(SdkJsonMode.SAFE, domain.mode());
    }

    @Test
    void propagatesSynchronousTypedDecodeFailureBeforeCallingTheHandler() {
        var handlerCalled = new CompletableFuture<Void>();
        var mapper = new CountingMcpJsonMapper(MAPPER);
        var binding = ToolBinding.content(INPUT, (_, _) -> {
            handlerCalled.complete(null);
            return ToolHandlers.completed(ToolResult.text("unexpected"));
        });

        var exception = assertThrows(IllegalArgumentException.class, () -> binding.invoke(mapper, Map.of("timeoutMs", "not-a-number"), Cancellation.none()));

        assertNotNull(exception.getMessage());
        assertEquals(0, mapper.convertValueCalls());
        assertFalse(handlerCalled.isDone());
    }

    @Test
    void preservesAsynchronousHandlerFailure() {
        var binding = ToolBinding.content(INPUT, (_, _) -> CompletableFuture.failedFuture(new IllegalStateException("async failure")));

        var exception = assertThrows(CompletionException.class, () -> binding.invoke(MAPPER, VALID_ARGUMENTS, Cancellation.none()).toCompletableFuture().join());

        assertEquals("async failure", exception.getCause().getMessage());
    }

    @Test
    void blockingBindingRunsOnItsAssignedVirtualExecutorAndCancellationInterruptsIt() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var virtualThread = new AtomicBoolean();
        var binding = ToolBinding.blocking(INPUT, (_, _) -> {
            virtualThread.set(Thread.currentThread().isVirtual());
            started.countDown();
            try {
                Thread.sleep(java.time.Duration.ofMinutes(1));
                return ToolResult.text("unexpected");
            } catch (InterruptedException exception) {
                interrupted.countDown();
                throw exception;
            }
        });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = binding.withBlockingExecutor(executor).invoke(MAPPER, VALID_ARGUMENTS, Cancellation.none()).toCompletableFuture();

            assertTrue(started.await(5, TimeUnit.SECONDS), "blocking binding did not start");
            assertTrue(future.cancel(true), "blocking binding future was not cancelled");
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "cancellation did not interrupt the virtual thread");
            assertTrue(virtualThread.get(), "blocking binding did not run on a virtual thread");
        }
    }

    private record BindingArguments(String uri, long timeoutMs, Instant startedAt, SdkJsonMode mode) {
    }
}

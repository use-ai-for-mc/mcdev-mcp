package dev.mcdevmcp.mcp.tool.api;

import java.util.Objects;
import java.util.concurrent.*;

public final class ToolHandlers {
    private ToolHandlers() {
    }

    public static <R extends ToolResult<?>> CompletionStage<R> completed(R result) {
        return CompletableFuture.completedFuture(Objects.requireNonNull(result));
    }

    public static <A> ToolHandler<A> blocking(ExecutorService executor, BlockingToolHandler<A> handler) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(handler, "handler");
        return (arguments, cancellation) -> submit(executor, () -> Objects.requireNonNull(handler.handle(arguments, cancellation), "Blocking tool handler result"));
    }

    public static <A, O> ToolOutputHandler<A, O> blockingOutput(ExecutorService executor, BlockingToolOutputHandler<A, O> handler) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(handler, "handler");
        return (arguments, cancellation) -> submit(executor, () -> Objects.requireNonNull(handler.handle(arguments, cancellation), "Blocking tool output handler result"));
    }

    @SuppressWarnings("preview")
    private static <T> CompletionStage<T> submit(ExecutorService executor, Callable<T> work) {
        var result = new CompletableFuture<T>();
        Future<?> task;
        try {
            task = executor.submit(() -> {
                try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.<T>anySuccessfulOrThrow(), config -> config.withName("mcp-tool-blocking"))) {
                    scope.fork(work);
                    result.complete(scope.join());
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                    result.completeExceptionally(cause);
                    if (cause instanceof Error error) {
                        throw error;
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    result.cancel(false);
                } catch (Throwable exception) {
                    result.completeExceptionally(exception);
                    if (exception instanceof Error error) {
                        throw error;
                    }
                }
            });
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        result.whenComplete((_, _) -> {
            if (result.isCancelled()) {
                task.cancel(true);
            }
        });
        return result;
    }
}
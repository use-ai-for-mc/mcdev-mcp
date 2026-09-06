package dev.mcdevmcp.mcp.tool.api;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public final class ToolHandlers {
    private ToolHandlers() {
    }

    public static <R extends ToolResult<?>> CompletionStage<R> completed(R result) {
        return CompletableFuture.completedFuture(Objects.requireNonNull(result));
    }

    public static <A> ToolHandler<A> blocking(ExecutorService executor, BlockingToolHandler<A> handler) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(handler, "handler");
        return (arguments, cancellation) -> {
            var result = new CompletableFuture<ContentToolResult<Void>>();
            Future<?> task;
            try {
                task = executor.submit(() -> {
                    try {
                        result.complete(Objects.requireNonNull(handler.handle(arguments, cancellation), "Blocking tool handler result"));
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
        };
    }

    @SuppressWarnings("overloads")
    public static <A, O> ToolOutputHandler<A, O> blocking(ExecutorService executor, BlockingToolOutputHandler<A, O> handler) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(handler, "handler");
        return (arguments, cancellation) -> {
            var result = new CompletableFuture<ToolResult<O>>();
            Future<?> task;
            try {
                task = executor.submit(() -> {
                    try {
                        result.complete(Objects.requireNonNull(handler.handle(arguments, cancellation), "Blocking tool output handler result"));
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
        };
    }
}

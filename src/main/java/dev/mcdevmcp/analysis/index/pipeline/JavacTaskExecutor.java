package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.index.IndexBuildException;
import dev.mcdevmcp.analysis.index.IndexRequest;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class JavacTaskExecutor {
    private static final Duration CANCELLATION_POLL = Duration.ofMillis(25);

    private JavacTaskExecutor() {
    }

    @SuppressWarnings("preview")
    static <T> T executeSingle(IndexRequest request, Callable<T> task) throws IndexBuildException, InterruptedException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(task, "task");
        request.cancellation().throwIfCancelled();
        try (var scope = StructuredTaskScope.open(new FirstCompleted<T>(), config -> config.withName("javac-index-single"))) {
            scope.fork(task);
            scope.fork(() -> {
                watchCancellation(request);
                return null;
            });
            return scope.join();
        } catch (ExecutionException exception) {
            throw mappedWorker(exception.getCause());
        }
    }

    // The coordinator advances tasks only when a complete admission slot is available.
    @SuppressWarnings("preview")
    static <T> void executeAll(IndexRequest request, int workerCount, Iterator<? extends Callable<T>> tasks, Consumer<T> resultConsumer) throws IndexBuildException, InterruptedException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(resultConsumer, "resultConsumer");
        if (workerCount < 1) {
            throw new IllegalArgumentException("Javac worker count must be at least 1");
        }
        request.cancellation().throwIfCancelled();
        try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.awaitAllSuccessfulOrThrow(), config -> config.withName("javac-index"))) {
            var finished = new AtomicBoolean();
            var abort = new AtomicBoolean();
            scope.fork(() -> {
                while (!finished.get() && !abort.get()) {
                    request.cancellation().throwIfCancelled();
                    Thread.sleep(CANCELLATION_POLL);
                }
                if (abort.get()) {
                    throw new InterruptedException("Javac index workers aborted");
                }
                return null;
            });
            Deque<CompletableFuture<T>> window = new ArrayDeque<>();
            Throwable failure = null;
            try {
                while (window.size() < workerCount && tasks.hasNext()) {
                    request.cancellation().throwIfCancelled();
                    window.addLast(fork(scope, tasks.next()));
                }
                while (!window.isEmpty()) {
                    T result = awaitHandoff(window.removeFirst(), request);
                    request.cancellation().throwIfCancelled();
                    resultConsumer.accept(result);
                    if (tasks.hasNext()) {
                        request.cancellation().throwIfCancelled();
                        window.addLast(fork(scope, tasks.next()));
                    }
                }
            } catch (Throwable exception) {
                abort.set(true);
                failure = exception;
            } finally {
                finished.set(true);
            }
            try {
                scope.join();
            } catch (ExecutionException exception) {
                if (failure == null) {
                    throw mappedWorker(exception.getCause());
                }
                failure.addSuppressed(exception);
            } catch (InterruptedException exception) {
                if (failure == null) {
                    throw exception;
                }
                Thread.currentThread().interrupt();
                failure.addSuppressed(exception);
            }
            if (failure != null) {
                rethrowCoordinator(failure);
            }
        }
    }

    @SuppressWarnings("preview")
    private static <T> CompletableFuture<T> fork(StructuredTaskScope<Object, Void, ExecutionException> scope, Callable<T> task) {
        var done = new CompletableFuture<T>();
        scope.fork(() -> {
            try {
                T value = task.call();
                done.complete(value);
                return value;
            } catch (Throwable exception) {
                done.completeExceptionally(exception);
                throw exception;
            }
        });
        return done;
    }

    private static <T> T awaitHandoff(CompletableFuture<T> done, IndexRequest request) throws IndexBuildException, InterruptedException {
        while (true) {
            request.cancellation().throwIfCancelled();
            try {
                return done.get(CANCELLATION_POLL.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException _) {
            } catch (ExecutionException exception) {
                throw mappedWorker(exception.getCause());
            } catch (CancellationException exception) {
                throw new InterruptedException("Javac source worker was cancelled");
            }
        }
    }

    private static void watchCancellation(IndexRequest request) throws InterruptedException {
        while (!request.cancellation().isCancelled() && !Thread.currentThread().isInterrupted()) {
            Thread.sleep(CANCELLATION_POLL);
        }
        throw new InterruptedException("Javac source worker was cancelled");
    }

    private static IndexBuildException mappedWorker(Throwable cause) throws IndexBuildException, InterruptedException {
        switch (cause) {
            case IndexBuildException buildException -> throw buildException;
            case InterruptedException interruptedException -> throw interruptedException;
            case Error error -> throw error;
            default -> throw new IndexBuildException("Javac source worker failed", cause);
        }
    }

    private static void rethrowCoordinator(Throwable failure) throws IndexBuildException, InterruptedException {
        switch (failure) {
            case IndexBuildException buildException -> throw buildException;
            case InterruptedException interruptedException -> throw interruptedException;
            case Error error -> throw error;
            case RuntimeException runtimeException -> throw runtimeException;
            default -> throw new IndexBuildException("Javac source worker failed", failure);
        }
    }

    @SuppressWarnings("preview")
    private static final class FirstCompleted<T> implements StructuredTaskScope.Joiner<T, T, ExecutionException> {
        private final AtomicReference<Outcome<T>> first = new AtomicReference<>();

        @Override
        public boolean onComplete(StructuredTaskScope.Subtask<T> subtask) {
            Outcome<T> outcome = switch (subtask.state()) {
                case SUCCESS -> new Outcome<>(subtask.get(), null);
                case FAILED -> new Outcome<>(null, subtask.exception());
                case UNAVAILABLE -> throw new IllegalArgumentException("Subtask is unavailable");
            };
            return first.compareAndSet(null, outcome);
        }

        @Override
        public T result() throws ExecutionException {
            var outcome = Objects.requireNonNull(first.get(), "No subtask completed");
            if (outcome.failure() != null) {
                throw new ExecutionException(outcome.failure());
            }
            return outcome.value();
        }

        @Override
        public T timeout() throws ExecutionException {
            throw new ExecutionException(new StructuredTaskScope.CancelledByTimeoutException());
        }

        private value record Outcome<T>(T value, Throwable failure) {
        }
    }
}
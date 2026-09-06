package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.index.IndexBuildException;
import dev.mcdevmcp.analysis.index.IndexRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Function;

final class JavacTaskExecutor {
    private static final long CANCELLATION_POLL_MILLIS = 25;

    private JavacTaskExecutor() {
    }

    static <T> T executeSingle(IndexRequest request, Callable<T> task) throws IndexBuildException, InterruptedException {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<T> future = executor.submit(task);
        try {
            T result = get(future, request);
            terminate(executor, List.of(future));
            return result;
        } catch (IndexBuildException | InterruptedException | RuntimeException | Error failure) {
            terminateSuppressing(executor, List.of(future), failure);
            throw failure;
        }
    }

    static <T, R> R executeAll(IndexRequest request, int workerCount, List<? extends Callable<T>> tasks, Function<List<T>, R> resultAssembler) throws IndexBuildException, InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        List<Future<T>> futures = new ArrayList<>();
        R result;
        try {
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(task));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                request.cancellation().throwIfCancelled();
                results.add(get(future, request));
            }
            result = resultAssembler.apply(results);
        } catch (IndexBuildException | InterruptedException | RuntimeException | Error failure) {
            terminateSuppressing(executor, futures, failure);
            throw failure;
        }
        terminate(executor, futures);
        return result;
    }

    private static <T> T get(Future<T> future, IndexRequest request) throws IndexBuildException, InterruptedException {
        while (true) {
            request.cancellation().throwIfCancelled();
            try {
                return future.get(CANCELLATION_POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof IndexBuildException buildException) {
                    throw buildException;
                }
                if (cause instanceof InterruptedException interruptedException) {
                    throw interruptedException;
                }
                throw new IndexBuildException("Javac source worker failed", cause);
            } catch (CancellationException exception) {
                throw new InterruptedException("Javac source worker was cancelled");
            }
        }
    }

    private static void terminate(ExecutorService executor, List<? extends Future<?>> futures) throws IndexBuildException, InterruptedException {
        futures.forEach(future -> future.cancel(true));
        executor.shutdownNow();
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
            throw new IndexBuildException("Javac index workers did not terminate");
        }
    }

    private static void terminateSuppressing(ExecutorService executor, List<? extends Future<?>> futures, Throwable failure) {
        try {
            terminate(executor, futures);
        } catch (IndexBuildException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        } catch (InterruptedException cleanupFailure) {
            Thread.currentThread().interrupt();
            failure.addSuppressed(cleanupFailure);
        }
    }
}
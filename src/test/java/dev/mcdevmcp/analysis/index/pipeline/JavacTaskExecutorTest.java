package dev.mcdevmcp.analysis.index.pipeline;

import dev.mcdevmcp.analysis.index.IndexBuildException;
import dev.mcdevmcp.analysis.index.IndexRequest;
import dev.mcdevmcp.support.Cancellation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class JavacTaskExecutorTest {
    @TempDir
    Path temporaryDirectory;

    private static Iterator<Callable<Integer>> admittedTasks(Deque<Callable<Integer>> tasks, AtomicInteger admitted) {
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return !tasks.isEmpty();
            }

            @Override
            public Callable<Integer> next() {
                Callable<Integer> task = tasks.removeFirst();
                admitted.incrementAndGet();
                return task;
            }
        };
    }

    private static Callable<Integer> blockingWorker(CountDownLatch started, CountDownLatch terminated) {
        return () -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
                return 0;
            } finally {
                terminated.countDown();
            }
        };
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for coordinated executor state");
    }

    private static void awaitInConsumer(CountDownLatch latch) {
        try {
            await(latch);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Consumer was interrupted", exception);
        }
    }

    private static void terminate(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Coordinator did not terminate");
    }

    @Test
    void completedLaterBatchCannotReleaseBlockedFirstBatchWindow() throws Exception {
        AtomicInteger admitted = new AtomicInteger();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondCompleted = new CountDownLatch(1);
        CountDownLatch thirdStarted = new CountDownLatch(1);
        CountDownLatch fourthCompleted = new CountDownLatch(1);
        Deque<Callable<Integer>> tasks = new ArrayDeque<>(List.of(() -> {
            firstStarted.countDown();
            await(releaseFirst);
            return 0;
        }, () -> {
            secondCompleted.countDown();
            return 1;
        }, () -> {
            thirdStarted.countDown();
            await(fourthCompleted);
            return 2;
        }, () -> {
            fourthCompleted.countDown();
            return 3;
        }));
        List<Integer> results = new ArrayList<>();
        ExecutorService coordinator = Executors.newSingleThreadExecutor();
        try {
            Future<?> execution = coordinator.submit(() -> {
                JavacTaskExecutor.executeAll(request(Cancellation.none()), 2, admittedTasks(tasks, admitted), results::add);
                return null;
            });
            await(firstStarted);
            await(secondCompleted);
            assertFalse(thirdStarted.await(200, TimeUnit.MILLISECONDS));
            assertEquals(2, admitted.get(), "Completed but unconsumed batches must retain admission slots");
            releaseFirst.countDown();
            execution.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(0, 1, 2, 3), results);
            assertEquals(4, admitted.get());
            assertTrue(tasks.isEmpty());
        } finally {
            releaseFirst.countDown();
            fourthCompleted.countDown();
            terminate(coordinator);
        }
    }

    @Test
    void consumerMustFinishBeforeItsWindowSlotCanBeReplaced() throws Exception {
        AtomicInteger admitted = new AtomicInteger();
        CountDownLatch consumerStarted = new CountDownLatch(1);
        CountDownLatch releaseConsumer = new CountDownLatch(1);
        CountDownLatch replacementStarted = new CountDownLatch(1);
        Deque<Callable<Integer>> tasks = new ArrayDeque<>(List.of(() -> 0, () -> {
            replacementStarted.countDown();
            return 1;
        }));
        List<Integer> results = new ArrayList<>();
        ExecutorService coordinator = Executors.newSingleThreadExecutor();
        try {
            Future<?> execution = coordinator.submit(() -> {
                JavacTaskExecutor.executeAll(request(Cancellation.none()), 1, admittedTasks(tasks, admitted), value -> {
                    if (value == 0) {
                        consumerStarted.countDown();
                        awaitInConsumer(releaseConsumer);
                    }
                    results.add(value);
                });
                return null;
            });
            await(consumerStarted);
            assertFalse(replacementStarted.await(200, TimeUnit.MILLISECONDS));
            assertEquals(1, admitted.get());
            releaseConsumer.countDown();
            execution.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(0, 1), results);
            assertEquals(2, admitted.get());
        } finally {
            releaseConsumer.countDown();
            terminate(coordinator);
        }
    }

    @Test
    void workerFailureStopsAdmissionAndTerminatesOtherAdmittedWorker() {
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondTerminated = new CountDownLatch(1);
        IndexBuildException failure = new IndexBuildException("deliberate worker failure");
        Deque<Callable<Integer>> tasks = new ArrayDeque<>(List.of(() -> {
            await(secondStarted);
            throw failure;
        }, blockingWorker(secondStarted, secondTerminated), () -> fail("Unadmitted worker ran")));

        IndexBuildException actual = assertThrows(IndexBuildException.class, () -> JavacTaskExecutor.executeAll(request(Cancellation.none()), 2, admittedTasks(tasks, new AtomicInteger()), _ -> fail("Failed batch was consumed")));

        assertSame(failure, actual);
        assertEquals(1, tasks.size());
        assertEquals(0, secondTerminated.getCount());
    }

    @Test
    void consumerFailureStopsAdmissionAndTerminatesOtherAdmittedWorker() {
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondTerminated = new CountDownLatch(1);
        IllegalStateException failure = new IllegalStateException("deliberate consumer failure");
        Deque<Callable<Integer>> tasks = new ArrayDeque<>(List.of(() -> {
            await(secondStarted);
            return 0;
        }, blockingWorker(secondStarted, secondTerminated), () -> fail("Unadmitted worker ran")));

        IllegalStateException actual = assertThrows(IllegalStateException.class, () -> JavacTaskExecutor.executeAll(request(Cancellation.none()), 2, admittedTasks(tasks, new AtomicInteger()), _ -> {
            throw failure;
        }));

        assertSame(failure, actual);
        assertEquals(1, tasks.size());
        assertEquals(0, secondTerminated.getCount());
    }

    @Test
    void cancellationWhileWaitingStopsAdmissionAndTerminatesWorkers() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch terminated = new CountDownLatch(2);
        Deque<Callable<Integer>> tasks = new ArrayDeque<>(List.of(blockingWorker(started, terminated), blockingWorker(started, terminated), () -> fail("Unadmitted worker ran")));
        ExecutorService coordinator = Executors.newSingleThreadExecutor();
        try {
            Future<?> execution = coordinator.submit(() -> {
                JavacTaskExecutor.executeAll(request(cancelled::get), 2, admittedTasks(tasks, new AtomicInteger()), _ -> fail("Cancelled batch was consumed"));
                return null;
            });
            await(started);
            cancelled.set(true);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> execution.get(5, TimeUnit.SECONDS));
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertEquals(1, tasks.size());
            assertEquals(0, terminated.getCount());
        } finally {
            cancelled.set(true);
            terminate(coordinator);
        }
    }

    @Test
    void cancellationDuringConsumptionPreventsReplacementAdmission() {
        AtomicBoolean cancelled = new AtomicBoolean();
        Deque<Callable<Integer>> tasks = new ArrayDeque<>(List.of(() -> 0, () -> fail("Unadmitted worker ran")));
        List<Integer> results = new ArrayList<>();

        assertThrows(InterruptedException.class, () -> JavacTaskExecutor.executeAll(request(cancelled::get), 1, admittedTasks(tasks, new AtomicInteger()), value -> {
            results.add(value);
            cancelled.set(true);
        }));

        assertEquals(List.of(0), results);
        assertEquals(1, tasks.size());
    }

    @Test
    void alreadyCancelledRequestDoesNotAdmitAnyTasks() {
        Deque<Callable<Integer>> tasks = new ArrayDeque<>(List.of(() -> fail("Cancelled worker ran")));
        assertThrows(InterruptedException.class, () -> JavacTaskExecutor.executeAll(request(() -> true), 1, admittedTasks(tasks, new AtomicInteger()), _ -> fail("Cancelled batch was consumed")));
        assertEquals(1, tasks.size());
    }

    @Test
    void workerCancellationBeforeReturningDoesNotDeliverItsResult() {
        AtomicBoolean cancelled = new AtomicBoolean();
        Deque<Callable<Integer>> tasks = new ArrayDeque<>(List.of(() -> {
            cancelled.set(true);
            return 0;
        }, () -> fail("Unadmitted worker ran")));

        assertThrows(InterruptedException.class, () -> JavacTaskExecutor.executeAll(request(cancelled::get), 1, admittedTasks(tasks, new AtomicInteger()), _ -> fail("Cancelled result was delivered")));
        assertEquals(1, tasks.size());
    }

    @Test
    void unexpectedWorkerExceptionRetainsItsCauseAndStopsAdmission() {
        IllegalStateException failure = new IllegalStateException("unexpected worker failure");
        Deque<Callable<Integer>> tasks = new ArrayDeque<>(List.of(() -> {
            throw failure;
        }, () -> fail("Unadmitted worker ran")));

        IndexBuildException actual = assertThrows(IndexBuildException.class, () -> JavacTaskExecutor.executeAll(request(Cancellation.none()), 1, admittedTasks(tasks, new AtomicInteger()), _ -> fail("Failed result was delivered")));

        assertSame(failure, actual.getCause());
        assertEquals("Javac source worker failed", actual.getMessage());
        assertEquals(1, tasks.size());
    }

    @Test
    void singleTaskCancellationInterruptsTheWorker() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch terminated = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        ExecutorService coordinator = Executors.newSingleThreadExecutor();
        try {
            Future<?> execution = coordinator.submit(() -> {
                JavacTaskExecutor.executeSingle(request(cancelled::get), blockingWorker(started, terminated));
                return null;
            });
            await(started);
            cancelled.set(true);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> execution.get(5, TimeUnit.SECONDS));
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertEquals(0, terminated.getCount());
        } finally {
            cancelled.set(true);
            terminate(coordinator);
        }
    }

    @Test
    void emptyTasksDoNotConsumeAndInvalidWorkerCountDoesNotDrainTasks() throws Exception {
        JavacTaskExecutor.executeAll(request(Cancellation.none()), 1, Collections.<Callable<Integer>>emptyIterator(), _ -> fail("Empty input was consumed"));
        Deque<Callable<Integer>> tasks = new ArrayDeque<>(List.of(() -> 0));
        assertThrows(IllegalArgumentException.class, () -> JavacTaskExecutor.executeAll(request(Cancellation.none()), 0, admittedTasks(tasks, new AtomicInteger()), _ -> fail("Invalid worker count was accepted")));
        assertEquals(1, tasks.size());
    }

    private IndexRequest request(Cancellation cancellation) {
        IndexRequest base = IndexerTestSupport.request(temporaryDirectory, temporaryDirectory.resolve("unused.jar"), temporaryDirectory.resolve("unused.jsonl"), 2);
        return new IndexRequest(base.minecraftVersion(), base.sourceRoots(), base.remappedJar(), base.classpath(), base.outputIndex(), base.threads(), base.progress(), cancellation);
    }
}
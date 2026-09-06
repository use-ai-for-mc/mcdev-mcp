package dev.mcdevmcp.mcp;

import dev.mcdevmcp.bridge.BridgeSession;
import dev.mcdevmcp.mcp.resource.ResourceCatalog;
import dev.mcdevmcp.mcp.tool.*;
import dev.mcdevmcp.mcp.tool.api.RecordInputSchemaFactory;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolHandlers;
import dev.mcdevmcp.mcp.tool.api.ToolInput;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.Cancellation;
import dev.mcdevmcp.tools.runtime.RuntimeContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class McpServerFactoryTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();

    private static Map<String, ToolBinding<?>> completeBindings(ToolBinding<?> snapshotBinding) {
        return CompleteToolBindings.including(MAPPER, snapshotBinding == null ? Map.of() : Map.of("mc_snapshot", snapshotBinding));
    }

    @Test
    void factoryAdaptsABlockingTypedBindingForItsToolCatalog() throws Exception {
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var virtualThread = new AtomicReference<Thread>();
        ToolInput<Object> input = declaredInput();
        var binding = ToolBinding.blocking(input, (_, _) -> {
            virtualThread.set(Thread.currentThread());
            started.countDown();
            try {
                Thread.sleep(java.time.Duration.ofMinutes(1));
                return ToolResult.text("unexpected");
            } catch (InterruptedException exception) {
                interrupted.countDown();
                throw exception;
            }
        });
        try (var factory = new McpServerFactory(new AppEnvironment(Map.of()), completeBindings(binding), MAPPER);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = factory.loadToolCatalog(executor).dispatch("mc_snapshot", Map.of(), Cancellation.none()).toCompletableFuture();

            assertTrue(started.await(5, TimeUnit.SECONDS), "factory did not start the blocking binding");
            assertTrue(future.cancel(true), "factory binding future was not cancelled");
            assertTrue(interrupted.await(5, TimeUnit.SECONDS), "factory binding cancellation did not interrupt execution");
            assertTrue(virtualThread.get().isVirtual());
        }
    }

    @Test
    @SuppressWarnings("try")
    void stdioServerOwnsTheRuntimeAndFactoryStartsOnlyOnce() {
        var runtimeCloses = new AtomicInteger();
        try (var factory = new McpServerFactory(new AppEnvironment(Map.of()), completeBindings(null), ResourceCatalog.withMapper(MAPPER), MAPPER, runtimeCloses::incrementAndGet);
             var server = factory.startStdio(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream());
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.close();
            server.close();
            assertThrows(IllegalStateException.class, () -> factory.loadServerDefinition(executor));
            assertThrows(IllegalStateException.class, () -> factory.startStdio(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()));
        }
        assertEquals(1, runtimeCloses.get());
    }

    @Test
    @SuppressWarnings("try")
    void factoryCloseIsIdempotentAndRejectsFurtherUse() {
        var closes = new AtomicInteger();
        try (var factory = new McpServerFactory(new AppEnvironment(Map.of()), completeBindings(null), ResourceCatalog.withMapper(MAPPER), MAPPER, closes::incrementAndGet);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            factory.close();
            factory.close();
            assertThrows(IllegalStateException.class, () -> factory.loadServerDefinition(executor));
            assertThrows(IllegalStateException.class, () -> factory.startStdio(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()));
        }
        assertEquals(1, closes.get());
    }

    @Test
    void factoryAndServerShareOneRuntimeClose() {
        var closes = new AtomicInteger();
        try (var factory = new McpServerFactory(new AppEnvironment(Map.of()), completeBindings(null), ResourceCatalog.withMapper(MAPPER), MAPPER, closes::incrementAndGet);
             var server = factory.startStdio(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())) {
            assertNotNull(server);
        }
        assertEquals(1, closes.get());
    }

    @Test
    void startupFailureClosesTheOwnedRuntime() {
        var runtimeCloses = new AtomicInteger();
        var unexpectedBinding = ToolBinding.content(ToolInput.of(TestEmptyArguments.class, RecordInputSchemaFactory.standard()), (_, _) -> ToolHandlers.completed(ToolResult.text("unused")));
        try (var factory = new McpServerFactory(new AppEnvironment(Map.of()), Map.of("not_in_metadata", unexpectedBinding), ResourceCatalog.withMapper(MAPPER), MAPPER, runtimeCloses::incrementAndGet)) {
            assertThrows(IllegalArgumentException.class, () -> factory.startStdio(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()));
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                assertThrows(IllegalStateException.class, () -> factory.loadServerDefinition(executor));
            }
        }
        assertEquals(1, runtimeCloses.get());
    }

    @Test
    void startupFailurePreservesTheOriginalFailureWhenRuntimeCloseThrowsAnError() {
        var unexpectedBinding = ToolBinding.content(ToolInput.of(TestEmptyArguments.class, RecordInputSchemaFactory.standard()), (_, _) -> ToolHandlers.completed(ToolResult.text("unused")));
        try (var factory = new McpServerFactory(new AppEnvironment(Map.of()), Map.of("not_in_metadata", unexpectedBinding), ResourceCatalog.withMapper(MAPPER), MAPPER, () -> {
            throw new AssertionError("runtime close failed");
        })) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> factory.startStdio(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()));

            assertEquals(1, failure.getSuppressed().length);
            assertEquals("runtime close failed", failure.getSuppressed()[0].getMessage());
        }
    }

    @Test
    void declarativeCompositionUsesRealTypedBindingsWithoutActivatingRuntime() {
        long pollThreadsBefore = pollThreadCount();
        McpServerFactory.DeclarativeComposition composition = McpServerFactory.declarativeComposition(new AppEnvironment(Map.of()), MAPPER);
        try (composition) {
            assertEquals(33, composition.definitions().size());
            assertTrue(composition.definitions().stream().allMatch(definition -> definition.binding() != null));
            assertTrue(composition.definitions().stream().allMatch(definition -> definition.input() == definition.binding().input()));
            assertFalse(composition.runtimeActivated(), "schema composition must not activate runtime providers");
            assertEquals(pollThreadsBefore, pollThreadCount(), "schema composition must not create polling threads");
        }
        assertEquals(pollThreadsBefore, pollThreadCount(), "closing an unactivated composition must not create polling threads");
    }

    @Test
    void lazyRuntimeBindingDecodesOnceActivatesOnInvocationAndHonorsClose() {
        var mapper = new CountingMcpJsonMapper(MAPPER);
        McpServerFactory.DeclarativeComposition composition = McpServerFactory.declarativeComposition(new AppEnvironment(Map.of()), mapper);
        ToolDefinition scriptLogs = composition.definitions().stream().filter(definition -> definition.name().equals("mc_script_logs")).findFirst().orElseThrow();
        assertFalse(composition.runtimeActivated());
        assertInstanceOf(dev.mcdevmcp.mcp.tool.api.ContentToolBinding.class, scriptLogs.binding(), "runtime declarations must retain their typed content binding");

        ToolResult<?> result = scriptLogs.binding().invoke(mapper, Map.of(), Cancellation.none()).toCompletableFuture().join();

        assertFalse(result.isError());
        assertEquals(1, mapper.convertValueCalls(), "lazy wrapper must not decode a typed input twice");
        assertTrue(composition.runtimeActivated());
        composition.close();
        assertThrows(IllegalStateException.class, () -> scriptLogs.binding().invoke(mapper, Map.of(), Cancellation.none()));
    }

    @Test
    void defaultServerRetainsTheComposedDefinitionsAcrossCatalogLoads() {
        try (var factory = new McpServerFactory(new AppEnvironment(Map.of()));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<ToolDefinition> first = factory.loadServerDefinition(executor).tools().definitions();
            List<ToolDefinition> second = factory.loadServerDefinition(executor).tools().definitions();

            assertEquals(33, first.size());
            assertEquals(first.size(), second.size());
            for (int index = 0; index < first.size(); index++) {
                assertSame(first.get(index), second.get(index));
            }
        }
    }

    @Test
    void concurrentFirstRuntimeInvocationsActivateExactlyOnce() throws Exception {
        var activations = new AtomicInteger();
        McpServerFactory.RuntimeResourceFactory delegate = McpServerFactory.productionResourceFactory();
        McpServerFactory.RuntimeResourceFactory counting = countingFactory(delegate, activations);
        var mapper = new CountingMcpJsonMapper(MAPPER);
        try (var composition = McpServerFactory.declarativeComposition(new AppEnvironment(Map.of()), mapper, counting);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ToolBinding<?> binding = composition.definitions().stream().filter(definition -> definition.name().equals("mc_script_logs")).findFirst().orElseThrow().binding();
            var first = executor.submit(() -> binding.invoke(mapper, Map.of(), Cancellation.none()).toCompletableFuture().join());
            var second = executor.submit(() -> binding.invoke(mapper, Map.of(), Cancellation.none()).toCompletableFuture().join());

            assertFalse(first.get().isError());
            assertFalse(second.get().isError());
            assertEquals(1, activations.get());
            assertEquals(2, mapper.convertValueCalls());
        }
    }

    @Test
    void runtimeActivationFailureClosesPartialResourcesAndIsNotRetried() {
        for (FailurePoint failurePoint : FailurePoint.values()) {
            TrackingRuntimeResourceFactory failing = new TrackingRuntimeResourceFactory(McpServerFactory.productionResourceFactory(), failurePoint, Set.of());
            var composition = McpServerFactory.declarativeComposition(new AppEnvironment(Map.of()), MAPPER, failing);
            ToolBinding<?> binding = composition.definitions().stream().filter(definition -> definition.name().equals("mc_script_logs")).findFirst().orElseThrow().binding();

            IllegalStateException first = assertThrows(IllegalStateException.class, () -> binding.invoke(MAPPER, Map.of(), Cancellation.none()));
            assertTrue(first.getMessage().contains(failurePoint.name()));
            assertFalse(composition.runtimeActivated());
            assertEquals(failurePoint == FailurePoint.CLIENT ? 0 : 1, failing.clientCloseCount());
            assertEquals(failurePoint == FailurePoint.CLIENT || failurePoint == FailurePoint.SESSION ? 0 : 1, failing.sessionCloseCount());
            assertEquals(failurePoint == FailurePoint.CLIENT || failurePoint == FailurePoint.SESSION || failurePoint == FailurePoint.SCHEDULER ? 0 : 1, failing.schedulerCloseCount());
            int clientCreations = failing.clientCreateCount();
            int sessionCreations = failing.sessionCreateCount();
            int schedulerCreations = failing.schedulerCreateCount();
            int bindingCreations = failing.bindingCreateCount();
            assertThrows(IllegalStateException.class, () -> binding.invoke(MAPPER, Map.of(), Cancellation.none()));
            assertEquals(clientCreations, failing.clientCreateCount(), failurePoint.name());
            assertEquals(sessionCreations, failing.sessionCreateCount(), failurePoint.name());
            assertEquals(schedulerCreations, failing.schedulerCreateCount(), failurePoint.name());
            assertEquals(bindingCreations, failing.bindingCreateCount(), failurePoint.name());
            composition.close();
        }
    }

    @Test
    void runtimeActivationCloseFailuresRemainSuppressedOnTheOriginalFailure() {
        Set<String> closeFailures = Set.of("session", "client", "scheduler");
        TrackingRuntimeResourceFactory failing = new TrackingRuntimeResourceFactory(McpServerFactory.productionResourceFactory(), FailurePoint.BINDINGS, closeFailures);
        try (var composition = McpServerFactory.declarativeComposition(new AppEnvironment(Map.of()), MAPPER, failing)) {
            ToolBinding<?> binding = composition.definitions().stream().filter(definition -> definition.name().equals("mc_script_logs")).findFirst().orElseThrow().binding();

            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> binding.invoke(MAPPER, Map.of(), Cancellation.none()));

            assertEquals("BINDINGS failure", failure.getMessage());
            assertEquals(1, failing.clientCloseCount());
            assertEquals(1, failing.sessionCloseCount());
            assertEquals(1, failing.schedulerCloseCount());
            for (String resource : closeFailures) {
                assertTrue(hasSuppressedMessage(failure, resource + " close failure"), resource);
            }
        }
    }

    @Test
    void closeWaitsForConcurrentFirstActivationAndStillClosesTheOwner() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        McpServerFactory.RuntimeResourceFactory blocking = blockingFactory(entered, release);
        var composition = McpServerFactory.declarativeComposition(new AppEnvironment(Map.of()), MAPPER, blocking);
        ToolBinding<?> binding = composition.definitions().stream().filter(definition -> definition.name().equals("mc_script_logs")).findFirst().orElseThrow().binding();
        try (composition; var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var invocation = executor.submit(() -> binding.invoke(MAPPER, Map.of(), Cancellation.none()).toCompletableFuture().join());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var closing = executor.submit(composition::close);
            assertFalse(closing.isDone(), "close must wait for in-flight activation");
            release.countDown();
            assertFalse(invocation.get().isError());
            closing.get(5, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, () -> binding.invoke(MAPPER, Map.of(), Cancellation.none()));
        }
    }

    private static McpServerFactory.RuntimeResourceFactory countingFactory(McpServerFactory.RuntimeResourceFactory delegate, AtomicInteger activations) {
        return new McpServerFactory.RuntimeResourceFactory() {
            @Override
            public HttpClient createClient() {
                return delegate.createClient();
            }

            @Override
            public BridgeSession createSession(HttpClient client, McpJsonMapper mapper, AppEnvironment environment) {
                return delegate.createSession(client, mapper, environment);
            }

            @Override
            public ScheduledExecutorService createScheduler() {
                return delegate.createScheduler();
            }

            @Override
            public RuntimeContext createContext(BridgeSession session, McpJsonMapper mapper, AppEnvironment environment, ScheduledExecutorService scheduler) {
                activations.incrementAndGet();
                return delegate.createContext(session, mapper, environment, scheduler);
            }
        };
    }

    private static boolean hasSuppressedMessage(Throwable failure, String message) {
        if (message.equals(failure.getMessage())) {
            return true;
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            if (hasSuppressedMessage(suppressed, message)) {
                return true;
            }
        }
        return false;
    }

    private static final class TrackingRuntimeResourceFactory implements McpServerFactory.RuntimeResourceFactory {
        private final McpServerFactory.RuntimeResourceFactory delegate;
        private final FailurePoint failurePoint;
        private final Set<String> closeFailures;
        private final AtomicInteger clientCreateCount = new AtomicInteger();
        private final AtomicInteger sessionCreateCount = new AtomicInteger();
        private final AtomicInteger schedulerCreateCount = new AtomicInteger();
        private final AtomicInteger bindingCreateCount = new AtomicInteger();
        private final AtomicInteger clientCloseCount = new AtomicInteger();
        private final AtomicInteger sessionCloseCount = new AtomicInteger();
        private final AtomicInteger schedulerCloseCount = new AtomicInteger();

        private TrackingRuntimeResourceFactory(McpServerFactory.RuntimeResourceFactory delegate, FailurePoint failurePoint, Set<String> closeFailures) {
            this.delegate = delegate;
            this.failurePoint = failurePoint;
            this.closeFailures = Set.copyOf(closeFailures);
        }

        @Override
        public HttpClient createClient() {
            clientCreateCount.incrementAndGet();
            if (failurePoint == FailurePoint.CLIENT) {
                throw failure(failurePoint);
            }
            return delegate.createClient();
        }

        @Override
        public BridgeSession createSession(HttpClient client, McpJsonMapper mapper, AppEnvironment environment) {
            sessionCreateCount.incrementAndGet();
            if (failurePoint == FailurePoint.SESSION) {
                throw failure(failurePoint);
            }
            return delegate.createSession(client, mapper, environment);
        }

        @Override
        public ScheduledExecutorService createScheduler() {
            schedulerCreateCount.incrementAndGet();
            if (failurePoint == FailurePoint.SCHEDULER) {
                throw failure(failurePoint);
            }
            return delegate.createScheduler();
        }

        @Override
        public RuntimeContext createContext(BridgeSession session, McpJsonMapper mapper, AppEnvironment environment, ScheduledExecutorService scheduler) {
            bindingCreateCount.incrementAndGet();
            if (failurePoint == FailurePoint.BINDINGS) {
                throw failure(failurePoint);
            }
            return delegate.createContext(session, mapper, environment, scheduler);
        }

        @Override
        public void closeSession(BridgeSession session) {
            sessionCloseCount.incrementAndGet();
            if (closeFailures.contains("session")) {
                throw new IllegalStateException("session close failure");
            }
            delegate.closeSession(session);
        }

        @Override
        public void closeClient(HttpClient client) {
            clientCloseCount.incrementAndGet();
            if (closeFailures.contains("client")) {
                throw new IllegalStateException("client close failure");
            }
            delegate.closeClient(client);
        }

        @Override
        public void closeScheduler(ScheduledExecutorService scheduler) {
            schedulerCloseCount.incrementAndGet();
            if (closeFailures.contains("scheduler")) {
                throw new IllegalStateException("scheduler close failure");
            }
            delegate.closeScheduler(scheduler);
        }

        private int clientCreateCount() {
            return clientCreateCount.get();
        }

        private int sessionCreateCount() {
            return sessionCreateCount.get();
        }

        private int schedulerCreateCount() {
            return schedulerCreateCount.get();
        }

        private int bindingCreateCount() {
            return bindingCreateCount.get();
        }

        private int clientCloseCount() {
            return clientCloseCount.get();
        }

        private int sessionCloseCount() {
            return sessionCloseCount.get();
        }

        private int schedulerCloseCount() {
            return schedulerCloseCount.get();
        }
    }

    private static McpServerFactory.RuntimeResourceFactory blockingFactory(CountDownLatch entered, CountDownLatch release) {
        McpServerFactory.RuntimeResourceFactory delegate = McpServerFactory.productionResourceFactory();
        return new McpServerFactory.RuntimeResourceFactory() {
            @Override
            public HttpClient createClient() {
                return delegate.createClient();
            }

            @Override
            public BridgeSession createSession(HttpClient client, McpJsonMapper mapper, AppEnvironment environment) {
                return delegate.createSession(client, mapper, environment);
            }

            @Override
            public ScheduledExecutorService createScheduler() {
                return delegate.createScheduler();
            }

            @Override
            public RuntimeContext createContext(BridgeSession session, McpJsonMapper mapper, AppEnvironment environment, ScheduledExecutorService scheduler) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("activation release timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("activation interrupted", exception);
                }
                return delegate.createContext(session, mapper, environment, scheduler);
            }
        };
    }

    private static IllegalStateException failure(FailurePoint failurePoint) {
        return new IllegalStateException(failurePoint.name() + " failure");
    }

    private enum FailurePoint {
        CLIENT, SESSION, SCHEDULER, BINDINGS
    }

    private static long pollThreadCount() {
        return Thread.getAllStackTraces().keySet().stream().filter(thread -> thread.getName().equals("mcdev-session-poll")).count();
    }

    @SuppressWarnings("unchecked")
    private static <A> ToolInput<A> declaredInput() {
        return (ToolInput<A>) ToolDeclarations.all().stream().filter(declaration -> declaration.name().equals("mc_snapshot")).findFirst().orElseThrow().input();
    }
}

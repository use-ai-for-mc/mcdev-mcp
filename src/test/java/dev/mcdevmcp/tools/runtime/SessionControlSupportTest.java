package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeMappingStatus;
import dev.mcdevmcp.bridge.BridgeResponse;
import dev.mcdevmcp.bridge.BridgeTestHarness;
import dev.mcdevmcp.bridge.SessionInfo;
import dev.mcdevmcp.minecraft.MinecraftServerAddress;
import dev.mcdevmcp.mcp.tool.api.RecordInputSchemaFactory;
import dev.mcdevmcp.mcp.tool.api.ToolInput;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SessionControlSupportTest {
    private static final Path GAME_DIRECTORY = Path.of(RuntimeContractFixtures.gameDirectory());

    @Test
    void classifiesJoinedDisconnectedPendingAndStaleWorldTransitions() {
        assertInstanceOf(InWorldPollResult.Joined.class, SessionControlSupport.classifyInWorldPoll(Map.of("player", Map.of()), Map.of("type", "ChatScreen")));
        InWorldPollResult.Failed failed = assertInstanceOf(InWorldPollResult.Failed.class, SessionControlSupport.classifyInWorldPoll(Map.of(), Map.of("type", "net.minecraft.DisconnectedScreen", "title", "Connection refused")));
        assertEquals("Connection refused", failed.reason());
        assertEquals("DisconnectedScreen", assertInstanceOf(InWorldPollResult.Failed.class, SessionControlSupport.classifyInWorldPoll(null, Map.of("type", "DisconnectedScreen", "title", ""))).reason());
        assertInstanceOf(InWorldPollResult.Pending.class, SessionControlSupport.classifyInWorldPoll(null, null));
        assertInstanceOf(InWorldPollResult.Pending.class, SessionControlSupport.classifyInWorldPoll(Map.of("player", false), null));

        var progress = new SessionControlSupport.InWorldWaitProgress();
        Map<String, Object> inWorld = Map.of("player", Map.of("x", 0));
        assertInstanceOf(InWorldPollResult.Pending.class, SessionControlSupport.stepInWorldWait(progress, true, inWorld, null));
        assertInstanceOf(InWorldPollResult.Pending.class, SessionControlSupport.stepInWorldWait(progress, true, null, null));
        assertInstanceOf(InWorldPollResult.Pending.class, SessionControlSupport.stepInWorldWait(progress, true, Map.of("world", Map.of()), null));
        assertInstanceOf(InWorldPollResult.Joined.class, SessionControlSupport.stepInWorldWait(progress, true, inWorld, null));
        assertInstanceOf(InWorldPollResult.Failed.class, SessionControlSupport.stepInWorldWait(new SessionControlSupport.InWorldWaitProgress(), true, Map.of(), Map.of("type", "DisconnectedScreen")));
    }

    @Test
    void parsesOnlyOneDistinctPositiveListeningPid() {
        assertEquals(4242L, SessionControlSupport.parseListeningPid(" 4242 \r\n4242\r\n"));
        assertNull(SessionControlSupport.parseListeningPid(""));
        assertNull(SessionControlSupport.parseListeningPid("4242\n5151\n"));
        assertNull(SessionControlSupport.parseListeningPid("4242\nwarning\n"));
        assertNull(SessionControlSupport.parseListeningPid("-1\n"));
        assertNull(SessionControlSupport.parseListeningPid("0\n"));
    }

    @Test
    void matchesGameDirectoryFirstThenVersionAndNeverGuessesWithoutComparableIdentity() {
        SessionInfo matchingDirectory = sessionInfo("1.19", GAME_DIRECTORY);
        SessionInfo noDirectory = sessionInfo("1.21.11", null);
        var expected = new SessionControlSupport.ExpectedInstance(Optional.of(new MinecraftVersion("1.21.11")), Optional.of(GAME_DIRECTORY));

        assertTrue(SessionControlSupport.instanceMatches(matchingDirectory, expected));
        assertTrue(SessionControlSupport.instanceMatches(noDirectory, expected));
        assertFalse(SessionControlSupport.instanceMatches(sessionInfo("1.19", null), expected));
        assertFalse(SessionControlSupport.instanceMatches(sessionInfo("1.21.11", null), new SessionControlSupport.ExpectedInstance(Optional.empty(), Optional.of(GAME_DIRECTORY))));
        assertTrue(SessionControlSupport.instanceMatches(sessionInfo("anything", null), SessionControlSupport.ExpectedInstance.none()));
        ToolInput<WaitForBridgeArguments> input = ToolInput.of(WaitForBridgeArguments.class, RecordInputSchemaFactory.standard());
        assertNull(input.decode(McpJsonDefaults.getMapper(), Map.of()).expectedVersion());
        assertEquals(new MinecraftVersion("1.21.11"), input.decode(McpJsonDefaults.getMapper(), Map.of("expectedVersion", "1.21.11")).expectedVersion());
    }

    @Test
    void decodesNumericTimeoutSecondsAndDefaultsIntoTheFinalJoinArguments() {
        ToolInput<JoinServerArguments> input = ToolInput.of(JoinServerArguments.class, RecordInputSchemaFactory.standard());

        JoinServerArguments defaults = input.decode(McpJsonDefaults.getMapper(), Map.of("address", "example.test"));
        JoinServerArguments explicit = input.decode(McpJsonDefaults.getMapper(), Map.of("address", "example.test", "acceptResourcePacks", false, "wait", false, "timeoutSeconds", new BigDecimal("1.25")));

        assertEquals(Duration.ofSeconds(60), defaults.timeoutSeconds());
        assertTrue(defaults.acceptResourcePacks());
        assertTrue(defaults.waitForWorld());
        assertEquals(Duration.ofMillis(1250), explicit.timeoutSeconds());
        assertFalse(explicit.acceptResourcePacks());
        assertFalse(explicit.waitForWorld());
        assertTrue(((Map<?, ?>) input.schema().value().get("properties")).containsKey("wait"));
        assertFalse(((Map<?, ?>) input.schema().value().get("properties")).containsKey("waitForWorld"));
        assertEquals(BigDecimal.ZERO, ((Map<?, ?>) ((Map<?, ?>) input.schema().value().get("properties")).get("timeoutSeconds")).get("minimum"));
    }

    @Test
    void exposesExactFinalSessionComponentsAndPropertyCreators() {
        assertEquals(List.of(MinecraftServerAddress.class, boolean.class, boolean.class, Duration.class), java.util.Arrays.stream(JoinServerArguments.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getType).toList());
        assertEquals(List.of(boolean.class, Duration.class), java.util.Arrays.stream(QuitClientArguments.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getType).toList());
        assertEquals(List.of(Duration.class, boolean.class), java.util.Arrays.stream(WaitUntilInWorldArguments.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getType).toList());
        assertEquals(List.of(MinecraftVersion.class, Duration.class), java.util.Arrays.stream(WaitForBridgeArguments.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getType).toList());
        assertPropertyCreator(JoinServerArguments.class, MinecraftServerAddress.class, Boolean.class, Boolean.class, Duration.class);
        assertPropertyCreator(QuitClientArguments.class, Boolean.class, Duration.class);
        assertPropertyCreator(WaitUntilInWorldArguments.class, Duration.class, Boolean.class);
        assertPropertyCreator(WaitForBridgeArguments.class, MinecraftVersion.class, Duration.class);
    }

    @Test
    void defaultsEverySessionInputAndPreservesExplicitFalseValues() {
        var mapper = McpJsonDefaults.getMapper();
        JoinServerArguments join = ToolInput.of(JoinServerArguments.class, RecordInputSchemaFactory.standard()).decode(mapper, Map.of("address", "example.test"));
        QuitClientArguments quit = ToolInput.of(QuitClientArguments.class, RecordInputSchemaFactory.standard()).decode(mapper, Map.of());
        WaitUntilInWorldArguments inWorld = ToolInput.of(WaitUntilInWorldArguments.class, RecordInputSchemaFactory.standard()).decode(mapper, Map.of());
        WaitForBridgeArguments bridge = ToolInput.of(WaitForBridgeArguments.class, RecordInputSchemaFactory.standard()).decode(mapper, Map.of());

        assertEquals(new MinecraftServerAddress("example.test"), join.address());
        assertTrue(join.acceptResourcePacks());
        assertTrue(join.waitForWorld());
        assertEquals(Duration.ofSeconds(60), join.timeoutSeconds());
        assertTrue(quit.waitForExit());
        assertEquals(Duration.ofSeconds(30), quit.timeoutSeconds());
        assertFalse(inWorld.requireAbsenceFirst());
        assertEquals(Duration.ofSeconds(60), inWorld.timeoutSeconds());
        assertNull(bridge.expectedVersion());
        assertEquals(Duration.ofSeconds(120), bridge.timeoutSeconds());

        JoinServerArguments explicitJoin = ToolInput.of(JoinServerArguments.class, RecordInputSchemaFactory.standard()).decode(mapper, Map.of("address", "example.test", "acceptResourcePacks", false, "wait", false, "timeoutSeconds", new BigDecimal("1.25")));
        QuitClientArguments explicitQuit = ToolInput.of(QuitClientArguments.class, RecordInputSchemaFactory.standard()).decode(mapper, Map.of("waitForExit", false, "timeoutSeconds", 0));
        WaitUntilInWorldArguments explicitInWorld = ToolInput.of(WaitUntilInWorldArguments.class, RecordInputSchemaFactory.standard()).decode(mapper, Map.of("requireAbsenceFirst", true, "timeoutSeconds", 0.5));
        WaitForBridgeArguments explicitBridge = ToolInput.of(WaitForBridgeArguments.class, RecordInputSchemaFactory.standard()).decode(mapper, Map.of("expectedVersion", "1.21.11", "timeoutSeconds", 0));

        assertFalse(explicitJoin.acceptResourcePacks());
        assertFalse(explicitJoin.waitForWorld());
        assertEquals(Duration.ofMillis(1250), explicitJoin.timeoutSeconds());
        assertFalse(explicitQuit.waitForExit());
        assertEquals(Duration.ZERO, explicitQuit.timeoutSeconds());
        assertTrue(explicitInWorld.requireAbsenceFirst());
        assertEquals(Duration.ofMillis(500), explicitInWorld.timeoutSeconds());
        assertEquals(new MinecraftVersion("1.21.11"), explicitBridge.expectedVersion());
        assertEquals(Duration.ZERO, explicitBridge.timeoutSeconds());
    }

    @Test
    void rejectsNegativeOrNullSessionTimeoutsAtConstruction() {
        MinecraftServerAddress address = new MinecraftServerAddress("example.test");
        assertThrows(IllegalArgumentException.class, () -> new JoinServerArguments(address, true, true, null));
        assertThrows(IllegalArgumentException.class, () -> new JoinServerArguments(address, true, true, Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> new QuitClientArguments(true, null));
        assertThrows(IllegalArgumentException.class, () -> new QuitClientArguments(true, Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> new WaitUntilInWorldArguments(null, false));
        assertThrows(IllegalArgumentException.class, () -> new WaitUntilInWorldArguments(Duration.ofSeconds(-1), false));
        assertThrows(IllegalArgumentException.class, () -> new WaitForBridgeArguments(null, null));
        assertThrows(IllegalArgumentException.class, () -> new WaitForBridgeArguments(null, Duration.ofSeconds(-1)));
    }

    private static void assertPropertyCreator(Class<?> type, Class<?>... parameterTypes) {
        var creators = java.util.Arrays.stream(type.getDeclaredMethods()).filter(method -> java.lang.reflect.Modifier.isStatic(method.getModifiers()) && type.equals(method.getReturnType()) && method.isAnnotationPresent(com.fasterxml.jackson.annotation.JsonCreator.class)).toList();
        assertEquals(1, creators.size(), type.getSimpleName());
        var creator = creators.getFirst();
        assertEquals(com.fasterxml.jackson.annotation.JsonCreator.Mode.PROPERTIES, creator.getAnnotation(com.fasterxml.jackson.annotation.JsonCreator.class).mode());
        assertEquals(List.of(parameterTypes), List.of(creator.getParameterTypes()));
    }

    @Test
    void scansDocumentedPortsPlusOneValidConfiguredOutOfRangePort() {
        List<Integer> documented = List.of(9876, 9877, 9878, 9879, 9880, 9881, 9882, 9883, 9884, 9885, 9886);
        assertEquals(List.of(9999, 9876, 9877, 9878, 9879, 9880, 9881, 9882, 9883, 9884, 9885, 9886), bridgePorts(Map.of("DEBUGBRIDGE_PORT", " 9999.0 ")));
        assertEquals(documented, bridgePorts(Map.of("DEBUGBRIDGE_PORT", " 9876.0 ")));
        for (String configured : List.of("65536", "9999.5", "0x2694")) {
            assertEquals(documented, bridgePorts(Map.of("DEBUGBRIDGE_PORT", configured)), configured);
        }
    }

    @Test
    void cancellationPreventsAComposedSideEffectFromStarting() {
        var first = new CompletableFuture<Integer>();
        var invoked = new AtomicBoolean();
        CompletableFuture<Integer> composed = SessionControlSupport.composeCancellable(first, value -> {
            invoked.set(true);
            return CompletableFuture.completedFuture(value + 1);
        }).toCompletableFuture();

        assertTrue(composed.cancel(true));
        first.complete(1);

        assertFalse(invoked.get());
        assertTrue(first.isCancelled());
    }

    @Test
    void usesWrapSafeMonotonicSubtractionAndSaturatesSchedulingDurations() {
        assertEquals(7L, SessionControlSupport.elapsedNanos(Long.MAX_VALUE - 3, Long.MIN_VALUE + 3));
        assertEquals(0L, SessionControlSupport.saturatedNanos(Duration.ZERO));
        assertEquals(1_250_000_000L, SessionControlSupport.saturatedNanos(Duration.ofMillis(1_250)));
        assertEquals(Long.MAX_VALUE, SessionControlSupport.saturatedNanos(Duration.ofSeconds(Long.MAX_VALUE)));
        assertEquals(0L, SessionControlSupport.saturatedNanos(Duration.ofNanos(-1)));
    }

    @Test
    void waitExpiryUsesMonotonicNanosecondsAcrossSignedWrap() throws Exception {
        try (var harness = new BridgeTestHarness(McpJsonDefaults.getMapper(), new AppEnvironment(Map.of()), (_, request) -> switch (request.endpoint().wireName()) {
            case "status" -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id()));
            case "snapshot" ->
                    CompletableFuture.completedFuture(new BridgeResponse(request.id(), true, true, Map.of(), null, null));
            case "screenInspect" ->
                    CompletableFuture.completedFuture(new BridgeResponse(request.id(), true, true, Map.of("type", "ChatScreen"), null, null));
            default ->
                    CompletableFuture.failedFuture(new AssertionError("Unexpected endpoint: " + request.endpoint().wireName()));
        }); var scheduler = new CapturingScheduler()) {
            harness.session().connect(null).toCompletableFuture().get(5, TimeUnit.SECONDS);
            var ticker = new SequenceTicker(Long.MAX_VALUE - 5, Long.MIN_VALUE + 5);
            var support = new SessionControlSupport(harness.session(), new AppEnvironment(Map.of()), scheduler, ticker, _ -> CompletableFuture.completedFuture(false), _ -> CompletableFuture.completedFuture(null));

            InWorldWaitResult result = support.waitUntilInWorld(Duration.ofNanos(10), false, Cancellation.none()).toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(InWorldWaitResult.State.TIMEOUT, result.state());
            assertEquals(0.0, result.elapsedSeconds());
        }
    }

    @Test
    void asynchronousPidProbeReadsOutputBeforeClosingTheProcess() throws Exception {
        ProbeProcess process = new ProbeProcess("4242\n");
        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            SessionControlSupport.ListeningPidResolver resolver = SessionControlSupport.listeningPidResolver(scheduler, _ -> process);
            CompletionStage<Long> pid = resolver.resolve(9876);
            process.exited.complete(process);

            assertEquals(4242L, pid.toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(1, process.closeCount.get());
            assertTrue(process.stdoutConsumed.get());
            assertFalse(process.destroyed.get());
        }
    }

    @Test
    @SuppressWarnings("resource")
    void cancellingPidProbeDestroysAndClosesTheProcessOnce() throws Exception {
        ProbeProcess process = new ProbeProcess("4242\n");
        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            SessionControlSupport.ListeningPidResolver resolver = SessionControlSupport.listeningPidResolver(scheduler, _ -> process);
            CompletableFuture<Long> pid = resolver.resolve(9876).toCompletableFuture();

            assertTrue(pid.cancel(true));
            assertTrue(process.destroyed.get());
            assertTrue(process.closeEntered.await(1, TimeUnit.SECONDS));
            assertEquals(1, process.closeCount.get());
        }
    }

    @Test
    @SuppressWarnings("resource")
    void cancellationReturnsPromptlyWhenProcessCloseBlocks() throws Exception {
        ProbeProcess process = ProbeProcess.blocking();
        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            SessionControlSupport.ListeningPidResolver resolver = SessionControlSupport.listeningPidResolver(scheduler, _ -> process);
            CompletableFuture<Long> pid = resolver.resolve(9876).toCompletableFuture();

            assertTrue(pid.cancel(true));
            assertTrue(process.destroyed.get());
            assertTrue(process.closeEntered.await(1, TimeUnit.SECONDS));
            assertTrue(pid.isCancelled());
            assertEquals(1, process.closeCount.get());
            process.releaseClose.countDown();
            assertTrue(process.closeFinished.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    @SuppressWarnings("resource")
    void scheduledTimeoutReturnsPromptlyAndCancelsNoSharedSchedulerThread() throws Exception {
        ProbeProcess process = ProbeProcess.blocking();
        try (var scheduler = new CapturingScheduler()) {
            SessionControlSupport.ListeningPidResolver resolver = SessionControlSupport.listeningPidResolver(scheduler, _ -> process);
            CompletableFuture<Long> pid = resolver.resolve(9876).toCompletableFuture();
            ScheduledFuture<?> scheduled = scheduler.scheduled.poll(1, TimeUnit.SECONDS);
            assertNotNull(scheduled);
            ManualScheduledFuture timeout = (ManualScheduledFuture) scheduled;

            Thread timeoutThread = Thread.ofPlatform().start(timeout);
            assertTrue(process.closeEntered.await(1, TimeUnit.SECONDS));
            timeoutThread.join(1_000);
            assertFalse(timeoutThread.isAlive(), "timeout callback must not wait for Process.close()");
            assertNull(pid.get(1, TimeUnit.SECONDS));
            process.releaseClose.countDown();
            assertTrue(process.closeFinished.await(1, TimeUnit.SECONDS));
            assertEquals(1, process.closeCount.get());
        }
    }

    @Test
    @SuppressWarnings("resource")
    void normalCompletionCancelsTheProbeTimerAndRegistrationFailureStillCleansUp() throws Exception {
        ProbeProcess success = new ProbeProcess("4242\n");
        try (var scheduler = new CapturingScheduler()) {
            SessionControlSupport.ListeningPidResolver resolver = SessionControlSupport.listeningPidResolver(scheduler, _ -> success);
            CompletableFuture<Long> pid = resolver.resolve(9876).toCompletableFuture();
            success.exited.complete(success);
            assertEquals(4242L, pid.get(1, TimeUnit.SECONDS));
            ScheduledFuture<?> scheduled = scheduler.scheduled.poll(1, TimeUnit.SECONDS);
            assertNotNull(scheduled);
            ManualScheduledFuture timeout = (ManualScheduledFuture) scheduled;
            assertTrue(timeout.isCancelled());
            assertEquals(1, success.closeCount.get());
        }

        ProbeProcess registrationFailure = ProbeProcess.registrationFailure();
        try (var scheduler = new CapturingScheduler()) {
            SessionControlSupport.ListeningPidResolver resolver = SessionControlSupport.listeningPidResolver(scheduler, _ -> registrationFailure);
            assertNull(resolver.resolve(9876).toCompletableFuture().get(1, TimeUnit.SECONDS));
            assertTrue(registrationFailure.destroyed.get());
            assertTrue(scheduler.scheduled.isEmpty());
            assertTrue(registrationFailure.closeEntered.await(1, TimeUnit.SECONDS));
            assertEquals(1, registrationFailure.closeCount.get());
        }
    }

    @Test
    void nonzeroExitAndStdoutReadFailureStillCloseExactlyOnce() throws Exception {
        for (ProbeProcess process : List.of(ProbeProcess.nonzero(), ProbeProcess.readFailure())) {
            try (var scheduler = new CapturingScheduler()) {
                SessionControlSupport.ListeningPidResolver resolver = SessionControlSupport.listeningPidResolver(scheduler, _ -> process);
                CompletableFuture<Long> pid = resolver.resolve(9876).toCompletableFuture();
                process.exited.complete(process);

                assertNull(pid.get(1, TimeUnit.SECONDS));
                assertTrue(process.closeEntered.await(1, TimeUnit.SECONDS));
                assertEquals(1, process.closeCount.get());
            }
        }
    }

    @Test
    void portCloseFallbackAndProcessClassificationAreConservative() throws Exception {
        try (var harness = new BridgeTestHarness(McpJsonDefaults.getMapper(), new AppEnvironment(Map.of()), (_, request) -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id())));
             ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            var support = new SessionControlSupport(harness.session(), new AppEnvironment(Map.of()), scheduler, MonotonicTicker.system(), _ -> CompletableFuture.completedFuture(false), _ -> CompletableFuture.completedFuture(null));
            ClientExitResult result = support.waitForClientExit(9876, null, Duration.ofSeconds(2), Cancellation.none()).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(new ClientExitResult.Exited(false), result);
        }
        assertTrue(SessionControlSupport.processAlive(ProcessHandle.current().pid()));
        assertFalse(SessionControlSupport.processAlive(Long.MAX_VALUE));
    }

    @Test
    void bridgeDeadlineWaitsForMismatchRecordingBeforePublishingTheTimeout() throws Exception {
        AtomicReference<CompletableFuture<BridgeResponse>> response = new AtomicReference<>();
        var notes = new BlockingNoteList();
        try (var harness = new BridgeTestHarness(McpJsonDefaults.getMapper(), new AppEnvironment(Map.of()), (_, _) -> {
            CompletableFuture<BridgeResponse> pending = new CompletableFuture<>();
            response.set(pending);
            return pending;
        }); var scheduler = new CapturingScheduler()) {
            var support = new SessionControlSupport(harness.session(), new AppEnvironment(Map.of()), scheduler, MonotonicTicker.system(), _ -> CompletableFuture.completedFuture(false), _ -> CompletableFuture.completedFuture(null));
            var expected = new SessionControlSupport.ExpectedInstance(Optional.of(new MinecraftVersion("different")), Optional.empty());
            CompletableFuture<SessionControlSupport.FoundBridge> wait = support.waitForBridge(expected, Duration.ofSeconds(10), notes, Cancellation.none()).toCompletableFuture();

            CompletableFuture<BridgeResponse> pending = response.get();
            assertNotNull(pending);
            Thread mismatch = Thread.ofPlatform().start(() -> pending.complete(RuntimeContractFixtures.status("req_1")));
            assertTrue(notes.addEntered.await(1, TimeUnit.SECONDS));

            ScheduledFuture<?> scheduledDeadline = scheduler.scheduled.poll(1, TimeUnit.SECONDS);
            assertNotNull(scheduledDeadline);
            ManualScheduledFuture deadline = assertInstanceOf(ManualScheduledFuture.class, scheduledDeadline);
            Thread deadlineThread = Thread.ofPlatform().start(deadline);
            assertTrue(deadline.runStarted.await(1, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> deadline.get(100, TimeUnit.MILLISECONDS));
            assertFalse(wait.isDone());

            notes.releaseAdd.countDown();
            mismatch.join();
            deadlineThread.join();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> wait.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("Other instances answered: port 9876"));
            assertEquals(1, notes.size());
        }
    }

    @Test
    void scriptLoggerUsesNodePathsCompactJsonlStatsAndRotation(@TempDir Path temporary) throws Exception {
        var mapper = McpJsonDefaults.getMapper();
        var diagnostics = new java.util.ArrayList<String>();
        var logger = new ScriptLogger(temporary, mapper, diagnostics::add, () -> false, InstantSource.fixed(Instant.ofEpochMilli(1234L)));
        logger.log(new ScriptLogger.ScriptLogEntry(Instant.parse("2026-07-28T00:00:00Z"), true, "return 1", true, 1, "ok", null, Duration.ofMillis(5)), false);
        logger.log(new ScriptLogger.ScriptLogEntry(Instant.parse("2026-07-28T00:00:01Z"), false, "badName", false, null, null, "Failure at line 12:34: 'badName'", Duration.ofMillis(7)), false);
        logger.log(new ScriptLogger.ScriptLogEntry(Instant.parse("2026-07-28T00:00:02Z"), true, "return null", true, null, "", null, Duration.ofMillis(2)), false);
        logger.log(new ScriptLogger.ScriptLogEntry(Instant.parse("2026-07-28T00:00:03Z"), true, "no result", false, null, "", null, Duration.ofMillis(3)), false);

        List<String> all = Files.readAllLines(logger.allLogPath(), StandardCharsets.UTF_8);
        List<String> errors = Files.readAllLines(logger.errorsLogPath(), StandardCharsets.UTF_8);
        assertEquals(4, all.size());
        assertEquals(1, errors.size());
        assertFalse(all.getFirst().contains("\"error\""));
        assertFalse(all.getFirst().contains("\n"));
        assertTrue(all.get(2).contains("\"result\":null"));
        assertFalse(all.get(3).contains("\"result\""));
        assertEquals("Failure at line 12:34: 'badName'", logger.recentErrors(20).getFirst().error());
        ScriptLogger.ScriptErrorStat stat = logger.errorStats().getFirst();
        assertEquals("Failure at line N:N: '...'", stat.error());
        assertEquals(1, stat.count());
        assertEquals(List.of("badName"), stat.examples());

        Files.write(logger.allLogPath(), new byte[(int) ScriptLogger.MAX_LOG_BYTES + 1]);
        logger.rotateIfNeeded();
        assertFalse(Files.exists(logger.allLogPath()));
        assertTrue(Files.exists(logger.logDirectory().resolve("all.1234.jsonl")));
        assertTrue(diagnostics.isEmpty());

        // The Linux dataDirectory layout is verifiable on any POSIX host.
        assertEquals(Path.of("/home/test/.local/share/mcdev-mcp"), ScriptLogger.dataDirectory("Linux", new AppEnvironment(Map.of()), Path.of("/home/test")));

        // The Windows dataDirectory branch is only reachable in production when the
        // host OS is actually Windows, where java.nio.Path uses backslash separators.
        // On a POSIX CI runner the expected literal form (C:\\Local\\mcdev-mcp\\Data)
        // cannot be produced, so only assert it when running on Windows.
        var windows = new AppEnvironment(Map.of("LOCALAPPDATA", "C:\\Local"));
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win"), "Windows-local dataDirectory layout only verifiable on a Windows host");
        assertEquals(Path.of("C:\\Local\\mcdev-mcp\\Data"), ScriptLogger.dataDirectory("Windows 11", windows, Path.of("C:\\Home")));
    }

    private static List<Integer> bridgePorts(Map<String, String> environment) {
        try (var harness = new BridgeTestHarness(McpJsonDefaults.getMapper(), new AppEnvironment(environment), (_, request) -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id())));
             ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            return new SessionControlSupport(harness.session(), new AppEnvironment(environment), scheduler).bridgePortRange();
        }
    }

    private static SessionInfo sessionInfo(String version, Path gameDirectory) {
        return new SessionInfo(9876, new MinecraftVersion(version), BridgeMappingStatus.MOJANG, false, 0, Optional.ofNullable(gameDirectory), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static final class BlockingNoteList extends AbstractList<String> {
        private final List<String> notes = new ArrayList<>();
        private final CountDownLatch addEntered = new CountDownLatch(1);
        private final CountDownLatch releaseAdd = new CountDownLatch(1);

        @Override
        public String get(int index) {
            return notes.get(index);
        }

        @Override
        public int size() {
            return notes.size();
        }

        @Override
        public boolean add(String note) {
            addEntered.countDown();
            try {
                if (!releaseAdd.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release note recording");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            return notes.add(note);
        }
    }

    @SuppressWarnings("NullableProblems")
    private static final class CapturingScheduler extends ScheduledThreadPoolExecutor {
        private final BlockingQueue<ScheduledFuture<?>> scheduled = new LinkedBlockingQueue<>();

        private CapturingScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            var future = new ManualScheduledFuture(command);
            scheduled.add(future);
            return future;
        }
    }

    @SuppressWarnings("NullableProblems")
    private static final class ManualScheduledFuture extends FutureTask<Void> implements ScheduledFuture<Void> {
        private final CountDownLatch runStarted = new CountDownLatch(1);

        private ManualScheduledFuture(Runnable command) {
            super(command, null);
        }

        @Override
        public void run() {
            runStarted.countDown();
            super.run();
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return other == this ? 0 : Integer.compare(System.identityHashCode(this), System.identityHashCode(other));
        }
    }

    private static final class SequenceTicker implements MonotonicTicker {
        private final long[] values;
        private int index;

        private SequenceTicker(long... values) {
            this.values = values;
        }

        @Override
        public long readNanos() {
            return values[Math.min(index++, values.length - 1)];
        }
    }

    private static final class ProbeProcess extends Process {
        private final CompletableFuture<Process> exited = new CompletableFuture<>();
        private final AtomicBoolean stdoutConsumed = new AtomicBoolean();
        private final AtomicBoolean destroyed = new AtomicBoolean();
        private final AtomicInteger closeCount = new AtomicInteger();
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch closeFinished = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);
        private final int exitCode;
        private final boolean blockClose;
        private final boolean completeOnDestroy;
        private final boolean registrationFailure;
        private final InputStream stdout;

        private ProbeProcess(String output) {
            this(output, 0, false, true, false);
        }

        private ProbeProcess(String output, int exitCode, boolean blockClose, boolean completeOnDestroy, boolean registrationFailure) {
            this(output, exitCode, blockClose, completeOnDestroy, registrationFailure, false);
        }

        private ProbeProcess(String output, int exitCode, boolean blockClose, boolean completeOnDestroy, boolean registrationFailure, boolean readFailure) {
            this.exitCode = exitCode;
            this.blockClose = blockClose;
            this.completeOnDestroy = completeOnDestroy;
            this.registrationFailure = registrationFailure;
            stdout = readFailure ? new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("stdout read failed");
                }
            } : new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
        }

        private static ProbeProcess blocking() {
            return new ProbeProcess("4242\n", 0, true, false, false);
        }

        private static ProbeProcess registrationFailure() {
            return new ProbeProcess("", 0, false, false, true);
        }

        private static ProbeProcess nonzero() {
            return new ProbeProcess("4242\n", 1, false, true, false);
        }

        private static ProbeProcess readFailure() {
            return new ProbeProcess("", 0, false, true, false, true);
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public void destroy() {
            destroyed.set(true);
        }

        @Override
        public Process destroyForcibly() {
            destroyed.set(true);
            if (completeOnDestroy) {
                exited.complete(this);
            }
            return this;
        }

        @Override
        public boolean isAlive() {
            return !exited.isDone();
        }

        @Override
        public CompletableFuture<Process> onExit() {
            if (registrationFailure) {
                throw new IllegalStateException("onExit registration failed");
            }
            return exited;
        }

        @Override
        public void close() throws IOException {
            closeCount.incrementAndGet();
            closeEntered.countDown();
            if (blockClose) {
                try {
                    if (!releaseClose.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting to release close");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(exception);
                }
            }
            stdoutConsumed.set(stdout.available() == 0);
            closeFinished.countDown();
            if (!blockClose && !stdoutConsumed.get()) {
                throw new IOException("stdout was not consumed before close");
            }
        }
    }
}

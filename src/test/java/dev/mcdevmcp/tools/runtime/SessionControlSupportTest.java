package dev.mcdevmcp.tools.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import dev.mcdevmcp.bridge.*;
import dev.mcdevmcp.mcp.tool.api.RecordInputSchemaFactory;
import dev.mcdevmcp.mcp.tool.api.ToolInput;
import dev.mcdevmcp.minecraft.MinecraftServerAddress;
import dev.mcdevmcp.storage.model.MinecraftVersion;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SessionControlSupportTest {
    private static final Path GAME_DIRECTORY = Path.of(RuntimeContractFixtures.gameDirectory());

    private static void assertPropertyCreator(Class<?> type, Class<?>... parameterTypes) {
        var creators = Arrays.stream(type.getDeclaredMethods()).filter(method -> Modifier.isStatic(method.getModifiers()) && type.equals(method.getReturnType()) && method.isAnnotationPresent(JsonCreator.class)).toList();
        assertEquals(1, creators.size(), type.getSimpleName());
        var creator = creators.getFirst();
        assertEquals(JsonCreator.Mode.PROPERTIES, creator.getAnnotation(JsonCreator.class).mode());
        assertEquals(List.of(parameterTypes), List.of(creator.getParameterTypes()));
    }

    private static List<Integer> bridgePorts(Map<String, String> environment) {
        try (var harness = new BridgeTestHarness(McpJsonDefaults.getMapper(), new AppEnvironment(environment), (_, request) -> RuntimeContractFixtures.status(request.id()))) {
            return new SessionControlSupport(harness.session(), new AppEnvironment(environment)).bridgePortRange();
        }
    }

    private static SessionInfo sessionInfo(String version, Path gameDirectory) {
        return new SessionInfo(9876, new MinecraftVersion(version), BridgeMappingStatus.MOJANG, false, 0, Optional.ofNullable(gameDirectory), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

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
        var expected = new ExpectedInstance(Optional.of(new MinecraftVersion("1.21.11")), Optional.of(GAME_DIRECTORY));

        assertTrue(SessionControlSupport.instanceMatches(matchingDirectory, expected));
        assertTrue(SessionControlSupport.instanceMatches(noDirectory, expected));
        assertFalse(SessionControlSupport.instanceMatches(sessionInfo("1.19", null), expected));
        assertFalse(SessionControlSupport.instanceMatches(sessionInfo("1.21.11", null), new ExpectedInstance(Optional.empty(), Optional.of(GAME_DIRECTORY))));
        assertTrue(SessionControlSupport.instanceMatches(sessionInfo("anything", null), ExpectedInstance.none()));
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
        assertEquals(List.of(MinecraftServerAddress.class, boolean.class, boolean.class, Duration.class), Arrays.stream(JoinServerArguments.class.getRecordComponents()).map(RecordComponent::getType).toList());
        assertEquals(List.of(boolean.class, Duration.class), Arrays.stream(QuitClientArguments.class.getRecordComponents()).map(RecordComponent::getType).toList());
        assertEquals(List.of(Duration.class, boolean.class), Arrays.stream(WaitUntilInWorldArguments.class.getRecordComponents()).map(RecordComponent::getType).toList());
        assertEquals(List.of(MinecraftVersion.class, Duration.class), Arrays.stream(WaitForBridgeArguments.class.getRecordComponents()).map(RecordComponent::getType).toList());
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

    @Test
    void scansTheConfiguredPortFirstThenTheDocumentedRange() {
        List<Integer> documented = List.of(9876, 9877, 9878, 9879, 9880, 9881, 9882, 9883, 9884, 9885, 9886);
        assertEquals(List.of(9999, 9876, 9877, 9878, 9879, 9880, 9881, 9882, 9883, 9884, 9885, 9886), bridgePorts(Map.of("DEBUGBRIDGE_PORT", " 9999.0 ")));
        assertEquals(documented, bridgePorts(Map.of("DEBUGBRIDGE_PORT", " 9876.0 ")));
        assertEquals(List.of(9878, 9876, 9877, 9879, 9880, 9881, 9882, 9883, 9884, 9885, 9886), bridgePorts(Map.of("DEBUGBRIDGE_PORT", "9878")), "a configured port inside the range is still tried first");
        for (String configured : List.of("65536", "9999.5", "0x2694")) {
            assertEquals(documented, bridgePorts(Map.of("DEBUGBRIDGE_PORT", configured)), configured);
        }
    }

    @Test
    void cancellingAGatedToolWhileItAwaitsSessionStatusNeverSendsItsSideEffect() throws Exception {
        var status = new CompletableFuture<BridgeResponse>();
        var environment = new AppEnvironment(Map.of());
        try (var harness = new BridgeTestHarness(McpJsonDefaults.getMapper(), environment, (_, request) -> request.endpoint().wireName().equals("status") ? status.get() : new BridgeResponse(request.id(), true, true, Map.of(), null, null))) {
            var join = McJoinServerTool.handler(new RuntimeToolSupport(harness.session(), McpJsonDefaults.getMapper()), new SessionControlSupport(harness.session(), environment));
            var call = BlockingCalls.start(() -> join.handle(new JoinServerArguments(new MinecraftServerAddress("example.test"), true, false, Duration.ofSeconds(1)), Cancellation.none()));
            BlockingCalls.awaitUntil(() -> harness.requests().size() == 1);

            assertTrue(call.cancel());
            call.awaitStopped();
            status.complete(RuntimeContractFixtures.status("req_1"));

            BlockingCalls.awaitUntil(() -> harness.session().connectedPort().isPresent());
            assertEquals(List.of("status"), harness.requests().stream().map(request -> request.endpoint().wireName()).toList());
        }
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
            case "status" -> RuntimeContractFixtures.status(request.id());
            case "snapshot" ->
                    new BridgeResponse(request.id(), true, true, Map.of(), null, null);
            case "screenInspect" ->
                    new BridgeResponse(request.id(), true, true, Map.of("type", "ChatScreen"), null, null);
            default ->
                    throw new AssertionError("Unexpected endpoint: " + request.endpoint().wireName());
        })) {
            harness.session().connect(null);
            var ticker = new SequenceTicker(Long.MAX_VALUE - 5, Long.MIN_VALUE + 5);
            var support = new SessionControlSupport(harness.session(), new AppEnvironment(Map.of()), ticker, _ -> false, _ -> null);

            InWorldWaitResult result = support.waitUntilInWorld(Duration.ofNanos(10), false, Cancellation.none());

            assertEquals(InWorldWaitResult.State.TIMEOUT, result.state());
            assertEquals(0.0, result.elapsedSeconds());
        }
    }

    @Test
    void pidProbeReadsOutputBeforeClosingTheProcess() throws Exception {
        ProbeProcess process = new ProbeProcess("4242\n");
        {
            SessionControlSupport.ListeningPidResolver resolver = SessionControlSupport.listeningPidResolver(_ -> process);
            var pid = BlockingCalls.start(() -> resolver.resolve(9876));
            process.exited.complete(process);

            assertEquals(4242L, pid.get());
            assertEquals(1, process.closeCount.get());
            assertTrue(process.stdoutConsumed.get());
            assertFalse(process.destroyed.get());
        }
    }

    @Test
    void cancellingPidProbeDestroysAndClosesTheProcessOnce() throws Exception {
        ProbeProcess process = new ProbeProcess("4242\n");
        {
            SessionControlSupport.ListeningPidResolver resolver = SessionControlSupport.listeningPidResolver(announcing(process));
            var pid = BlockingCalls.start(() -> resolver.resolve(9876));

            assertTrue(process.started.await(1, TimeUnit.SECONDS));
            assertTrue(process.probeEntered.await(1, TimeUnit.SECONDS));
            assertTrue(pid.cancel());
            assertTrue(process.destroyedLatch.await(1, TimeUnit.SECONDS));
            assertTrue(process.closeEntered.await(1, TimeUnit.SECONDS));
            pid.awaitStopped();
            assertEquals(1, process.closeCount.get());
        }
    }

    @Test
    void cancellationReportsPromptlyButTheCallerStopsOnlyAfterProcessCloseFinishes() throws Exception {
        ProbeProcess process = ProbeProcess.blocking();
        {
            SessionControlSupport.ListeningPidResolver resolver = SessionControlSupport.listeningPidResolver(announcing(process));
            var pid = BlockingCalls.start(() -> resolver.resolve(9876));

            assertTrue(process.started.await(1, TimeUnit.SECONDS));
            assertTrue(process.probeEntered.await(1, TimeUnit.SECONDS));
            assertTrue(pid.cancel());
            assertTrue(process.destroyedLatch.await(1, TimeUnit.SECONDS));
            assertTrue(process.closeEntered.await(1, TimeUnit.SECONDS));
            assertTrue(pid.task().isCancelled());
            assertEquals(1, process.closeCount.get());
            assertTrue(pid.thread().isAlive());
            process.releaseClose.countDown();
            assertTrue(process.closeFinished.await(1, TimeUnit.SECONDS));
            pid.awaitStopped();
        }
    }

    @Test
    void cancellationInterruptsAStarterOwnedByTheStructuredScope() throws Exception {
        CountDownLatch starterEntered = new CountDownLatch(1);
        CountDownLatch starterCaughtInterrupt = new CountDownLatch(1);
        CountDownLatch releaseStarter = new CountDownLatch(1);
        SessionControlSupport.ListeningPidResolver resolver = SessionControlSupport.listeningPidResolver(_ -> {
            starterEntered.countDown();
            try {
                releaseStarter.await();
            } catch (InterruptedException interrupted) {
                starterCaughtInterrupt.countDown();
                Thread.currentThread().interrupt();
                throw new IOException("starter interrupted", interrupted);
            }
            throw new IOException("starter released for cancellation test");
        });

        var pid = BlockingCalls.start(() -> resolver.resolve(9876));
        assertTrue(starterEntered.await(1, TimeUnit.SECONDS));
        assertTrue(pid.cancel());
        assertTrue(starterCaughtInterrupt.await(1, TimeUnit.SECONDS));
        releaseStarter.countDown();
        pid.awaitStopped();
    }

    @Test
    void lateReturningStarterIsDestroyedAfterCancellation() throws Exception {
        ProbeProcess process = new ProbeProcess("4242\n");
        CountDownLatch starterEntered = new CountDownLatch(1);
        CountDownLatch starterCaughtInterrupt = new CountDownLatch(1);
        CountDownLatch releaseStarter = new CountDownLatch(1);
        var handover = starting(process);
        var resolver = SessionControlSupport.listeningPidResolver(command -> {
            starterEntered.countDown();
            try {
                releaseStarter.await();
            } catch (InterruptedException interrupted) {
                starterCaughtInterrupt.countDown();
                while (Thread.interrupted()) {
                    Thread.yield();
                }
                while (true) {
                    try {
                        releaseStarter.await();
                        break;
                    } catch (InterruptedException ignored) {
                        while (Thread.interrupted()) {
                            Thread.yield();
                        }
                    }
                }
                Thread.currentThread().interrupt();
            }
            return handover.start(command);
        });

        var pid = BlockingCalls.start(() -> resolver.resolve(9876));
        assertTrue(starterEntered.await(1, TimeUnit.SECONDS));
        assertTrue(pid.cancel());
        assertTrue(starterCaughtInterrupt.await(1, TimeUnit.SECONDS));
        releaseStarter.countDown();
        assertTrue(process.destroyedLatch.await(1, TimeUnit.SECONDS));
        assertTrue(process.closeEntered.await(1, TimeUnit.SECONDS));
        assertEquals(1, process.probeEntered.getCount());
        pid.awaitStopped();
    }

    @Test
    void pidProbeHonorsItsRealFourSecondDeadlineAndDrainsBothPipes() throws Exception {
        TimeoutProcess process = new TimeoutProcess();
        SessionControlSupport.ListeningPidResolver resolver = SessionControlSupport.listeningPidResolver(starting(process));
        long started = System.nanoTime();
        Long pid = resolver.resolve(9876);
        long elapsed = System.nanoTime() - started;

        assertNull(pid);
        assertTrue(elapsed >= Duration.ofSeconds(3).toNanos(), "probe completed before its bounded deadline");
        assertTrue(process.destroyed.await(1, TimeUnit.SECONDS));
        assertTrue(process.stdoutRead.await(1, TimeUnit.SECONDS));
        assertTrue(process.stderrRead.await(1, TimeUnit.SECONDS));
    }

    @Test
    void anAlreadyInterruptedCallerNeverStartsThePidProbe() throws Exception {
        AtomicBoolean starterInvoked = new AtomicBoolean();
        SessionControlSupport.ListeningPidResolver resolver = SessionControlSupport.listeningPidResolver(_ -> {
            starterInvoked.set(true);
            throw new IOException("starter must not run");
        });

        var pid = BlockingCalls.start(() -> {
            Thread.currentThread().interrupt();
            return resolver.resolve(9876);
        });

        assertInstanceOf(InterruptedException.class, pid.failure());
        assertFalse(starterInvoked.get());
    }

    @Test
    void successfulPidProbeClosesProcessExactlyOnce() throws Exception {
        ProbeProcess success = new ProbeProcess("4242\n");
        {
            SessionControlSupport.ListeningPidResolver resolver = SessionControlSupport.listeningPidResolver(_ -> success);
            var pid = BlockingCalls.start(() -> resolver.resolve(9876));
            success.exited.complete(success);
            assertEquals(4242L, pid.get());
            assertEquals(1, success.closeCount.get());
        }

    }

    @Test
    void nonzeroExitAndStdoutReadFailureStillCloseExactlyOnce() throws Exception {
        for (ProbeProcess process : List.of(ProbeProcess.nonzero(), ProbeProcess.readFailure())) {
            {
                SessionControlSupport.ListeningPidResolver resolver = SessionControlSupport.listeningPidResolver(_ -> process);
                var pid = BlockingCalls.start(() -> resolver.resolve(9876));
                process.exited.complete(process);

                assertNull(pid.get());
                assertTrue(process.closeEntered.await(1, TimeUnit.SECONDS));
                assertEquals(1, process.closeCount.get());
            }
        }
    }

    @Test
    void portCloseFallbackAndProcessClassificationAreConservative() throws Exception {
        try (var harness = new BridgeTestHarness(McpJsonDefaults.getMapper(), new AppEnvironment(Map.of()), (_, request) -> RuntimeContractFixtures.status(request.id()))) {
            var support = new SessionControlSupport(harness.session(), new AppEnvironment(Map.of()), MonotonicTicker.system(), _ -> false, _ -> null);
            ClientExitResult result = support.waitForClientExit(9876, null, Duration.ofSeconds(2), Cancellation.none());
            assertEquals(new ClientExitResult.Exited(false), result);
        }
        assertTrue(SessionControlSupport.processAlive(ProcessHandle.current().pid()));
        assertFalse(SessionControlSupport.processAlive(Long.MAX_VALUE));
    }

    @Test
    void structuredBridgeDeadlinePublishesRecordedMismatches() throws Exception {
        AtomicReference<CompletableFuture<BridgeResponse>> response = new AtomicReference<>();
        var notes = new BlockingNoteList();
        try (var harness = new BridgeTestHarness(McpJsonDefaults.getMapper(), new AppEnvironment(Map.of()), (_, _) -> {
            CompletableFuture<BridgeResponse> pending = new CompletableFuture<>();
            response.set(pending);
            return pending.get();
        })) {
            var support = new SessionControlSupport(harness.session(), new AppEnvironment(Map.of()), MonotonicTicker.system(), _ -> false, _ -> null);
            var expected = new ExpectedInstance(Optional.of(new MinecraftVersion("different")), Optional.empty());
            var wait = BlockingCalls.start(() -> support.waitForBridge(expected, Duration.ofMillis(250), notes, Cancellation.none()));

            BlockingCalls.awaitUntil(() -> response.get() != null);
            CompletableFuture<BridgeResponse> ready = response.get();
            Thread mismatch = Thread.ofPlatform().start(() -> ready.complete(RuntimeContractFixtures.status("req_1")));
            assertTrue(notes.addEntered.await(1, TimeUnit.SECONDS));
            notes.releaseAdd.countDown();
            mismatch.join();
            assertTrue(wait.failure().getMessage().contains("Other instances answered: port 9876"));
            assertEquals(1, notes.size());
        }
    }

    @Test
    void scriptLoggerUsesNodePathsCompactJsonlStatsAndRotation(@TempDir Path temporary) throws Exception {
        var mapper = McpJsonDefaults.getMapper();
        var diagnostics = new ArrayList<String>();
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
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"), "Windows-local dataDirectory layout only verifiable on a Windows host");
        assertEquals(Path.of("C:\\Local\\mcdev-mcp\\Data"), ScriptLogger.dataDirectory("Windows 11", windows, Path.of("C:\\Home")));
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

    /**
     * A starter for a process the test goes on observing. The resolver owns whatever its starter
     * returns and is the one to close it.
     */
    private static SessionControlSupport.ProcessStarter starting(Process process) {
        return _ -> process;
    }

    private static SessionControlSupport.ProcessStarter announcing(ProbeProcess process) {
        return _ -> {
            process.started.countDown();
            return process;
        };
    }

    private static final class ProbeProcess extends Process {
        private final CompletableFuture<Process> exited = new CompletableFuture<>();
        private final AtomicBoolean stdoutConsumed = new AtomicBoolean();
        private final AtomicBoolean destroyed = new AtomicBoolean();
        private final CountDownLatch destroyedLatch = new CountDownLatch(1);
        private final AtomicInteger closeCount = new AtomicInteger();
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch probeEntered = new CountDownLatch(1);
        private final CountDownLatch closeFinished = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);
        private final int exitCode;
        private final boolean blockClose;
        private final InputStream stdout;

        private ProbeProcess(String output) {
            this(output, 0, false, false);
        }

        private ProbeProcess(String output, int exitCode, boolean blockClose, boolean readFailure) {
            this.exitCode = exitCode;
            this.blockClose = blockClose;
            stdout = readFailure ? new InputStream() {
                @Override
                public int read() throws IOException {
                    throw new IOException("stdout read failed");
                }
            } : new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
        }

        private static ProbeProcess blocking() {
            return new ProbeProcess("4242\n", 0, true, false);
        }

        private static ProbeProcess nonzero() {
            return new ProbeProcess("4242\n", 1, false, false);
        }

        private static ProbeProcess readFailure() {
            return new ProbeProcess("", 0, false, true);
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            probeEntered.countDown();
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            try {
                exited.get();
            } catch (ExecutionException exception) {
                throw new IllegalStateException(exception.getCause());
            }
            return exitCode;
        }

        @Override
        public int exitValue() {
            if (isAlive()) {
                throw new IllegalThreadStateException("process is still running");
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            terminate();
        }

        @Override
        public Process destroyForcibly() {
            terminate();
            return this;
        }

        private void terminate() {
            destroyed.set(true);
            destroyedLatch.countDown();
            exited.complete(this);
        }

        @Override
        public boolean isAlive() {
            return !exited.isDone();
        }

        @Override
        public void close() throws IOException {
            closeCount.incrementAndGet();
            closeEntered.countDown();
            if (blockClose) {
                boolean interrupted = false;
                try {
                    boolean waiting = true;
                    while (waiting) {
                        waiting = !releaseClose.await(100, TimeUnit.MILLISECONDS);
                    }
                } catch (InterruptedException exception) {
                    interrupted = true;
                    while (true) {
                        try {
                            releaseClose.await();
                            break;
                        } catch (InterruptedException ignored) {
                            while (Thread.interrupted()) {
                                Thread.yield();
                            }
                        }
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            stdoutConsumed.set(stdout.available() == 0);
            closeFinished.countDown();
            if (!blockClose && !stdoutConsumed.get()) {
                throw new IOException("stdout was not consumed before close");
            }
        }
    }

    private static final class TimeoutProcess extends Process {
        private final CountDownLatch finished = new CountDownLatch(1);
        private final CountDownLatch destroyed = new CountDownLatch(1);
        private final CountDownLatch stdoutRead = new CountDownLatch(1);
        private final CountDownLatch stderrRead = new CountDownLatch(1);

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            stdoutRead.countDown();
            return new ByteArrayInputStream("4242\n".getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public InputStream getErrorStream() {
            stderrRead.countDown();
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            try {
                finished.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return 1;
        }

        @Override
        public int exitValue() {
            if (finished.getCount() != 0) {
                throw new IllegalThreadStateException();
            }
            return 1;
        }

        @Override
        public void destroy() {
            terminate();
        }

        @Override
        public Process destroyForcibly() {
            terminate();
            return this;
        }

        private void terminate() {
            destroyed.countDown();
            finished.countDown();
        }

        @Override
        public boolean isAlive() {
            return finished.getCount() != 0;
        }
    }
}
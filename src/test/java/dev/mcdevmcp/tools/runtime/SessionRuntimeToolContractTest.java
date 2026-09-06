package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeRequest;
import dev.mcdevmcp.bridge.BridgeResponse;
import dev.mcdevmcp.bridge.BridgeTestHarness;
import dev.mcdevmcp.bridge.payload.EmptyBridgePayload;
import dev.mcdevmcp.bridge.payload.JoinServerPayload;
import dev.mcdevmcp.bridge.payload.RunCommandPayload;
import dev.mcdevmcp.mcp.tool.CountingMcpJsonMapper;
import dev.mcdevmcp.mcp.tool.CompleteToolBindings;
import dev.mcdevmcp.mcp.tool.ToolCatalog;
import dev.mcdevmcp.mcp.tool.api.ToolBinding;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SessionRuntimeToolContractTest {
    private static String contentText(ToolResult<?> result) {
        return assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst()).text();
    }

    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();
    private static final AppEnvironment DEFAULT_ENVIRONMENT = new AppEnvironment(Map.of());
    private static final List<String> SESSION_FIXTURE_LABELS = List.of("join_no_wait", "join_missing_result", "leave_success", "leave_missing_result", "command_strips_one_slash", "command_error");

    @Test
    void replaysTheFrozenSessionCorpusWithExactPayloadsTimeoutsAndText() throws Exception {
        List<RequestFixture> requests = RuntimeContractFixtures.load(MAPPER, "contracts/runtime-tools/session-requests.jsonl", RequestFixture.class);
        List<BridgeFixture> bridgeResponses = RuntimeContractFixtures.load(MAPPER, "contracts/runtime-tools/session-bridge-responses.jsonl", BridgeFixture.class);
        List<ResultFixture> results = RuntimeContractFixtures.load(MAPPER, "contracts/runtime-tools/session-tool-results.jsonl", ResultFixture.class);
        assertEquals(SESSION_FIXTURE_LABELS, requests.stream().map(RequestFixture::label).toList());
        assertEquals(SESSION_FIXTURE_LABELS, bridgeResponses.stream().map(BridgeFixture::label).toList());
        assertEquals(SESSION_FIXTURE_LABELS, results.stream().map(ResultFixture::label).toList());
        assertEquals(requests.size(), bridgeResponses.size());
        assertEquals(requests.size(), results.size());

        for (int index = 0; index < requests.size(); index++) {
            RequestFixture request = requests.get(index);
            BridgeFixture bridge = bridgeResponses.get(index);
            ResultFixture expected = results.get(index);
            assertEquals(request.label(), bridge.label());
            assertEquals(request.label(), expected.label());

            AppEnvironment environment = request.tool().equals("mc_run_command") ? new AppEnvironment(Map.of("MCDEV_RUN_COMMAND", "1")) : DEFAULT_ENVIRONMENT;
            try (var harness = new BridgeTestHarness(MAPPER, environment, (_, wireRequest) -> respond(request, bridge, wireRequest))) {
                ToolCatalog catalog = ToolCatalog.load(environment, CompleteToolBindings.including(MAPPER, RuntimeToolModule.handlers(harness.session(), MAPPER, environment)), MAPPER);
                ToolResult<?> actual = dispatch(catalog, request.tool(), request.arguments());

                assertEquals(expected.text(), contentText(actual), request.label());
                assertEquals(expected.isError(), actual.isError(), request.label());
                assertWireRequest(request, harness.requests());
                Duration targetTimeout = request.endpoint().equals("joinServer") ? Duration.ofSeconds(70) : Duration.ofSeconds(10);
                assertEquals(List.of(Duration.ofSeconds(10), targetTimeout), harness.effectiveTimeouts(), request.label());
            }
        }
    }

    @Test
    void rendersImmediateJoinedAndDisconnectedPollOutcomes() throws Exception {
        try (var joined = new BridgeTestHarness(MAPPER, DEFAULT_ENVIRONMENT, (_, request) -> switch (request.endpoint().wireName()) {
            case "status" -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id()));
            case "snapshot" -> CompletableFuture.completedFuture(success(request, Map.of("player", Map.of("x", 0))));
            default ->
                    CompletableFuture.failedFuture(new AssertionError("Unexpected endpoint: " + request.endpoint().wireName()));
        })) {
            ToolResult<?> result = dispatch(ToolCatalog.load(DEFAULT_ENVIRONMENT, CompleteToolBindings.including(MAPPER, RuntimeToolModule.handlers(joined.session(), MAPPER)), MAPPER), "mc_wait_until_in_world", Map.of());
            assertEquals("In-world after 0s.", contentText(result));
            assertFalse(result.isError());
            assertEquals(List.of("status", "snapshot"), joined.requests().stream().map(request -> request.endpoint().wireName()).toList());
            assertTrue(joined.requests().stream().allMatch(request -> request.payload().getClass() == EmptyBridgePayload.class));
        }

        try (var disconnected = new BridgeTestHarness(MAPPER, DEFAULT_ENVIRONMENT, (_, request) -> switch (request.endpoint().wireName()) {
            case "status" -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id()));
            case "snapshot" -> CompletableFuture.completedFuture(success(request, Map.of()));
            case "screenInspect" ->
                    CompletableFuture.completedFuture(success(request, Map.of("type", "net.minecraft.client.gui.screens.DisconnectedScreen", "title", "Connection refused")));
            default ->
                    CompletableFuture.failedFuture(new AssertionError("Unexpected endpoint: " + request.endpoint().wireName()));
        })) {
            ToolResult<?> result = dispatch(ToolCatalog.load(DEFAULT_ENVIRONMENT, CompleteToolBindings.including(MAPPER, RuntimeToolModule.handlers(disconnected.session(), MAPPER)), MAPPER), "mc_wait_until_in_world", Map.of());
            assertEquals("Join failed — DisconnectedScreen shown.\nReason: Connection refused", contentText(result));
            assertTrue(result.isError());
            assertEquals(List.of("status", "snapshot", "screenInspect"), disconnected.requests().stream().map(request -> request.endpoint().wireName()).toList());
            assertTrue(disconnected.requests().stream().allMatch(request -> request.payload().getClass() == EmptyBridgePayload.class));
        }
    }

    @Test
    void joinPreflightAndJoinDispatchUseTheProductionEmptyAndNamedPayloads() throws Exception {
        int[] snapshots = {0};
        try (var harness = new BridgeTestHarness(MAPPER, DEFAULT_ENVIRONMENT, (_, request) -> switch (request.endpoint().wireName()) {
            case "status" -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id()));
            case "snapshot" ->
                    CompletableFuture.completedFuture(success(request, snapshots[0]++ == 0 ? Map.of() : Map.of("player", Map.of("x", 0))));
            case "joinServer" -> CompletableFuture.completedFuture(success(request, Map.of("status", "connecting")));
            default ->
                    CompletableFuture.failedFuture(new AssertionError("Unexpected endpoint: " + request.endpoint().wireName()));
        })) {
            ToolCatalog catalog = ToolCatalog.load(DEFAULT_ENVIRONMENT, CompleteToolBindings.including(MAPPER, RuntimeToolModule.handlers(harness.session(), MAPPER)), MAPPER);
            ToolResult<?> result = dispatch(catalog, "mc_join_server", Map.of("address", "localhost:25565", "wait", true));

            assertEquals("Joined localhost:25565 — in-world after 0s.", contentText(result));
            assertFalse(result.isError());
            List<BridgeRequest> requests = harness.requests();
            assertEquals(List.of("status", "snapshot", "joinServer", "snapshot"), requests.stream().map(request -> request.endpoint().wireName()).toList());
            assertEquals(EmptyBridgePayload.class, requests.get(0).payload().getClass());
            assertEquals(EmptyBridgePayload.class, requests.get(1).payload().getClass());
            assertEquals(JoinServerPayload.class, requests.get(2).payload().getClass());
            assertEquals(EmptyBridgePayload.class, requests.get(3).payload().getClass());
        }
    }

    @Test
    void rejectsDisabledSessionControlBeforeCallingAGatedEndpoint() throws Exception {
        try (var harness = new BridgeTestHarness(MAPPER, DEFAULT_ENVIRONMENT, (_, request) -> CompletableFuture.completedFuture(disabledStatus(request)))) {
            ToolCatalog catalog = ToolCatalog.load(DEFAULT_ENVIRONMENT, CompleteToolBindings.including(MAPPER, RuntimeToolModule.handlers(harness.session(), MAPPER)), MAPPER);

            ToolResult<?> result = dispatch(catalog, "mc_join_server", Map.of("address", "localhost", "wait", false));

            assertTrue(result.isError());
            assertEquals("Session control is disabled in DebugBridge (session_control_enabled=false, the default).\nTo enable it: edit " + RuntimeContractFixtures.fixturePath("C:\\Game\\config\\debugbridge.json") + ", set \"session_control_enabled\": true, then restart the Minecraft client — the flag is only read at startup.", contentText(result));
            assertEquals(List.of("status"), harness.requests().stream().map(request -> request.endpoint().wireName()).toList());
        }
    }

    @Test
    void rejectsInvalidSessionInputsBeforeMapperConversionOrBridgeDispatch() {
        List<Map.Entry<String, Map<String, Object>>> invalid = List.of(Map.entry("mc_join_server", Map.of("address", "example.test", "timeoutSeconds", -1)), Map.entry("mc_join_server", mapOf("address", "example.test", "acceptResourcePacks", null)), Map.entry("mc_join_server", Map.of("address", "example.test", "wait", "false")), Map.entry("mc_join_server", Map.of("address", "example.test", "timeoutSeconds", Double.NaN)), Map.entry("mc_join_server", Map.of("address", "example.test", "unknown", true)), Map.entry("mc_wait_until_in_world", Map.of("timeoutSeconds", -0.1)), Map.entry("mc_wait_until_in_world", mapOf("requireAbsenceFirst", null)), Map.entry("mc_quit_client", Map.of("timeoutSeconds", "30")), Map.entry("mc_quit_client", mapOf("waitForExit", null)), Map.entry("mc_wait_for_bridge", Map.of("expectedVersion", "1.21.11", "timeoutSeconds", Double.POSITIVE_INFINITY)), Map.entry("mc_wait_for_bridge", mapOf("expectedVersion", null)));

        try (var harness = new BridgeTestHarness(MAPPER, DEFAULT_ENVIRONMENT, (_, _) -> CompletableFuture.failedFuture(new AssertionError("No bridge call should occur for invalid session input")))) {
            Map<String, ToolBinding<?>> bindings = RuntimeToolModule.handlers(harness.session(), MAPPER, DEFAULT_ENVIRONMENT);
            for (Map.Entry<String, Map<String, Object>> testCase : invalid) {
                CountingMcpJsonMapper mapper = new CountingMcpJsonMapper(MAPPER);
                assertThrows(IllegalArgumentException.class, () -> bindings.get(testCase.getKey()).invoke(mapper, testCase.getValue(), Cancellation.none()), testCase.getKey());
                assertEquals(0, mapper.convertValueCalls(), testCase.getKey());
            }
            assertEquals(List.of(), harness.requests());
        }
    }

    @Test
    void rejectsMissingAndDomainInvalidJoinAddressesWithoutBridgeDispatch() {
        try (var harness = new BridgeTestHarness(MAPPER, DEFAULT_ENVIRONMENT, (_, _) -> CompletableFuture.failedFuture(new AssertionError("No bridge call should occur for invalid join address")))) {
            ToolBinding<?> binding = RuntimeToolModule.handlers(harness.session(), MAPPER, DEFAULT_ENVIRONMENT).get("mc_join_server");
            List<Map.Entry<Map<String, Object>, Integer>> invalid = List.of(Map.entry(Map.of(), 0), Map.entry(Map.of("address", "[example.test]"), 1));
            for (Map.Entry<Map<String, Object>, Integer> testCase : invalid) {
                CountingMcpJsonMapper mapper = new CountingMcpJsonMapper(MAPPER);
                assertThrows(IllegalArgumentException.class, () -> binding.invoke(mapper, testCase.getKey(), Cancellation.none()));
                assertEquals(testCase.getValue(), mapper.convertValueCalls(), "join_server mapper conversion count");
            }
            assertEquals(List.of(), harness.requests());
        }
    }

    @Test
    void projectsTheValidatedAddressScalarAndPrimitiveResourcePackFlagAtTheBridgeBoundary() throws Exception {
        try (var harness = new BridgeTestHarness(MAPPER, DEFAULT_ENVIRONMENT, (_, request) -> switch (request.endpoint().wireName()) {
            case "status" -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id()));
            case "joinServer" -> CompletableFuture.completedFuture(success(request, Map.of("status", "connecting")));
            default ->
                    CompletableFuture.failedFuture(new AssertionError("Unexpected endpoint: " + request.endpoint().wireName()));
        })) {
            ToolCatalog catalog = ToolCatalog.load(DEFAULT_ENVIRONMENT, CompleteToolBindings.including(MAPPER, RuntimeToolModule.handlers(harness.session(), MAPPER)), MAPPER);
            ToolResult<?> result = dispatch(catalog, "mc_join_server", Map.of("address", "  example.test:25565  ", "acceptResourcePacks", false, "wait", false));

            assertFalse(result.isError());
            assertEquals(new JoinServerPayload("example.test:25565", false), harness.requests().getLast().payload());
        }
    }

    private static Map<String, Object> mapOf(Object... fields) {
        var map = new LinkedHashMap<String, Object>();
        for (int index = 0; index < fields.length; index += 2) {
            map.put((String) fields[index], fields[index + 1]);
        }
        return map;
    }

    @Test
    void reconnectsToTheFirstMatchingBridgeAndQueuesQuitWithoutWaiting() throws Exception {
        try (var bridge = new BridgeTestHarness(MAPPER, DEFAULT_ENVIRONMENT, (_, request) -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id())))) {
            ToolResult<?> result = dispatch(ToolCatalog.load(DEFAULT_ENVIRONMENT, CompleteToolBindings.including(MAPPER, RuntimeToolModule.handlers(bridge.session(), MAPPER)), MAPPER), "mc_wait_for_bridge", Map.of());
            assertEquals("Connected: Minecraft 1.21.11 on port 9876.\nGame dir: " + RuntimeContractFixtures.gameDirectory() + "\nSession control: enabled", contentText(result));
            assertEquals(List.of(9876, 9876), bridge.openedPorts());
            assertEquals(List.of("status", "status"), bridge.requests().stream().map(request -> request.endpoint().wireName()).toList());
        }

        try (var quitting = new BridgeTestHarness(MAPPER, DEFAULT_ENVIRONMENT, (_, request) -> switch (request.endpoint().wireName()) {
            case "status" -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id()));
            case "quit" -> CompletableFuture.completedFuture(success(request, Map.of("status", "quitting")));
            default ->
                    CompletableFuture.failedFuture(new AssertionError("Unexpected endpoint: " + request.endpoint().wireName()));
        })) {
            ToolResult<?> result = dispatch(ToolCatalog.load(DEFAULT_ENVIRONMENT, CompleteToolBindings.including(MAPPER, RuntimeToolModule.handlers(quitting.session(), MAPPER)), MAPPER), "mc_quit_client", Map.of("waitForExit", false));
            assertEquals("Quit queued — the client is shutting down. Use mc_wait_for_bridge after relaunching to reconnect.", contentText(result));
            assertFalse(result.isError());
            assertFalse(quitting.session().connectedPort().isPresent());
            assertEquals(List.of("status", "quit"), quitting.requests().stream().map(request -> request.endpoint().wireName()).toList());
            assertTrue(quitting.requests().stream().allMatch(request -> request.payload().getClass() == EmptyBridgePayload.class));
        }
    }

    @Test
    void appendsFiveSessionAndTwoAlwaysBoundDevHandlersWithEnvironmentGatedCatalogVisibility() throws Exception {
        try (var harness = new BridgeTestHarness(MAPPER, DEFAULT_ENVIRONMENT, (_, request) -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id())))) {
            Map<String, ?> handlers = RuntimeToolModule.handlers(harness.session(), MAPPER);
            List<String> names = List.copyOf(handlers.keySet());
            List<String> taskNames = List.of("mc_join_server", "mc_leave_server", "mc_wait_until_in_world", "mc_quit_client", "mc_wait_for_bridge", "mc_script_logs", "mc_run_command");
            assertEquals(25, names.size());
            assertEquals(taskNames, names.subList(names.size() - taskNames.size(), names.size()));
            assertDoesNotThrow(handlers::clear);

            ToolCatalog defaultCatalog = ToolCatalog.load(DEFAULT_ENVIRONMENT, CompleteToolBindings.including(MAPPER, RuntimeToolModule.handlers(harness.session(), MAPPER)), MAPPER);
            assertTrue(defaultCatalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_wait_for_bridge")));
            assertFalse(defaultCatalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_script_logs")));
            assertFalse(defaultCatalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_run_command")));
            assertEquals("Unknown tool: mc_run_command", contentText(dispatch(defaultCatalog, "mc_run_command", Map.of("command", "say hi"))));

            AppEnvironment devEnvironment = new AppEnvironment(Map.of("MCDEV_SESSION_LOG_DIR", Path.of(System.getProperty("java.io.tmpdir"), "mcdev-dev-session-logs").toString(), "MCDEV_RUN_COMMAND", "1"));
            ToolCatalog devCatalog = ToolCatalog.load(devEnvironment, CompleteToolBindings.including(MAPPER, RuntimeToolModule.handlers(harness.session(), MAPPER, devEnvironment)), MAPPER);
            assertTrue(devCatalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_script_logs")));
            assertTrue(devCatalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_run_command")));
        }
    }

    @Test
    void executePerformsNoLogIoUntilTheScriptLogGateIsOptedIn(@TempDir Path temporary) throws Exception {
        try (var harness = new BridgeTestHarness(MAPPER, DEFAULT_ENVIRONMENT, (_, request) -> switch (request.endpoint().wireName()) {
            case "status" -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id()));
            case "execute" -> CompletableFuture.completedFuture(success(request, 2));
            default ->
                    CompletableFuture.failedFuture(new AssertionError("Unexpected endpoint: " + request.endpoint().wireName()));
        })) {
            // Session logging is opt-in. Without MCDEV_SESSION_LOG_DIR, the logger is not
            // constructed, and no script-log files are written anywhere.
            AppEnvironment disabled = new AppEnvironment(Map.of());
            ToolCatalog disabledCatalog = ToolCatalog.load(disabled, CompleteToolBindings.including(MAPPER, RuntimeToolModule.handlers(harness.session(), MAPPER, disabled)), MAPPER);
            assertEquals("2", contentText(dispatch(disabledCatalog, "mc_execute", Map.of("code", "return 2"))));
            try (var files = java.nio.file.Files.walk(temporary)) {
                assertTrue(files.noneMatch(path -> path.getFileName().toString().equals("script-logs")));
            }

            AppEnvironment enabled = new AppEnvironment(Map.of("MCDEV_SESSION_LOG_DIR", temporary.resolve("enabled").toString()));
            ToolCatalog enabledCatalog = ToolCatalog.load(enabled, CompleteToolBindings.including(MAPPER, RuntimeToolModule.handlers(harness.session(), MAPPER, enabled)), MAPPER);
            assertEquals("2", contentText(dispatch(enabledCatalog, "mc_execute", Map.of("code", "return 2"))));
            Path log = temporary.resolve("enabled").resolve("script-logs").resolve("all.jsonl");
            assertTrue(Files.exists(log));
            assertTrue(Files.readString(log).contains("\"code\":\"return 2\""));
            ToolResult<?> paths = dispatch(enabledCatalog, "mc_script_logs", Map.of("mode", "paths"));
            assertExactText(expectedPaths(log.getParent()), paths);
        }
    }

    @Test
    void executeDurationUsesMonotonicTickerWhileLogTimestampUsesWallSource(@TempDir Path temporary) throws Exception {
        try (var harness = new BridgeTestHarness(MAPPER, DEFAULT_ENVIRONMENT, (_, request) -> switch (request.endpoint().wireName()) {
            case "status" -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id()));
            case "execute" -> CompletableFuture.completedFuture(success(request, 2));
            default ->
                    CompletableFuture.failedFuture(new AssertionError("Unexpected endpoint: " + request.endpoint().wireName()));
        })) {
            harness.session().connect(null).toCompletableFuture().get(5, TimeUnit.SECONDS);
            var ticker = new SequenceTicker(10L, 1_250_000_010L);
            var support = new RuntimeToolSupport(harness.session(), MAPPER, ticker);
            Instant timestamp = Instant.parse("2026-09-03T00:00:00Z");
            var logger = new ScriptLogger(temporary, MAPPER, ignored -> {
            }, () -> false, InstantSource.fixed(timestamp));

            ToolResult<?> result = support.execute(new ExecuteArguments("return 2", Duration.ofSeconds(10)), logger, true).toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals("2", contentText(result));
            assertTrue(Files.readString(logger.allLogPath()).contains("\"duration_ms\":1250"));
            assertTrue(Files.readString(logger.allLogPath()).contains(timestamp.toString()));
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

    @Test
    void scriptLogsPreserveExactDisabledErrorsStatsAndPathsText(@TempDir Path temporary) throws Exception {
        ToolBinding<?> disabledBinding = McScriptLogsTool.binding(null);
        CountingMcpJsonMapper disabledMapper = new CountingMcpJsonMapper(MAPPER);
        ToolResult<?> disabled = disabledBinding.invoke(disabledMapper, Map.of(), Cancellation.none()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertExactText("Session logging is disabled. Set MCDEV_SCRIPT_LOGS=1 or MCDEV_SESSION_LOG_DIR to enable it.", disabled);
        assertEquals(1, disabledMapper.convertValueCalls());

        ScriptLogger logger = new ScriptLogger(temporary, MAPPER, ignored -> {
        }, () -> false, InstantSource.fixed(Instant.ofEpochMilli(1_700_000_000_000L)));
        Instant timestamp = Instant.parse("2026-09-03T00:00:00Z");
        logger.log(new ScriptLogger.ScriptLogEntry(timestamp, false, "return 1", false, null, null, "boom", Duration.ofMillis(12)), false);
        ToolBinding<?> binding = McScriptLogsTool.binding(logger);

        CountingMcpJsonMapper errorsMapper = new CountingMcpJsonMapper(MAPPER);
        ToolResult<?> errors = binding.invoke(errorsMapper, Map.of("mode", "errors", "limit", 20), Cancellation.none()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertExactText("Recent Script Errors (1 entries):\n\n---\n**2026-09-03T00:00:00Z** (12ms)\nError: boom\n```groovy\nreturn 1\n```\n\n", errors);
        assertEquals(1, errorsMapper.convertValueCalls());

        CountingMcpJsonMapper statsMapper = new CountingMcpJsonMapper(MAPPER);
        ToolResult<?> stats = binding.invoke(statsMapper, Map.of("mode", "stats", "limit", 20), Cancellation.none()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertExactText("Error Statistics (1 distinct error types):\n\n## boom\n   Count: 1 | Last seen: 2026-09-03T00:00:00Z\n   Example script:\n   ```groovy\n   return 1\n   ```\n\n", stats);
        assertEquals(1, statsMapper.convertValueCalls());

        CountingMcpJsonMapper pathsMapper = new CountingMcpJsonMapper(MAPPER);
        ToolResult<?> paths = binding.invoke(pathsMapper, Map.of("mode", "paths", "limit", 20), Cancellation.none()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertExactText(expectedPaths(temporary.resolve("script-logs")), paths);
        assertEquals(1, pathsMapper.convertValueCalls());
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    void retainedScriptLogSwitchWritesJsonlToThePlatformDefaultAndExplicitDirectoryTakesPrecedence(@TempDir Path temporary) throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", temporary.resolve("home").toString());
        try {
            var values = new LinkedHashMap<String, String>();
            values.put("MCDEV_SCRIPT_LOGS", "1");
            values.put("LOCALAPPDATA", temporary.resolve("local-app-data").toString());
            values.put("XDG_DATA_HOME", temporary.resolve("xdg-data").toString());
            AppEnvironment environment = new AppEnvironment(values);
            Path dataDirectory = ScriptLogger.dataDirectory(System.getProperty("os.name"), environment, Path.of(System.getProperty("user.home")));
            Path defaultLog = dataDirectory.resolve("script-logs").resolve("all.jsonl");

            try (var harness = new BridgeTestHarness(MAPPER, environment, (_, request) -> switch (request.endpoint().wireName()) {
                case "status" -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id()));
                case "execute" -> CompletableFuture.completedFuture(success(request, 2));
                default ->
                        CompletableFuture.failedFuture(new AssertionError("Unexpected endpoint: " + request.endpoint().wireName()));
            })) {
                ToolCatalog catalog = ToolCatalog.load(environment, CompleteToolBindings.including(MAPPER, RuntimeToolModule.handlers(harness.session(), MAPPER, environment)), MAPPER);

                assertEquals("2", contentText(dispatch(catalog, "mc_execute", Map.of("code", "return 2"))));
                assertTrue(Files.exists(defaultLog));
                assertTrue(Files.readString(defaultLog).contains("\"code\":\"return 2\""));
                assertTrue(catalog.enabledDefinitions().stream().anyMatch(tool -> tool.name().equals("mc_script_logs")));
            }

            long defaultLogSize = Files.size(defaultLog);
            Path explicitDirectory = temporary.resolve("explicit-session-data");
            values.put("MCDEV_SESSION_LOG_DIR", explicitDirectory.toString());
            AppEnvironment explicitEnvironment = new AppEnvironment(values);
            try (var harness = new BridgeTestHarness(MAPPER, explicitEnvironment, (_, request) -> switch (request.endpoint().wireName()) {
                case "status" -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id()));
                case "execute" -> CompletableFuture.completedFuture(success(request, 3));
                default ->
                        CompletableFuture.failedFuture(new AssertionError("Unexpected endpoint: " + request.endpoint().wireName()));
            })) {
                ToolCatalog catalog = ToolCatalog.load(explicitEnvironment, CompleteToolBindings.including(MAPPER, RuntimeToolModule.handlers(harness.session(), MAPPER, explicitEnvironment)), MAPPER);

                assertEquals("3", contentText(dispatch(catalog, "mc_execute", Map.of("code", "return 3"))));
                Path explicitLog = explicitDirectory.resolve("script-logs").resolve("all.jsonl");
                assertTrue(Files.readString(explicitLog).contains("\"code\":\"return 3\""));
                assertEquals(defaultLogSize, Files.size(defaultLog));
            }
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            }
            else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    private static CompletableFuture<BridgeResponse> respond(RequestFixture request, BridgeFixture bridge, BridgeRequest wireRequest) {
        if (wireRequest.endpoint().wireName().equals("status")) {
            return CompletableFuture.completedFuture(RuntimeContractFixtures.status(wireRequest.id()));
        }
        if (!request.endpoint().equals(wireRequest.endpoint().wireName())) {
            return CompletableFuture.failedFuture(new AssertionError("Unexpected endpoint for " + request.label() + ": " + wireRequest.endpoint().wireName()));
        }
        return CompletableFuture.completedFuture(new BridgeResponse(wireRequest.id(), bridge.success(), bridge.resultPresent(), bridge.result(), bridge.output(), bridge.error()));
    }

    private static BridgeResponse success(BridgeRequest request, Object result) {
        return new BridgeResponse(request.id(), true, true, result, null, null);
    }

    private static BridgeResponse disabledStatus(BridgeRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", "1.21.11");
        result.put("mappingStatus", "mojang");
        result.put("obfuscated", true);
        result.put("refs", 0);
        result.put("gameDir", RuntimeContractFixtures.gameDirectory());
        result.put("sessionControlEnabled", false);
        return new BridgeResponse(request.id(), true, true, result, null, null);
    }

    private static ToolResult<?> dispatch(ToolCatalog catalog, String tool, Map<String, Object> arguments) throws Exception {
        return catalog.dispatch(tool, arguments, Cancellation.none()).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static String expectedPaths(Path logDirectory) {
        return "Script log files:\n" + "  All executions: " + logDirectory.resolve("all.jsonl") + "\n" + "  Errors only:    " + logDirectory.resolve("errors.jsonl") + "\n" + "  Log directory:  " + logDirectory + "\n\n" + "Use the Read tool to view these files. Format: JSON Lines (one JSON object per line).";
    }

    private static void assertExactText(String expected, ToolResult<?> actual) {
        assertFalse(actual.isError());
        assertEquals(1, actual.content().size());
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), contentText(actual).getBytes(StandardCharsets.UTF_8));
    }

    private static void assertWireRequest(RequestFixture fixture, List<BridgeRequest> actual) throws IOException {
        assertEquals(2, actual.size(), fixture.label());
        assertEquals("status", actual.getFirst().endpoint().wireName(), fixture.label());
        assertEquals(EmptyBridgePayload.class, actual.getFirst().payload().getClass(), fixture.label());
        BridgeRequest target = actual.getLast();
        assertEquals(fixture.endpoint(), target.endpoint().wireName(), fixture.label());
        assertEquals(expectedPayloadType(fixture.endpoint()), target.payload().getClass(), fixture.label());
        assertEquals(MAPPER.writeValueAsString(fixture.payload()), MAPPER.writeValueAsString(target.payload()), fixture.label());
    }

    private static Class<?> expectedPayloadType(String endpoint) {
        return switch (endpoint) {
            case "disconnect", "quit" -> EmptyBridgePayload.class;
            case "joinServer" -> JoinServerPayload.class;
            case "runCommand" -> RunCommandPayload.class;
            default -> throw new AssertionError("Unhandled session payload endpoint: " + endpoint);
        };
    }

    private record RequestFixture(String label, String tool, Map<String, Object> arguments, String endpoint, Map<String, Object> payload) {
    }

    private record BridgeFixture(String label, boolean success, boolean resultPresent, Object result, String output, String error) {
    }

    private record ResultFixture(String label, String text, boolean isError) {
    }
}

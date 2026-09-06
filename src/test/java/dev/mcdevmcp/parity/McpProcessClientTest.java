package dev.mcdevmcp.parity;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("parity")
@Timeout(15)
class McpProcessClientTest {
    @Test
    void matchesAnAutomaticallyNumberedResponse() throws Exception {
        try (var client = startFixture("echo")) {
            Map<String, Object> response = client.request("fixture/echo", Map.of());

            assertEquals(1, ((Number) response.get("id")).intValue());
            assertEquals(Map.of("method", "fixture/echo"), response.get("result"));
        }
    }

    @Test
    void matchesAStringResponseIdWithoutCoercion() throws Exception {
        try (var client = startFixture("echo")) {
            Map<String, Object> response = client.request(request("string-id", "fixture/echo"));

            assertEquals("string-id", response.get("id"));
            assertEquals(Map.of("method", "fixture/echo"), response.get("result"));
        }
    }

    @Test
    void rejectsAResponseForARequestThatWasNeverSent() throws Exception {
        assertProtocolRejected("mismatched", "unmatched JSON-RPC response id later");
    }

    @Test
    void recordsServerNotificationsBeforeTheRequestedResponse() throws Exception {
        try (var client = startFixture("notification")) {
            Map<String, Object> response = client.request(request(7, "fixture/notification"));
            var notifications = client.awaitQuiescence();

            assertEquals(7, ((Number) response.get("id")).intValue());
            assertEquals(Map.of("notificationIgnored", true), response.get("result"));
            assertEquals(1, notifications.size());
            assertEquals("notifications/progress", notifications.getFirst().get("method"));
            assertEquals(notifications, client.notifications());
        }
    }

    @Test
    void rejectsAnUnmatchedResponseAtTheProtocolBoundary() throws Exception {
        try (var client = startFixture("extra-response")) {
            Map<String, Object> response = client.request(request(7, "fixture/extra"));

            assertEquals(Map.of("expected", true), response.get("result"));
            IOException failure = assertThrows(IOException.class, client::awaitQuiescence);
            assertTrue(failure.getMessage().contains("unmatched JSON-RPC response id unexpected"), failure::getMessage);
        }
    }

    @Test
    void closeRejectsUnconsumedProtocolOutput() throws Exception {
        try (var client = startFixture("extra-response")) {
            client.request(request(7, "fixture/extra"));

            IOException failure = assertThrows(IOException.class, client::close);

            assertTrue(failure.getMessage().contains("unmatched JSON-RPC response id unexpected"), failure::getMessage);
        }
    }

    @Test
    void rejectsNonJsonStdoutAndRetainsSeparateStderrDiagnostics() throws Exception {
        var client = startFixture("malformed-json");
        IOException failure = assertThrows(IOException.class, () -> client.request("fixture/malformed", Map.of()));

        assertTrue(failure.getMessage().contains("non-JSON NDJSON output"), failure::getMessage);
        assertTrue(client.stderr().contains("fixture diagnostic"), client::stderr);
        client.close();
    }

    @Test
    void rejectsJsonRpcOutputWithoutAnIdOrNotificationShape() throws Exception {
        try (var client = startFixture("invalid-message")) {
            IOException failure = assertThrows(IOException.class, () -> client.request("fixture/invalid", Map.of()));

            assertTrue(failure.getMessage().contains("malformed JSON-RPC notification envelope"), failure::getMessage);
        }
    }

    @Test
    void requiresEveryIncomingMessageToDeclareJsonRpcTwoExactly() throws Exception {
        assertProtocolRejected("invalid-jsonrpc", "jsonrpc exactly 2.0");
    }

    @Test
    void rejectsTopLevelJsonNull() throws Exception {
        assertProtocolRejected("top-level-null", "top-level JSON null");
    }

    @Test
    void rejectsServerRequestsInsteadOfTreatingThemAsResponses() throws Exception {
        assertProtocolRejected("server-request", "unsupported JSON-RPC server request");
    }

    @Test
    void requiresExactlyOneResponsePayloadAndNoMethod() throws Exception {
        assertProtocolRejected("response-both", "exactly one of result or error");
        assertProtocolRejected("response-neither", "exactly one of result or error");
        assertProtocolRejected("response-method", "unsupported JSON-RPC server request");
    }

    @Test
    void rejectsMalformedErrorObjectsAndNullResponseIds() throws Exception {
        assertProtocolRejected("malformed-error", "malformed JSON-RPC error object");
        assertProtocolRejected("null-id", "JSON-RPC id must be a string or number");
    }

    @Test
    void rejectsDuplicateResponsesEvenAfterTheFirstWasConsumed() throws Exception {
        try (var client = startFixture("duplicate-response")) {
            Map<String, Object> response = client.request(request(7, "fixture/duplicate"));

            assertEquals(Map.of("first", true), response.get("result"));
            IOException failure = assertThrows(IOException.class, client::awaitQuiescence);
            assertTrue(failure.getMessage().contains("duplicate JSON-RPC response id 7"), failure::getMessage);
        }
    }

    @Test
    void reportsCleanEndOfStreamBeforeAResponse() throws Exception {
        try (var client = startFixture("eof")) {
            IOException failure = assertThrows(IOException.class, () -> client.request("fixture/eof", Map.of()));

            assertTrue(failure.getMessage().contains("closed STDOUT before responding"), failure::getMessage);
        }
    }

    @Test
    void timesOutAndClosesAResponsiveButSilentProcess() throws Exception {
        try (var client = startTimeoutFixture(Duration.ofMillis(100))) {
            IOException failure = assertThrows(IOException.class, () -> client.request("fixture/timeout", Map.of()));

            assertTrue(failure.getMessage().contains("Timed out after PT0.1S"), failure::getMessage);
            IOException closed = assertThrows(IOException.class, () -> client.request("fixture/after-timeout", Map.of()));
            assertEquals("MCP process client is closed", closed.getMessage());
        }
    }

    @Test
    void cleanupTerminatesTrackedDescendantsAfterTheRootExitsFirst(@TempDir Path temporaryDirectory) throws Exception {
        Path childPidFile = temporaryDirectory.resolve("child.pid");
        var client = startClientTreeFixture(childPidFile);

        IOException failure = assertThrows(IOException.class, () -> client.request("fixture/tree", Map.of()));

        assertTrue(failure.getMessage().contains("exited before responding"), failure::getMessage);
        long childPid = Long.parseLong(Files.readString(childPidFile, StandardCharsets.UTF_8));
        assertFalse(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false), "MCP cleanup left a tracked descendant alive after the root exited");
        client.close();
    }

    @Test
    void closeIsIdempotentAndRejectsFurtherRequests() throws Exception {
        var client = startFixture("close");

        client.close();
        client.close();

        IOException failure = assertThrows(IOException.class, () -> client.request("fixture/closed", Map.of()));
        assertEquals("MCP process client is closed", failure.getMessage());
    }

    @Test
    void explicitlyAllowedForcedShutdownStillCleansUpTheProcess() throws Exception {
        var client = McpProcessClient.startAllowingForcedShutdown(fixtureBuilder("forced-shutdown"));
        assertTrue(client.request("fixture/ready", Map.of()).containsKey("result"));

        client.close();
    }

    @Test
    void forcedShutdownAllowanceDoesNotSuppressASpontaneousNonzeroExit() throws Exception {
        try (var client = McpProcessClient.startAllowingForcedShutdown(fixtureBuilder("spontaneous-nonzero"))) {
            assertTrue(client.request("fixture/ready", Map.of()).containsKey("result"));

            IOException failure = assertThrows(IOException.class, client::close);

            assertTrue(failure.getMessage().contains("exited with code 23"), failure::getMessage);
        }
    }

    @Test
    void rejectsMergedOrRedirectedProtocolStreamsBeforeStarting() {
        ProcessBuilder merged = fixtureBuilder("close").redirectErrorStream(true);
        ProcessBuilder redirectedOutput = fixtureBuilder("close").redirectOutput(ProcessBuilder.Redirect.INHERIT);

        assertStartRejected(merged);
        assertStartRejected(redirectedOutput);
    }

    @Test
    void validatesTheTimeoutBeforeStartingTheProcess() {
        var missingExecutable = new ProcessBuilder("mcdev-parity-command-that-does-not-exist");

        assertThrows(IllegalArgumentException.class, () -> {
            try (McpProcessClient ignored = McpProcessClient.start(missingExecutable, Duration.ZERO)) {
                throw new AssertionError("Invalid timeout unexpectedly started a process: " + ignored);
            }
        });
    }

    private static McpProcessClient startFixture(String mode) throws IOException {
        return McpProcessClient.start(fixtureBuilder(mode));
    }

    private static McpProcessClient startClientTreeFixture(Path childPidFile) throws IOException {
        return McpProcessClient.start(fixtureBuilder("client-tree-root-exits", childPidFile.toString()));
    }

    private static McpProcessClient startTimeoutFixture(Duration timeout) throws IOException {
        return McpProcessClient.start(fixtureBuilder("timeout"), timeout);
    }

    private static void assertStartRejected(ProcessBuilder builder) {
        assertThrows(IllegalArgumentException.class, () -> {
            try (McpProcessClient ignored = McpProcessClient.start(builder)) {
                throw new AssertionError("Invalid process builder unexpectedly started: " + ignored);
            }
        });
    }

    private static ProcessBuilder fixtureBuilder(String mode) {
        return fixtureBuilder(mode, new String[0]);
    }

    private static ProcessBuilder fixtureBuilder(String mode, String... arguments) {
        String configuredJava = System.getProperty("mcdevMcpJava");
        String java = configuredJava == null || configuredJava.isBlank() ? ProcessHandle.current().info().command().orElseThrow() : configuredJava;
        var command = new ArrayList<String>(arguments.length + 5);
        command.add(java);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(McpProcessFixtureMain.class.getName());
        command.add(mode);
        command.addAll(List.of(arguments));
        return new ProcessBuilder(command);
    }

    private static void assertProtocolRejected(String mode, String expectedMessage) throws Exception {
        try (var client = startFixture(mode)) {
            IOException failure = assertThrows(IOException.class, () -> client.request("fixture/protocol", Map.of()));

            assertTrue(failure.getMessage().contains(expectedMessage), failure::getMessage);
        }
    }

    private static Map<String, Object> request(Object id, String method) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", Map.of());
        return request;
    }
}

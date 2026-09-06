package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeRequest;
import dev.mcdevmcp.bridge.BridgeResponse;
import dev.mcdevmcp.bridge.BridgeTestHarness;
import dev.mcdevmcp.bridge.payload.EmptyBridgePayload;
import dev.mcdevmcp.mcp.tool.CompleteToolBindings;
import dev.mcdevmcp.mcp.tool.ToolCatalog;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.support.AppEnvironment;
import dev.mcdevmcp.support.Cancellation;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MediaRuntimeToolContractTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();
    private static final AppEnvironment ENVIRONMENT = new AppEnvironment(Map.of());
    private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() {
    };
    private static final List<String> MEDIA_FIXTURE_LABELS = List.of("screenshot-success", "screenshot-floating-integral-token", "screenshot-declared-error", "screenshot-missing-result", "screenshot-wrong-primitive", "record-grid-tagged-fixed-seconds", "record-frames-dropped", "record-unknown-mode", "record-malformed-paths", "record-declared-error", "item-texture-success", "item-texture-wrong-primitive", "entity-item-texture-success", "item-texture-by-id-success", "entity-glow-success", "entity-glow-declared-error", "block-glow-success", "clear-block-glow-success", "clear-block-glow-missing-result");

    @Test
    void replaysTheFrozenMediaCorpusWithExactPayloadsTimeoutsAndContent() throws Exception {
        List<RequestFixture> requests = RuntimeContractFixtures.load(MAPPER, "contracts/runtime-tools/media-requests.jsonl", RequestFixture.class);
        List<BridgeFixture> bridgeResponses = RuntimeContractFixtures.load(MAPPER, "contracts/runtime-tools/media-bridge-responses.jsonl", BridgeFixture.class);
        List<ResultFixture> results = RuntimeContractFixtures.load(MAPPER, "contracts/runtime-tools/media-tool-results.jsonl", ResultFixture.class);
        assertEquals(requests.size(), bridgeResponses.size());
        assertEquals(requests.size(), results.size());

        for (int index = 0; index < requests.size(); index++) {
            RequestFixture request = requests.get(index);
            BridgeFixture bridge = bridgeResponses.get(index);
            ResultFixture expected = results.get(index);
            assertEquals(request.label(), bridge.label(), "bridge fixture " + index);
            assertEquals(request.label(), expected.label(), "result fixture " + index);

            try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, wireRequest) -> respond(request, bridge, wireRequest))) {
                ToolCatalog catalog = ToolCatalog.load(ENVIRONMENT, CompleteToolBindings.including(MAPPER, RuntimeToolModule.handlers(harness.session(), MAPPER)), MAPPER);
                ToolResult<?> actual = dispatch(catalog, request.tool(), request.arguments());

                assertContent(expected, actual);
                assertWireRequest(request, harness.requests());
                assertEquals(expectedEffectiveTimeouts(request), harness.effectiveTimeouts(), request.label());
                assertEquals(List.of(9876), harness.openedPorts(), request.label());
            }
        }
    }

    @Test
    void mediaFixturesAreStrictJsonLinesWithStableDocumentsAndLabels() throws Exception {
        for (String resource : List.of("contracts/runtime-tools/media-requests.jsonl", "contracts/runtime-tools/media-bridge-responses.jsonl", "contracts/runtime-tools/media-tool-results.jsonl")) {
            List<Map<String, Object>> documents = readStrictJsonLines(resource);
            assertEquals(MEDIA_FIXTURE_LABELS, documents.stream().map(document -> document.get("label")).map(String.class::cast).toList(), resource);
        }
    }

    @Test
    void rejectsOversizedTextureBeforeCreatingMcpImageContent() throws Exception {
        String oversized = "a".repeat(MediaToolSupport.MAX_BASE64_PNG_BYTES + 1);
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, request) -> {
            if (request.endpoint().wireName().equals("status")) {
                return CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id()));
            }
            return CompletableFuture.completedFuture(new BridgeResponse(request.id(), true, true, Map.of("base64Png", oversized, "width", 16, "height", 16, "spriteName", "minecraft:item/diamond"), null, null));
        })) {
            ToolCatalog catalog = ToolCatalog.load(ENVIRONMENT, CompleteToolBindings.including(MAPPER, RuntimeToolModule.handlers(harness.session(), MAPPER)), MAPPER);

            ToolResult<?> result = dispatch(catalog, "mc_get_item_texture", Map.of("slot", 0));

            assertTrue(result.isError());
            assertEquals("Bridge 'getItemTexture' returned a 7.0 MB base64 PNG, exceeding the 7.0 MB cap. This usually means a malformed bridge response — please report it.", ((McpSchema.TextContent) result.content().getFirst()).text());
            assertEquals(1, result.content().size());
            assertInstanceOf(McpSchema.TextContent.class, result.content().getFirst());
        }
    }

    @Test
    void acceptsTaggedIntervalsAndScalesRecordingDeadlinesLikeTheNodeOracle() {
        var fixed = new RecordInterval.Fixed(Duration.ofMillis(100));
        var frame = new RecordInterval.Frame();
        assertThrows(IllegalArgumentException.class, () -> new RecordInterval.Fixed(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new RecordInterval.Fixed(Duration.ofNanos(999_999)));
        assertEquals(Duration.ofMillis(100), fixed.intervalSeconds());
        assertEquals(1.5, RecordInterval.projectedMillis(Duration.ofNanos(1_500_000)));
        assertEquals(Duration.ofMillis(25_000), MediaToolSupport.recordingDeadline(100, fixed));
        assertEquals(Duration.ofMillis(20_100), MediaToolSupport.recordingDeadline(300, frame));
        assertEquals(Duration.ofMillis(15_153), MediaToolSupport.recordingDeadline(9, null));
        assertEquals(Duration.ofMillis(15_170), MediaToolSupport.recordingDeadline(10, frame));
        assertEquals(Duration.ofMillis(Long.MAX_VALUE), MediaToolSupport.recordingDeadline(300, new RecordInterval.Fixed(Duration.ofSeconds(Long.MAX_VALUE))));
    }

    @Test
    void appendsExactlyEightMutableMediaBindingsAfterTheCoreGroup() {
        try (var harness = new BridgeTestHarness(MAPPER, ENVIRONMENT, (_, request) -> CompletableFuture.completedFuture(RuntimeContractFixtures.status(request.id())))) {
            Map<String, ?> handlers = RuntimeToolModule.handlers(harness.session(), MAPPER);
            List<String> names = List.copyOf(handlers.keySet());
            List<String> mediaNames = List.of("mc_screenshot", "mc_record_video", "mc_get_item_texture", "mc_get_entity_item_texture", "mc_get_item_texture_by_id", "mc_set_entity_glow", "mc_set_block_glow", "mc_clear_block_glow");

            assertEquals(25, names.size());
            assertEquals(mediaNames, names.subList(10, 10 + mediaNames.size()));
            assertDoesNotThrow(handlers::clear);
        }
    }

    private static ToolResult<?> dispatch(ToolCatalog catalog, String tool, Map<String, Object> arguments) throws Exception {
        return catalog.dispatch(tool, arguments, Cancellation.none()).toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static List<Map<String, Object>> readStrictJsonLines(String resource) throws IOException {
        var stream = MediaRuntimeToolContractTest.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(stream, "Missing fixture " + resource);
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            var documents = new java.util.ArrayList<Map<String, Object>>();
            String line;
            while ((line = reader.readLine()) != null) {
                assertFalse(line.isBlank(), "Blank JSONL line in " + resource);
                assertEquals(line.strip(), line, "JSONL line has surrounding whitespace in " + resource);
                assertTrue(line.startsWith("{") && line.endsWith("}"), "Each JSONL line must be one object in " + resource);
                documents.add(MAPPER.readValue(line, MAP_TYPE));
            }
            assertEquals(MEDIA_FIXTURE_LABELS.size(), documents.size(), resource);
            return List.copyOf(documents);
        }
    }

    private static CompletableFuture<BridgeResponse> respond(RequestFixture request, BridgeFixture bridge, BridgeRequest wireRequest) {
        String endpoint = wireRequest.endpoint().wireName();
        if (endpoint.equals("status")) {
            return CompletableFuture.completedFuture(RuntimeContractFixtures.status(wireRequest.id()));
        }
        if (!request.endpoint().equals(endpoint)) {
            return CompletableFuture.failedFuture(new IllegalStateException("Unexpected endpoint " + endpoint + " for " + request.label()));
        }
        return CompletableFuture.completedFuture(new BridgeResponse(wireRequest.id(), Boolean.TRUE.equals(bridge.success()), Boolean.TRUE.equals(bridge.resultPresent()), RuntimeContractFixtures.nativeResult(bridge.result()), bridge.output(), bridge.error()));
    }

    private static void assertWireRequest(RequestFixture fixture, List<BridgeRequest> actual) {
        assertEquals(2, actual.size(), fixture.label());
        assertEquals("status", actual.getFirst().endpoint().wireName(), fixture.label());
        assertEquals(new EmptyBridgePayload(), actual.getFirst().payload(), fixture.label());
        assertEquals(fixture.endpoint(), actual.getLast().endpoint().wireName(), fixture.label());
        assertEquals(writeJson(fixture.payload()), writeJson(actual.getLast().payload()), fixture.label());
    }

    private static String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException exception) {
            throw new AssertionError("Unable to serialize contract payload", exception);
        }
    }

    private static List<Duration> expectedEffectiveTimeouts(RequestFixture fixture) {
        Duration target = Duration.ofSeconds(10);
        if (fixture.endpoint().equals("record_video")) {
            RecordInterval interval = fixture.arguments().get("interval") == null ? null : MAPPER.convertValue(fixture.arguments().get("interval"), RecordInterval.class);
            int frames = ((Number) fixture.arguments().get("frames")).intValue();
            Duration requested = MediaToolSupport.recordingDeadline(frames, interval);
            Duration extended = requested.plusSeconds(5);
            target = extended.compareTo(Duration.ofMinutes(5)) > 0 ? Duration.ofMinutes(5) : extended;
        }
        return List.of(Duration.ofSeconds(10), target);
    }

    private static void assertContent(ResultFixture expected, ToolResult<?> actual) {
        assertEquals(expected.isError(), actual.isError(), expected.label());
        assertEquals(expected.content().size(), actual.content().size(), expected.label());
        for (int index = 0; index < expected.content().size(); index++) {
            ContentFixture wanted = expected.content().get(index);
            McpSchema.Content observed = actual.content().get(index);
            switch (wanted.type()) {
                case "TEXT" -> {
                    var text = assertInstanceOf(McpSchema.TextContent.class, observed, expected.label() + " content " + index);
                    if (List.of("screenshot-wrong-primitive", "record-unknown-mode", "record-malformed-paths", "item-texture-wrong-primitive").contains(expected.label())) {
                        assertTrue(text.text().contains("response has invalid result"), expected.label() + " content " + index);
                    }
                    else {
                        assertEquals(RuntimeContractFixtures.fixturePath(wanted.text()), text.text(), expected.label() + " content " + index);
                    }
                }
                case "IMAGE" -> {
                    var image = assertInstanceOf(McpSchema.ImageContent.class, observed, expected.label() + " content " + index);
                    assertEquals(wanted.mimeType(), image.mimeType(), expected.label() + " content " + index);
                    assertEquals(wanted.data(), image.data(), expected.label() + " content " + index);
                }
                case "AUDIO" -> {
                    var audio = assertInstanceOf(McpSchema.AudioContent.class, observed, expected.label() + " content " + index);
                    assertEquals(wanted.mimeType(), audio.mimeType(), expected.label() + " content " + index);
                    assertEquals(wanted.data(), audio.data(), expected.label() + " content " + index);
                }
                default -> fail("Unknown content type: " + wanted.type());
            }
        }
    }

    private record RequestFixture(String label, String tool, Map<String, Object> arguments, String endpoint, Map<String, Object> payload) {
    }

    private record BridgeFixture(String label, Boolean success, Boolean resultPresent, Object result, String output, String error) {
    }

    private record ResultFixture(String label, List<ContentFixture> content, boolean isError) {
    }

    @SuppressWarnings("unused")
    private record ContentFixture(String type, String text, String mimeType, String data) {
    }
}

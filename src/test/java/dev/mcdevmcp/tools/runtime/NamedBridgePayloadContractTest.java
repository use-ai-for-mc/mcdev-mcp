package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.bridge.BridgeJson;
import dev.mcdevmcp.bridge.BridgePayload;
import dev.mcdevmcp.bridge.BridgeRequest;
import dev.mcdevmcp.bridge.payload.BlockDetailsPayload;
import dev.mcdevmcp.bridge.payload.ChatHistoryPayload;
import dev.mcdevmcp.bridge.payload.EmptyBridgePayload;
import dev.mcdevmcp.bridge.payload.EntityDetailsPayload;
import dev.mcdevmcp.bridge.payload.EntityItemTexturePayload;
import dev.mcdevmcp.bridge.payload.ExecutePayload;
import dev.mcdevmcp.bridge.payload.ItemTextureByIdPayload;
import dev.mcdevmcp.bridge.payload.ItemTexturePayload;
import dev.mcdevmcp.bridge.payload.JoinServerPayload;
import dev.mcdevmcp.bridge.payload.LookedAtEntityPayload;
import dev.mcdevmcp.bridge.payload.NearbyBlocksPayload;
import dev.mcdevmcp.bridge.payload.NearbyEntitiesPayload;
import dev.mcdevmcp.bridge.payload.RecordVideoFramePayload;
import dev.mcdevmcp.bridge.payload.RecordVideoTimedPayload;
import dev.mcdevmcp.bridge.payload.RunCommandPayload;
import dev.mcdevmcp.bridge.payload.ScreenInspectPayload;
import dev.mcdevmcp.bridge.payload.ScreenshotPayload;
import dev.mcdevmcp.bridge.payload.SetBlockGlowPayload;
import dev.mcdevmcp.bridge.payload.SetEntityGlowPayload;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamedBridgePayloadContractTest {
    private static final BridgeJson JSON = new BridgeJson(McpJsonDefaults.getMapper());
    private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() {
    };
    private static final Path CORPUS = Path.of("src", "test", "resources", "contracts", "runtime-tools", "pre-migration-bridge-requests.jsonl");
    private static final String CORPUS_SHA256 = "4f81e7daa97aefe5191e87b333994fa20ea05e0d1c115f62aac83cdbe04b315f";
    private static final String EMBEDDED_REQUESTS_SHA256 = "2b6257049a94fb61d1da0f43f3977d068f600c54d0d94e2dc6355f922af1e624";
    private static final String ORIGINAL_CRLF_SHA256 = "c25de34bb7b3db413f96c8092566dcb4a9e757ee9e8d6739cc6b4ffd3e39f855";
    private static final List<String> SOURCES = List.of("src/main/java/dev/mcdevmcp/bridge/BridgeProbe.java:19", "src/main/java/dev/mcdevmcp/bridge/BridgeSession.java:205", "src/main/java/dev/mcdevmcp/bridge/BridgeSession.java:376", "src/main/java/dev/mcdevmcp/tools/runtime/McSnapshotTool.java:16", "src/main/java/dev/mcdevmcp/tools/runtime/McJoinServerTool.java:31", "src/main/java/dev/mcdevmcp/tools/runtime/SessionControlSupport.java:661", "src/main/java/dev/mcdevmcp/tools/runtime/SessionControlSupport.java:675", "src/main/java/dev/mcdevmcp/tools/runtime/McLeaveServerTool.java:24", "src/main/java/dev/mcdevmcp/tools/runtime/McQuitClientTool.java:34", "src/main/java/dev/mcdevmcp/tools/runtime/McClearBlockGlowTool.java:16", "src/main/java/dev/mcdevmcp/tools/runtime/RuntimeToolSupport.java:168", "src/main/java/dev/mcdevmcp/tools/runtime/RuntimeToolSupport.java:229", "src/main/java/dev/mcdevmcp/tools/runtime/RuntimeToolSupport.java:229", "src/main/java/dev/mcdevmcp/tools/runtime/McNearbyEntitiesTool.java:16", "src/main/java/dev/mcdevmcp/tools/runtime/McNearbyEntitiesTool.java:16", "src/main/java/dev/mcdevmcp/tools/runtime/McNearbyEntitiesTool.java:16", "src/main/java/dev/mcdevmcp/tools/runtime/McNearbyEntitiesTool.java:16", "src/main/java/dev/mcdevmcp/tools/runtime/McNearbyEntitiesTool.java:16", "src/main/java/dev/mcdevmcp/tools/runtime/McNearbyBlocksTool.java:16", "src/main/java/dev/mcdevmcp/tools/runtime/McNearbyBlocksTool.java:16", "src/main/java/dev/mcdevmcp/tools/runtime/McNearbyBlocksTool.java:16", "src/main/java/dev/mcdevmcp/tools/runtime/McNearbyBlocksTool.java:16", "src/main/java/dev/mcdevmcp/tools/runtime/McChatHistoryTool.java:16", "src/main/java/dev/mcdevmcp/tools/runtime/McChatHistoryTool.java:16", "src/main/java/dev/mcdevmcp/tools/runtime/McChatHistoryTool.java:16", "src/main/java/dev/mcdevmcp/tools/runtime/McChatHistoryTool.java:16", "src/main/java/dev/mcdevmcp/tools/runtime/McScreenInspectTool.java:16", "src/main/java/dev/mcdevmcp/tools/runtime/McScreenInspectTool.java:16", "src/main/java/dev/mcdevmcp/tools/runtime/McEntityDetailsTool.java:16", "src/main/java/dev/mcdevmcp/tools/runtime/McBlockDetailsTool.java:16", "src/main/java/dev/mcdevmcp/tools/runtime/McSetEntityGlowTool.java:16", "src/main/java/dev/mcdevmcp/tools/runtime/McSetBlockGlowTool.java:16", "src/main/java/dev/mcdevmcp/tools/runtime/MediaToolSupport.java:41", "src/main/java/dev/mcdevmcp/tools/runtime/MediaToolSupport.java:54", "src/main/java/dev/mcdevmcp/tools/runtime/MediaToolSupport.java:54", "src/main/java/dev/mcdevmcp/tools/runtime/MediaToolSupport.java:77", "src/main/java/dev/mcdevmcp/tools/runtime/MediaToolSupport.java:81", "src/main/java/dev/mcdevmcp/tools/runtime/MediaToolSupport.java:85", "src/main/java/dev/mcdevmcp/tools/runtime/McJoinServerTool.java:35", "src/main/java/dev/mcdevmcp/tools/runtime/McRunCommandTool.java:18");
    private static final List<String> LABELS = List.of("status-bridge-probe", "status-session-open", "status-session-verify", "snapshot-tool", "snapshot-prejoin-gate", "snapshot-poll", "screen-inspect-poll", "disconnect", "quit", "clear-block-glow", "execute-default-timeout", "looked-at-entity-absent", "looked-at-entity-present", "nearby-entities-none", "nearby-entities-range", "nearby-entities-limit", "nearby-entities-both", "nearby-entities-icons", "nearby-blocks-none", "nearby-blocks-range", "nearby-blocks-limit", "nearby-blocks-both", "chat-history-none", "chat-history-limit", "chat-history-json", "chat-history-both", "screen-inspect-false", "screen-inspect-true", "entity-details", "block-details", "set-entity-glow", "set-block-glow", "screenshot", "record-video-frame", "record-video-timed", "item-texture", "entity-item-texture", "item-texture-by-id", "join-server", "run-command");

    @Test
    void typedPayloadsMatchTheFrozenPreMigrationRequestBytes() throws Exception {
        List<CorpusRow> rows = loadRows();
        assertEquals(LABELS, rows.stream().map(CorpusRow::label).toList());
        assertEquals(SOURCES, rows.stream().map(CorpusRow::source).toList());
        assertEquals(40, rows.size());

        for (CorpusRow row : rows) {
            Map<String, Object> embedded = embeddedRequest(row);
            assertEquals(row.label(), embedded.get("id"), row.label());
            assertEquals(row.endpoint(), embedded.get("type"), row.label());
            assertEquals(row.payload(), embedded.get("payload"), row.label());
            BridgePayload payload = payload(row.label());
            BridgeRequest request = new BridgeRequest(row.label(), new BridgeEndpoint(row.endpoint()), payload);
            String actual = JSON.writeRequest(request);
            assertArrayEquals(row.request().getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8), row.label());
            assertEquals(payload.getClass(), expectedType(row.label()), row.label());
        }
    }

    @Test
    void payloadRecordsAreConcreteAndTheEnvelopeHasOnlyTheThreeWireMembers() throws Exception {
        List<CorpusRow> rows = loadRows();
        for (CorpusRow row : rows) {
            Map<String, Object> envelope = McpJsonDefaults.getMapper().readValue(JSON.writeRequest(new BridgeRequest(row.label(), new BridgeEndpoint(row.endpoint()), payload(row.label()))), MAP_TYPE);
            assertEquals(List.of("id", "type", "payload"), List.copyOf(envelope.keySet()), row.label());
            assertTrue(!envelope.toString().contains("java.lang") && !envelope.toString().contains("wireName"), row.label());
        }
    }

    @Test
    void frozenCorpusDigestsRetainTheEmbeddedRequestsAndOriginalCrLfProvenance() throws Exception {
        List<CorpusRow> rows = loadRows();
        String embeddedRequests = String.join("\n", rows.stream().map(CorpusRow::request).toList()) + "\n";
        assertEquals(EMBEDDED_REQUESTS_SHA256, sha256(embeddedRequests.getBytes(StandardCharsets.UTF_8)));

        byte[] bytes = Files.readAllBytes(CORPUS);
        assertEquals(CORPUS_SHA256, sha256(bytes));
        assertEquals(10_972, bytes.length);
        assertEquals(0, countCarriageReturns(bytes));
        assertEquals(40, countLineFeeds(bytes));
        assertEquals(ORIGINAL_CRLF_SHA256, sha256(withCrLf(bytes)));
    }

    private static Map<String, Object> embeddedRequest(CorpusRow row) throws IOException {
        Map<String, Object> embedded = McpJsonDefaults.getMapper().readValue(row.request(), MAP_TYPE);
        assertEquals(List.of("id", "type", "payload"), List.copyOf(embedded.keySet()), row.label());
        return embedded;
    }

    private static BridgePayload payload(String label) {
        return switch (label) {
            case "status-bridge-probe", "status-session-open", "status-session-verify", "snapshot-tool",
                 "snapshot-prejoin-gate", "snapshot-poll", "screen-inspect-poll", "disconnect", "quit",
                 "clear-block-glow" -> new EmptyBridgePayload();
            case "execute-default-timeout" -> new ExecutePayload("return [value: 1]", 10_000L);
            case "looked-at-entity-absent" -> new LookedAtEntityPayload(null);
            case "looked-at-entity-present" -> new LookedAtEntityPayload(0.0d);
            case "nearby-entities-none" -> new NearbyEntitiesPayload(null, null, false);
            case "nearby-entities-range" -> new NearbyEntitiesPayload(8.5d, null, false);
            case "nearby-entities-limit" -> new NearbyEntitiesPayload(null, 5, false);
            case "nearby-entities-both" -> new NearbyEntitiesPayload(8.5d, 5, false);
            case "nearby-entities-icons" -> new NearbyEntitiesPayload(null, null, true);
            case "nearby-blocks-none" -> new NearbyBlocksPayload(null, null);
            case "nearby-blocks-range" -> new NearbyBlocksPayload(8.5d, null);
            case "nearby-blocks-limit" -> new NearbyBlocksPayload(null, 5);
            case "nearby-blocks-both" -> new NearbyBlocksPayload(8.5d, 5);
            case "chat-history-none" -> new ChatHistoryPayload(null, false);
            case "chat-history-limit" -> new ChatHistoryPayload(20, false);
            case "chat-history-json" -> new ChatHistoryPayload(null, true);
            case "chat-history-both" -> new ChatHistoryPayload(20, true);
            case "screen-inspect-false" -> new ScreenInspectPayload(false);
            case "screen-inspect-true" -> new ScreenInspectPayload(true);
            case "entity-details" -> new EntityDetailsPayload(12);
            case "block-details" -> new BlockDetailsPayload(0, 64, -2);
            case "set-entity-glow" -> new SetEntityGlowPayload(7, true);
            case "set-block-glow" -> new SetBlockGlowPayload(1, 64, -2, true);
            case "screenshot" -> new ScreenshotPayload(2, 0.75d);
            case "record-video-frame" -> new RecordVideoFramePayload(3, "frame", "frames", 2, 2, 0.75d);
            case "record-video-timed" -> new RecordVideoTimedPayload(4, 50.0d, "grid", 2, 2, 0.75d);
            case "item-texture" -> new ItemTexturePayload(0);
            case "entity-item-texture" -> new EntityItemTexturePayload(7, "MAINHAND");
            case "item-texture-by-id" -> new ItemTextureByIdPayload("minecraft:diamond");
            case "join-server" -> new JoinServerPayload("localhost:25565", true);
            case "run-command" -> new RunCommandPayload("give @s minecraft:stone");
            default -> throw new AssertionError("Unhandled frozen payload row: " + label);
        };
    }

    private static Class<? extends BridgePayload> expectedType(String label) {
        return switch (label) {
            case "status-bridge-probe", "status-session-open", "status-session-verify", "snapshot-tool",
                 "snapshot-prejoin-gate", "snapshot-poll", "screen-inspect-poll", "disconnect", "quit",
                 "clear-block-glow" -> EmptyBridgePayload.class;
            case "execute-default-timeout" -> ExecutePayload.class;
            case "looked-at-entity-absent", "looked-at-entity-present" -> LookedAtEntityPayload.class;
            case "nearby-entities-none", "nearby-entities-range", "nearby-entities-limit", "nearby-entities-both",
                 "nearby-entities-icons" -> NearbyEntitiesPayload.class;
            case "nearby-blocks-none", "nearby-blocks-range", "nearby-blocks-limit", "nearby-blocks-both" ->
                    NearbyBlocksPayload.class;
            case "chat-history-none", "chat-history-limit", "chat-history-json", "chat-history-both" ->
                    ChatHistoryPayload.class;
            case "screen-inspect-false", "screen-inspect-true" -> ScreenInspectPayload.class;
            case "entity-details" -> EntityDetailsPayload.class;
            case "block-details" -> BlockDetailsPayload.class;
            case "set-entity-glow" -> SetEntityGlowPayload.class;
            case "set-block-glow" -> SetBlockGlowPayload.class;
            case "screenshot" -> ScreenshotPayload.class;
            case "record-video-frame" -> RecordVideoFramePayload.class;
            case "record-video-timed" -> RecordVideoTimedPayload.class;
            case "item-texture" -> ItemTexturePayload.class;
            case "entity-item-texture" -> EntityItemTexturePayload.class;
            case "item-texture-by-id" -> ItemTextureByIdPayload.class;
            case "join-server" -> JoinServerPayload.class;
            case "run-command" -> RunCommandPayload.class;
            default -> throw new AssertionError("Unhandled frozen payload row: " + label);
        };
    }

    private static List<CorpusRow> loadRows() throws IOException {
        return RuntimeContractFixtures.load(McpJsonDefaults.getMapper(), "contracts/runtime-tools/pre-migration-bridge-requests.jsonl", CorpusRow.class);
    }

    private static int countCarriageReturns(byte[] bytes) {
        int count = 0;
        for (byte candidate : bytes) {
            if (candidate == '\r') {
                count++;
            }
        }
        return count;
    }

    private static int countLineFeeds(byte[] bytes) {
        int count = 0;
        for (byte candidate : bytes) {
            if (candidate == '\n') {
                count++;
            }
        }
        return count;
    }

    private static byte[] withCrLf(byte[] bytes) {
        byte[] result = new byte[bytes.length + countLineFeeds(bytes)];
        int target = 0;
        for (byte candidate : bytes) {
            if (candidate == '\n') {
                result[target++] = '\r';
            }
            result[target++] = candidate;
        }
        return result;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is required", exception);
        }
    }

    private record CorpusRow(String label, String source, String endpoint, Map<String, Object> payload, String request) {
    }
}

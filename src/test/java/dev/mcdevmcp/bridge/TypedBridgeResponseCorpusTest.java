package dev.mcdevmcp.bridge;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class TypedBridgeResponseCorpusTest {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();
    private static final String RESOURCE = "contracts/runtime-tools/typed-bridge-response-corpus.jsonl";
    private static final String SHA256 = "883cc5164c422a9db879d94a6b0c1f975d05964d8c36a9718897c9d6cdb5a829";

    @Test
    void durableCorpusRecordsSourceProvenanceAndEveryClosedProviderShape() throws Exception {
        List<Row> rows = load();
        assertEquals(10, rows.size());
        Row metadata = rows.getFirst();
        assertEquals("metadata", metadata.kind());
        assertEquals("typed-bridge-response", metadata.corpus());
        assertEquals("reconstructed-source-attributed", metadata.status());
        assertEquals("654e50c83774735d588a8fc6bc5ab3ebf0f1c961", metadata.providerCommit());
        assertEquals("2026-09-04T23:52:07.9144231Z", metadata.createdAt());
        assertEquals("not-live; reconstructed from current provider source after the closed-reader replacement because no pre-replacement wire capture was persisted", metadata.capture());
        assertEquals("not recorded; do not treat this as pre-replacement evidence", metadata.replacementTiming());
        assertEquals(List.of("status-full", "looked-at-entity-present", "looked-at-entity-null", "screenshot", "item-texture", "entity-item-texture", "item-texture-by-id", "record-video-grid", "record-video-frames"), rows.stream().skip(1).map(Row::label).toList());

        for (Row row : rows.stream().skip(1).toList()) {
            assertTrue(row.source().contains("BridgeServer.java"), row.label());
            assertNotNull(row.endpoint(), row.label());
            assertNotNull(row.result(), row.label());
        }
        Row status = rows.get(1);
        assertEquals("BridgeServer.java:879-906; StatusDto.java:14-38", status.source());
        assertEquals(9976, ((Number) status.result().get("webUiPort")).intValue());
        assertEquals("C:\\Game\\logs\\latest.log", status.result().get("latestLog"));
        assertTrue(rows.get(3).result().containsKey("entityId"));
        assertNull(rows.get(3).result().get("entityId"));

        BridgeResultDecoder decoder = new BridgeResultDecoder(MAPPER);
        BridgeStatusWire statusWire = decoder.decode(new BridgeEndpoint("status"), rows.get(1).result(), BridgeResultTypes.STATUS);
        assertEquals(Integer.valueOf(9976), statusWire.webUiPort());
        assertEquals("C:\\Game\\logs\\debug.log", statusWire.debugLog());
        assertEquals(Integer.valueOf(12), decoder.decode(new BridgeEndpoint("lookedAtEntity"), rows.get(2).result(), BridgeResultTypes.LOOKED_AT_ENTITY).entityId());
        assertNull(decoder.decode(new BridgeEndpoint("lookedAtEntity"), rows.get(3).result(), BridgeResultTypes.LOOKED_AT_ENTITY).entityId());
        assertInstanceOf(ScreenshotWireResult.class, decoder.decode(new BridgeEndpoint("screenshot"), rows.get(4).result(), BridgeResultTypes.SCREENSHOT));
        for (int index = 5; index <= 7; index++) {
            assertInstanceOf(TextureWireResult.class, decoder.decode(new BridgeEndpoint(rows.get(index).endpoint()), rows.get(index).result(), BridgeResultTypes.TEXTURE));
        }
        assertInstanceOf(RecordVideoGridWireResult.class, decoder.decode(new BridgeEndpoint("record_video"), rows.get(8).result(), BridgeResultTypes.RECORD_VIDEO));
        assertInstanceOf(RecordVideoFramesWireResult.class, decoder.decode(new BridgeEndpoint("record_video"), rows.get(9).result(), BridgeResultTypes.RECORD_VIDEO));
    }

    @Test
    void corpusHashIsFrozenForReviewerReproducibility() throws Exception {
        Path path = Path.of("src", "test", "resources", "contracts", "runtime-tools", "typed-bridge-response-corpus.jsonl");
        assertEquals(SHA256, sha256(Files.readAllBytes(path)));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static List<Row> load() throws IOException {
        try (InputStream stream = TypedBridgeResponseCorpusTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            assertNotNull(stream, RESOURCE);
            try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                var rows = new java.util.ArrayList<Row>();
                String line;
                while ((line = reader.readLine()) != null) {
                    assertFalse(line.isBlank());
                    rows.add(MAPPER.readValue(line, Row.class));
                }
                return List.copyOf(rows);
            }
        }
    }

    private record Row(String kind, String corpus, String label, String endpoint, String source, String status, String capture, String providerCommit, String createdAt, String replacementTiming, Map<String, Object> result) {
    }
}
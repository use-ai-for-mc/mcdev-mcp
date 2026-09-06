package dev.mcdevmcp.bridge;

import dev.mcdevmcp.support.JsonResourceReader;
import dev.mcdevmcp.bridge.payload.EmptyBridgePayload;
import dev.mcdevmcp.bridge.payload.ScreenInspectPayload;
import io.modelcontextprotocol.json.McpJsonDefaults;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class BridgeJsonContractTest {
    @Test
    void serializesTheFixtureDefinedRequestEnvelopeAndToleratesUnknownResponseFields() {
        BridgeJson json = new BridgeJson(McpJsonDefaults.getMapper());
        String request = json.writeRequest(new BridgeRequest("req_1", new BridgeEndpoint("status"), new ScreenInspectPayload(true)));

        assertEquals("{\"id\":\"req_1\",\"type\":\"status\",\"payload\":{\"includeIcons\":true}}", request);
        BridgeResponse response = json.readResponse("{\"id\":\"req_1\",\"success\":true,\"result\":{\"gameDirectory\":\"run\"},\"unknown\":42}");

        assertEquals("req_1", response.id());
        assertTrue(response.success());
        assertTrue(response.resultPresent());
        assertTrue(BridgePayloadValidator.requireBoolean(true, "flag"));
        assertEquals("text", BridgePayloadValidator.requireString("text", "field"));
        assertEquals(7L, BridgePayloadValidator.requireIntegralNumber(7, "count"));
        assertEquals("png", BridgePayloadValidator.requirePngBase64("png"));
        assertThrows(UnsupportedOperationException.class, () -> BridgePayloadValidator.requireOpenObject(response).put("later", false));
    }

    @Test
    void distinguishesAnExplicitNullResultFromAnOmittedResult() {
        BridgeJson json = new BridgeJson(McpJsonDefaults.getMapper());

        BridgeResponse explicitNull = json.readResponse("{\"id\":\"req_1\",\"success\":true,\"result\":null}");
        BridgeResponse omitted = json.readResponse("{\"id\":\"req_2\",\"success\":true}");

        assertTrue(explicitNull.resultPresent());
        assertNull(BridgePayloadValidator.requirePresentResult("lookedAtEntity", explicitNull));
        assertFalse(omitted.resultPresent());
        assertThrows(IllegalArgumentException.class, () -> BridgePayloadValidator.requirePresentResult("lookedAtEntity", omitted));
    }

    @Test
    void rejectsMalformedOrIncompleteResponseEnvelopes() {
        BridgeJson json = new BridgeJson(McpJsonDefaults.getMapper());

        assertThrows(IllegalArgumentException.class, () -> json.readResponse("{\"success\":true}"));
        assertThrows(IllegalArgumentException.class, () -> json.readResponse("{\"id\":\"req_1\"}"));
        assertThrows(IllegalArgumentException.class, () -> json.readResponse("{not-json"));
    }

    @Test
    void readsTheVersionedDebugBridgeFixtures() throws Exception {
        var mapper = McpJsonDefaults.getMapper();
        BridgeJson json = new BridgeJson(mapper);
        JsonResourceReader resources = new JsonResourceReader(mapper);

        FixtureMetadata metadata = resources.read("/debugbridge/2.0.0/metadata.json", FixtureMetadata.class);
        assertEquals("v2.0.0", metadata.release());
        assertEquals("72902e65c4edd1e2147dc6ac3f8182abd56711a1", metadata.commit());
        assertEquals(List.of("id", "type", "payload"), metadata.requestShape());
        assertEquals(List.of("id", "success", "result", "output", "error"), metadata.responseShape());
        Object expectedRequest = mapper.readValue(resources.readText("/debugbridge/2.0.0/request.json"), Object.class);
        Object actualRequest = mapper.readValue(json.writeRequest(new BridgeRequest("req_1", new BridgeEndpoint("status"), new EmptyBridgePayload())), Object.class);
        assertEquals(expectedRequest, actualRequest);
        BridgeResponse statusResponse = json.readResponse(resources.readText("/debugbridge/2.0.0/success.json"));
        BridgeStatusWire status = new BridgeResultDecoder(mapper).decode(new BridgeEndpoint("status"), BridgePayloadValidator.requireResult("status", statusResponse), BridgeResultTypes.STATUS);
        assertEquals("1.21.11", status.version());
        assertEquals(0L, status.refs());
        assertFalse(json.readResponse(resources.readText("/debugbridge/2.0.0/error.json")).success());
        assertEquals("passthrough", BridgePayloadValidator.requireOpenObject(json.readResponse(resources.readText("/debugbridge/2.0.0/missing-optional.json"))).get("mappingStatus"));
        assertThrows(IllegalArgumentException.class, () -> json.readResponse(resources.readText("/debugbridge/2.0.0/malformed.json")));
    }

    @Test
    void endpointAwareValidationKeepsWireDataBounded() {
        String enormous = "x".repeat(2_000);
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> BridgePayloadValidator.requireOpenObject("screenshot", new BridgeResponse("req_1", false, false, null, "", enormous)));

        assertTrue(exception.getMessage().contains("screenshot"));
        assertTrue(exception.getMessage().length() < 600);
        IllegalArgumentException primitive = assertThrows(IllegalArgumentException.class, () -> BridgePayloadValidator.requireBoolean("screen", "paused", "no"));
        assertTrue(primitive.getMessage().contains("screen"));
    }

    private record FixtureMetadata(String release, String commit, List<String> requestShape, List<String> responseShape) {
    }
}

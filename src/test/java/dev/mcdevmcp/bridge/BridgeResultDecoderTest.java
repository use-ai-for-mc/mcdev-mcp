package dev.mcdevmcp.bridge;

import dev.mcdevmcp.mcp.tool.CountingMcpJsonMapper;
import dev.mcdevmcp.mcp.tool.api.JsonLogicalType;
import dev.mcdevmcp.mcp.tool.api.JsonValueSchema;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class BridgeResultDecoderTest {
    private static final BridgeResultDecoder DECODER = new BridgeResultDecoder(McpJsonDefaults.getMapper());

    @Test
    void validatesBeforeMaterializingAndIgnoresAdditiveProviderFields() {
        var result = new LinkedHashMap<String, Object>();
        result.put("path", "C:\\Game\\shot.jpg");
        result.put("width", 1920);
        result.put("height", 1080);
        result.put("sizeBytes", 1536L);
        result.put("mimeType", "image/jpeg");
        result.put("expiresAt", "later");

        ScreenshotWireResult decoded = DECODER.decode(new BridgeEndpoint("screenshot"), result, BridgeResultTypes.SCREENSHOT);

        assertEquals("C:\\Game\\shot.jpg", decoded.path());
        assertEquals(1920, decoded.width());
    }

    @Test
    void rejectsCoercionsAndMissingRequiredMembersAtTheEndpointBoundary() {
        var wrongType = new HashMap<String, Object>();
        wrongType.put("path", "shot.jpg");
        wrongType.put("width", "1920");
        wrongType.put("height", 1080);
        wrongType.put("sizeBytes", 1L);
        wrongType.put("mimeType", "image/jpeg");
        IllegalArgumentException wrong = assertThrows(IllegalArgumentException.class, () -> DECODER.decode(new BridgeEndpoint("screenshot"), wrongType, BridgeResultTypes.SCREENSHOT));
        assertTrue(wrong.getMessage().contains("screenshot"));

        IllegalArgumentException omitted = assertThrows(IllegalArgumentException.class, () -> DECODER.decode(new BridgeEndpoint("lookedAtEntity"), Map.of(), BridgeResultTypes.LOOKED_AT_ENTITY));
        assertTrue(omitted.getMessage().contains("lookedAtEntity"));
    }

    @Test
    void decodesTheTaggedVideoUnionOnceAndRequiresItsSemanticDiscriminator() throws Exception {
        Map<String, Object> frames = new LinkedHashMap<>();
        frames.put("mode", "frames");
        frames.put("paths", List.of("C:\\Game\\frame.jpg"));
        frames.put("frameWidth", 320);
        frames.put("frameHeight", 180);
        frames.put("mimeType", "image/jpeg");
        frames.put("frameCount", 1);
        frames.put("captureMs", 200L);
        frames.put("intervalMs", 17.2);
        frames.put("sizeBytes", 100L);
        frames.put("dropped", 0);

        RecordVideoFramesWireResult decoded = assertInstanceOf(RecordVideoFramesWireResult.class, DECODER.decode(new BridgeEndpoint("record_video"), frames, BridgeResultTypes.RECORD_VIDEO));
        assertTrue(McpJsonDefaults.getMapper().writeValueAsString((RecordVideoWireResult) decoded).contains("\"mode\":\"frames\""));
        assertFalse(McpJsonDefaults.getMapper().writeValueAsString((RecordVideoWireResult) decoded).contains("dev.mcdevmcp"));

        frames.put("mode", "mosaic");
        assertThrows(IllegalArgumentException.class, () -> DECODER.decode(new BridgeEndpoint("record_video"), frames, BridgeResultTypes.RECORD_VIDEO));
    }

    @Test
    void usesOnlyTheDirectionalInputSchema() {
        JsonLogicalType<String> outputOnly = JsonLogicalType.outputOnly("test.output-only", String.class, JsonValueSchema.of(Map.of("type", "string")));
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> DECODER.decode(new BridgeEndpoint("output"), "value", outputOnly));
        assertTrue(exception.getMessage().contains("input schema"));
    }

    @Test
    void everyClosedTargetUsesOneValidationAndOneMapperMaterialization() {
        for (ValidCase testCase : validCases()) {
            CountingMcpJsonMapper mapper = new CountingMcpJsonMapper(McpJsonDefaults.getMapper());
            CountingSchemaValidator validator = new CountingSchemaValidator(McpJsonDefaults.getSchemaValidator());
            Object decoded = new BridgeResultDecoder(mapper, validator).decode(testCase.endpoint(), testCase.result(), testCase.type());
            assertNotNull(decoded, testCase.name());
            assertEquals(1, validator.calls, testCase.name());
            assertEquals(1, mapper.convertValueCalls(), testCase.name());
        }
    }

    @Test
    void strictDecoderMatrixRejectsInvalidClosedWireValuesBeforeConversion() {
        for (InvalidCase testCase : invalidCases()) {
            CountingMcpJsonMapper mapper = new CountingMcpJsonMapper(McpJsonDefaults.getMapper());
            CountingSchemaValidator validator = new CountingSchemaValidator(McpJsonDefaults.getSchemaValidator());
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new BridgeResultDecoder(mapper, validator).decode(testCase.endpoint(), testCase.result(), testCase.type()), testCase.name());
            assertTrue(failure.getMessage().contains(testCase.endpoint().wireName()), testCase.name());
            assertEquals(1, validator.calls, testCase.name());
            assertEquals(0, mapper.convertValueCalls(), testCase.name());
        }
    }

    @Test
    void boundsValidatorDiagnosticsWithoutDiscardingUsefulDetail() {
        JsonSchemaValidator validator = (_, _) -> new JsonSchemaValidator.ValidationResponse(false, "required property 'path' violates the screenshot rule: " + "x".repeat(2_000), null);
        CountingMcpJsonMapper mapper = new CountingMcpJsonMapper(McpJsonDefaults.getMapper());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> new BridgeResultDecoder(mapper, validator).decode(new BridgeEndpoint("screenshot"), screenshot("shot.jpg"), BridgeResultTypes.SCREENSHOT));

        assertTrue(failure.getMessage().contains("DebugBridge screenshot response has invalid result for debugbridge.screenshot.v1"));
        assertTrue(failure.getMessage().contains("required property 'path' violates the screenshot rule"));
        assertTrue(failure.getMessage().length() < 700);
        assertEquals(0, mapper.convertValueCalls());
    }

    @Test
    void preservesForeignPathLexemesOnlyAtTheWireBoundary() {
        String foreignPath = "C:\\Foreign\\bridge-result.jpg";
        Map<String, Object> status = status();
        status.put("gameDir", foreignPath);
        assertEquals(foreignPath, DECODER.decode(new BridgeEndpoint("status"), status, BridgeResultTypes.STATUS).gameDir());
        assertEquals(foreignPath, DECODER.decode(new BridgeEndpoint("screenshot"), screenshot(foreignPath), BridgeResultTypes.SCREENSHOT).path());
        RecordVideoGridWireResult grid = assertInstanceOf(RecordVideoGridWireResult.class, DECODER.decode(new BridgeEndpoint("record_video"), grid(foreignPath), BridgeResultTypes.RECORD_VIDEO));
        assertEquals(foreignPath, grid.path());
        RecordVideoFramesWireResult frames = assertInstanceOf(RecordVideoFramesWireResult.class, DECODER.decode(new BridgeEndpoint("record_video"), frames(List.of(foreignPath)), BridgeResultTypes.RECORD_VIDEO));
        assertEquals(List.of(foreignPath), frames.paths());
    }

    private static List<ValidCase> validCases() {
        String nativePath = java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize().resolve("bridge-result.jpg").toString();
        Map<String, Object> maxInterval = grid(nativePath);
        maxInterval.put("intervalMs", 9_223_372_036_854d);
        Map<String, Object> maxCapture = grid(nativePath);
        maxCapture.put("captureMs", Long.MAX_VALUE);
        return List.of(new ValidCase("status", new BridgeEndpoint("status"), status(), BridgeResultTypes.STATUS), new ValidCase("lookedAtEntity-id", new BridgeEndpoint("lookedAtEntity"), lookedAt(7), BridgeResultTypes.LOOKED_AT_ENTITY), new ValidCase("lookedAtEntity-null", new BridgeEndpoint("lookedAtEntity"), lookedAt(null), BridgeResultTypes.LOOKED_AT_ENTITY), new ValidCase("screenshot", new BridgeEndpoint("screenshot"), screenshot(nativePath), BridgeResultTypes.SCREENSHOT), new ValidCase("texture", new BridgeEndpoint("getItemTexture"), texture(), BridgeResultTypes.TEXTURE), new ValidCase("record-grid", new BridgeEndpoint("record_video"), grid(nativePath), BridgeResultTypes.RECORD_VIDEO), new ValidCase("record-frames", new BridgeEndpoint("record_video"), frames(List.of(nativePath)), BridgeResultTypes.RECORD_VIDEO), new ValidCase("record-grid-max-interval", new BridgeEndpoint("record_video"), maxInterval, BridgeResultTypes.RECORD_VIDEO), new ValidCase("record-grid-max-capture", new BridgeEndpoint("record_video"), maxCapture, BridgeResultTypes.RECORD_VIDEO));
    }

    private static List<InvalidCase> invalidCases() {
        List<InvalidCase> cases = new java.util.ArrayList<>();
        for (String field : List.of("version", "mappingStatus", "obfuscated", "refs")) {
            Map<String, Object> status = status();
            status.remove(field);
            cases.add(new InvalidCase("status-missing-" + field, new BridgeEndpoint("status"), status, BridgeResultTypes.STATUS));
        }
        for (String field : List.of("gameDir", "logsDir", "latestLog", "latestLogExists", "debugLog", "debugLogExists", "sessionControlEnabled", "webUiPort")) {
            Map<String, Object> status = status();
            status.put(field, null);
            cases.add(new InvalidCase("status-null-" + field, new BridgeEndpoint("status"), status, BridgeResultTypes.STATUS));
        }
        Map<String, Object> numericString = screenshot("shot.jpg");
        numericString.put("width", "1920");
        cases.add(new InvalidCase("screenshot-numeric-string", new BridgeEndpoint("screenshot"), numericString, BridgeResultTypes.SCREENSHOT));
        Map<String, Object> fractionalInteger = screenshot("shot.jpg");
        fractionalInteger.put("width", 1.5d);
        cases.add(new InvalidCase("screenshot-fractional-integer", new BridgeEndpoint("screenshot"), fractionalInteger, BridgeResultTypes.SCREENSHOT));
        Map<String, Object> overflowInteger = screenshot("shot.jpg");
        overflowInteger.put("width", 2_147_483_648L);
        cases.add(new InvalidCase("screenshot-overflow-integer", new BridgeEndpoint("screenshot"), overflowInteger, BridgeResultTypes.SCREENSHOT));
        Map<String, Object> negative = screenshot("shot.jpg");
        negative.put("width", -1);
        cases.add(new InvalidCase("screenshot-negative-integer", new BridgeEndpoint("screenshot"), negative, BridgeResultTypes.SCREENSHOT));
        Map<String, Object> nullTexture = texture();
        nullTexture.put("base64Png", null);
        cases.add(new InvalidCase("texture-invalid-null", new BridgeEndpoint("getItemTexture"), nullTexture, BridgeResultTypes.TEXTURE));
        for (String field : List.of("base64Png", "width", "height", "spriteName")) {
            Map<String, Object> texture = texture();
            texture.remove(field);
            cases.add(new InvalidCase("texture-missing-" + field, new BridgeEndpoint("getItemTexture"), texture, BridgeResultTypes.TEXTURE));
        }
        Map<String, Object> missingScreenshot = screenshot("shot.jpg");
        missingScreenshot.remove("path");
        cases.add(new InvalidCase("screenshot-missing-required", new BridgeEndpoint("screenshot"), missingScreenshot, BridgeResultTypes.SCREENSHOT));
        cases.add(new InvalidCase("status-wrong-root", new BridgeEndpoint("status"), List.of(), BridgeResultTypes.STATUS));
        cases.add(new InvalidCase("screenshot-wrong-root", new BridgeEndpoint("screenshot"), "shot", BridgeResultTypes.SCREENSHOT));
        cases.add(new InvalidCase("texture-wrong-root", new BridgeEndpoint("getItemTexture"), List.of(), BridgeResultTypes.TEXTURE));
        cases.add(new InvalidCase("video-wrong-root", new BridgeEndpoint("record_video"), List.of(), BridgeResultTypes.RECORD_VIDEO));
        Map<String, Object> missingLookedAt = new LinkedHashMap<>();
        cases.add(new InvalidCase("lookedAtEntity-missing-required", new BridgeEndpoint("lookedAtEntity"), missingLookedAt, BridgeResultTypes.LOOKED_AT_ENTITY));
        Map<String, Object> missingMode = grid("shot.jpg");
        missingMode.remove("mode");
        cases.add(new InvalidCase("video-missing-mode", new BridgeEndpoint("record_video"), missingMode, BridgeResultTypes.RECORD_VIDEO));
        cases.add(new InvalidCase("video-unknown-mode", new BridgeEndpoint("record_video"), Map.of("mode", "mosaic"), BridgeResultTypes.RECORD_VIDEO));
        for (double interval : new double[]{-1d, Double.NaN, Double.POSITIVE_INFINITY, Math.nextUp(9_223_372_036_854d)}) {
            Map<String, Object> invalidInterval = grid("shot.jpg");
            invalidInterval.put("intervalMs", interval);
            cases.add(new InvalidCase("video-invalid-interval-" + interval, new BridgeEndpoint("record_video"), invalidInterval, BridgeResultTypes.RECORD_VIDEO));
        }
        for (String field : List.of("mode", "path", "width", "height", "sizeBytes", "mimeType", "frameCount", "frameWidth", "frameHeight", "gridCols", "gridRows", "captureMs", "intervalMs", "dropped")) {
            Map<String, Object> grid = grid("shot.jpg");
            grid.remove(field);
            cases.add(new InvalidCase("video-grid-missing-" + field, new BridgeEndpoint("record_video"), grid, BridgeResultTypes.RECORD_VIDEO));
        }
        for (String field : List.of("mode", "paths", "frameWidth", "frameHeight", "mimeType", "frameCount", "captureMs", "intervalMs", "sizeBytes", "dropped")) {
            Map<String, Object> frames = frames(List.of("shot.jpg"));
            frames.remove(field);
            cases.add(new InvalidCase("video-frames-missing-" + field, new BridgeEndpoint("record_video"), frames, BridgeResultTypes.RECORD_VIDEO));
        }
        Map<String, Object> captureOverflow = grid("shot.jpg");
        captureOverflow.put("captureMs", BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));
        cases.add(new InvalidCase("video-capture-overflow", new BridgeEndpoint("record_video"), captureOverflow, BridgeResultTypes.RECORD_VIDEO));
        Map<String, Object> captureNegative = grid("shot.jpg");
        captureNegative.put("captureMs", -1L);
        cases.add(new InvalidCase("video-capture-negative", new BridgeEndpoint("record_video"), captureNegative, BridgeResultTypes.RECORD_VIDEO));
        Map<String, Object> captureFractional = grid("shot.jpg");
        captureFractional.put("captureMs", 200.5d);
        cases.add(new InvalidCase("video-capture-fractional", new BridgeEndpoint("record_video"), captureFractional, BridgeResultTypes.RECORD_VIDEO));
        Map<String, Object> captureString = grid("shot.jpg");
        captureString.put("captureMs", "200");
        cases.add(new InvalidCase("video-capture-string", new BridgeEndpoint("record_video"), captureString, BridgeResultTypes.RECORD_VIDEO));
        Map<String, Object> longOverflow = grid("shot.jpg");
        longOverflow.put("sizeBytes", BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));
        cases.add(new InvalidCase("video-long-overflow", new BridgeEndpoint("record_video"), longOverflow, BridgeResultTypes.RECORD_VIDEO));
        return List.copyOf(cases);
    }

    private static Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", "1.21.11");
        result.put("mappingStatus", "mojang");
        result.put("obfuscated", false);
        result.put("refs", 7L);
        result.put("webUiPort", 9976);
        return result;
    }

    private static Map<String, Object> lookedAt(Integer entityId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entityId", entityId);
        return result;
    }

    private static Map<String, Object> screenshot(String path) {
        return new LinkedHashMap<>(Map.of("path", path, "width", 1920, "height", 1080, "sizeBytes", 1536L, "mimeType", "image/jpeg"));
    }

    private static Map<String, Object> texture() {
        return new LinkedHashMap<>(Map.of("base64Png", "iVBORw0KGgo=", "width", 16, "height", 16, "spriteName", "minecraft:item/diamond"));
    }

    private static Map<String, Object> grid(String path) {
        return new LinkedHashMap<>(Map.ofEntries(Map.entry("mode", "grid"), Map.entry("path", path), Map.entry("width", 640), Map.entry("height", 360), Map.entry("sizeBytes", 2560L), Map.entry("mimeType", "image/jpeg"), Map.entry("frameCount", 4), Map.entry("frameWidth", 320), Map.entry("frameHeight", 180), Map.entry("gridCols", 2), Map.entry("gridRows", 2), Map.entry("captureMs", 200L), Map.entry("intervalMs", 17.2), Map.entry("dropped", 0)));
    }

    private static Map<String, Object> frames(List<String> paths) {
        return new LinkedHashMap<>(Map.ofEntries(Map.entry("mode", "frames"), Map.entry("paths", paths), Map.entry("frameWidth", 320), Map.entry("frameHeight", 180), Map.entry("mimeType", "image/jpeg"), Map.entry("frameCount", paths.size()), Map.entry("captureMs", 200L), Map.entry("intervalMs", 17.2), Map.entry("sizeBytes", 2560L), Map.entry("dropped", 0)));
    }

    private record ValidCase(String name, BridgeEndpoint endpoint, Object result, JsonLogicalType<?> type) {
    }

    private record InvalidCase(String name, BridgeEndpoint endpoint, Object result, JsonLogicalType<?> type) {
    }

    private static final class CountingSchemaValidator implements JsonSchemaValidator {
        private final JsonSchemaValidator delegate;
        private int calls;

        private CountingSchemaValidator(JsonSchemaValidator delegate) {
            this.delegate = delegate;
        }

        @Override
        public ValidationResponse validate(Map<String, Object> schema, Object value) {
            calls++;
            return delegate.validate(schema, value);
        }
    }
}
package dev.mcdevmcp.bridge;

import dev.mcdevmcp.mcp.tool.api.JsonLogicalType;
import dev.mcdevmcp.mcp.tool.api.JsonValueSchema;
import io.modelcontextprotocol.json.TypeRef;

import java.util.List;
import java.util.Map;

/**
 * Trusted closed-result schemas selected by server-owned endpoint code.
 */
public final class BridgeResultTypes {
    private static final long MAX_INTERVAL_MILLIS = 9_223_372_036_854L;

    public static final JsonLogicalType<BridgeStatusWire> STATUS = JsonLogicalType.inputOnly("debugbridge.status.v1", BridgeStatusWire.class, object(Map.ofEntries(Map.entry("version", string()), Map.entry("mappingStatus", string()), Map.entry("obfuscated", booleanType()), Map.entry("refs", nonNegativeLong()), Map.entry("gameDir", string()), Map.entry("logsDir", string()), Map.entry("latestLog", string()), Map.entry("latestLogExists", booleanType()), Map.entry("debugLog", string()), Map.entry("debugLogExists", booleanType()), Map.entry("sessionControlEnabled", booleanType()), Map.entry("webUiPort", portInteger())), List.of("version", "mappingStatus", "obfuscated", "refs")));

    public static final JsonLogicalType<LookedAtEntityWireResult> LOOKED_AT_ENTITY = JsonLogicalType.inputOnly("debugbridge.looked-at-entity.v1", LookedAtEntityWireResult.class, object(Map.of("entityId", nullable(integer())), List.of("entityId")));

    public static final JsonLogicalType<ScreenshotWireResult> SCREENSHOT = JsonLogicalType.inputOnly("debugbridge.screenshot.v1", ScreenshotWireResult.class, object(Map.of("path", string(), "width", nonNegativeInt(), "height", nonNegativeInt(), "sizeBytes", nonNegativeLong(), "mimeType", string()), List.of("path", "width", "height", "sizeBytes", "mimeType")));

    public static final JsonLogicalType<TextureWireResult> TEXTURE = JsonLogicalType.inputOnly("debugbridge.texture.v1", TextureWireResult.class, object(Map.of("base64Png", string(), "width", nonNegativeInt(), "height", nonNegativeInt(), "spriteName", string()), List.of("base64Png", "width", "height", "spriteName")));

    public static final JsonLogicalType<RecordVideoWireResult> RECORD_VIDEO = JsonLogicalType.inputOnly("debugbridge.record-video.v1", new TypeRef<>() {
    }, new JsonValueSchema(Map.of("oneOf", List.of(gridSchema(), framesSchema()))));

    private BridgeResultTypes() {
    }

    private static JsonValueSchema object(Map<String, Object> properties, List<String> required) {
        return new JsonValueSchema(Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", true));
    }

    private static Map<String, Object> string() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> booleanType() {
        return Map.of("type", "boolean");
    }

    private static Map<String, Object> nonNegativeInt() {
        return Map.of("type", "integer", "minimum", 0, "maximum", Integer.MAX_VALUE);
    }

    private static Map<String, Object> nonNegativeLong() {
        return Map.of("type", "integer", "minimum", 0, "maximum", Long.MAX_VALUE);
    }

    private static Map<String, Object> portInteger() {
        return Map.of("type", "integer", "minimum", 1, "maximum", 65535);
    }

    private static Map<String, Object> integer() {
        return Map.of("type", "integer", "minimum", Integer.MIN_VALUE, "maximum", Integer.MAX_VALUE);
    }

    private static Map<String, Object> interval() {
        return Map.of("type", "number", "minimum", 0, "maximum", MAX_INTERVAL_MILLIS);
    }

    private static Map<String, Object> nullable(Map<String, Object> schema) {
        return Map.of("oneOf", List.of(schema, Map.of("type", "null")));
    }

    private static Map<String, Object> arrayOf(Map<String, Object> schema) {
        return Map.of("type", "array", "items", schema);
    }

    private static Map<String, Object> gridSchema() {
        return objectMap(Map.ofEntries(Map.entry("mode", Map.of("type", "string", "const", "grid")), Map.entry("path", string()), Map.entry("width", nonNegativeInt()), Map.entry("height", nonNegativeInt()), Map.entry("sizeBytes", nonNegativeLong()), Map.entry("mimeType", string()), Map.entry("frameCount", nonNegativeInt()), Map.entry("frameWidth", nonNegativeInt()), Map.entry("frameHeight", nonNegativeInt()), Map.entry("gridCols", nonNegativeInt()), Map.entry("gridRows", nonNegativeInt()), Map.entry("captureMs", nonNegativeLong()), Map.entry("intervalMs", interval()), Map.entry("dropped", nonNegativeInt())), List.of("mode", "path", "width", "height", "sizeBytes", "mimeType", "frameCount", "frameWidth", "frameHeight", "gridCols", "gridRows", "captureMs", "intervalMs", "dropped"));
    }

    private static Map<String, Object> framesSchema() {
        return objectMap(Map.ofEntries(Map.entry("mode", Map.of("type", "string", "const", "frames")), Map.entry("paths", arrayOf(string())), Map.entry("frameWidth", nonNegativeInt()), Map.entry("frameHeight", nonNegativeInt()), Map.entry("mimeType", string()), Map.entry("frameCount", nonNegativeInt()), Map.entry("captureMs", nonNegativeLong()), Map.entry("intervalMs", interval()), Map.entry("sizeBytes", nonNegativeLong()), Map.entry("dropped", nonNegativeInt())), List.of("mode", "paths", "frameWidth", "frameHeight", "mimeType", "frameCount", "captureMs", "intervalMs", "sizeBytes", "dropped"));
    }

    private static Map<String, Object> objectMap(Map<String, Object> properties, List<String> required) {
        return Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", true);
    }
}
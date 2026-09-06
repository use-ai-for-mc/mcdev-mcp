package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.bridge.BridgeEndpoint;
import dev.mcdevmcp.bridge.BridgePayload;
import dev.mcdevmcp.bridge.BridgeResultTypes;
import dev.mcdevmcp.bridge.BridgeResponse;
import dev.mcdevmcp.bridge.RecordVideoWireResult;
import dev.mcdevmcp.bridge.RecordVideoFramesWireResult;
import dev.mcdevmcp.bridge.RecordVideoGridWireResult;
import dev.mcdevmcp.bridge.ScreenshotWireResult;
import dev.mcdevmcp.bridge.TextureWireResult;
import dev.mcdevmcp.bridge.payload.EntityItemTexturePayload;
import dev.mcdevmcp.bridge.payload.ItemTextureByIdPayload;
import dev.mcdevmcp.bridge.payload.ItemTexturePayload;
import dev.mcdevmcp.bridge.payload.RecordVideoFramePayload;
import dev.mcdevmcp.bridge.payload.RecordVideoTimedPayload;
import dev.mcdevmcp.bridge.payload.ScreenshotPayload;
import dev.mcdevmcp.mcp.tool.api.ToolResult;
import dev.mcdevmcp.mcp.tool.api.ContentToolResult;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionStage;

final class MediaToolSupport {
    static final int MAX_BASE64_PNG_BYTES = 7 * 1024 * 1024;
    static final long MAX_INTERVAL_MILLIS = 9_223_372_036_854L;

    private final RuntimeToolSupport runtime;

    MediaToolSupport(RuntimeToolSupport runtime) {
        this.runtime = runtime;
    }

    static Duration recordingDeadline(int frames, RecordInterval interval) {
        double perFrameMillis = switch (interval) {
            case null -> 17.0;
            case RecordInterval.Frame ignored -> 17.0;
            case RecordInterval.Fixed fixed -> RecordInterval.projectedMillis(fixed.intervalSeconds());
        };
        double captureMillis = frames * perFrameMillis;
        if (!Double.isFinite(captureMillis) || captureMillis >= Long.MAX_VALUE - 15_000d) {
            return Duration.ofMillis(Long.MAX_VALUE);
        }
        long roundedCapture = Math.round(captureMillis);
        try {
            return Duration.ofMillis(Math.addExact(roundedCapture, 15_000L));
        } catch (ArithmeticException ignored) {
            return Duration.ofMillis(Long.MAX_VALUE);
        }
    }

    CompletionStage<ContentToolResult<Void>> screenshot(ScreenshotArguments arguments) {
        BridgePayload payload = new ScreenshotPayload(arguments.downscale(), arguments.quality());
        return runtime.request(McScreenshotTool.ENDPOINT, payload, null, response -> {
            ContentToolResult<Void> failure = RuntimeToolSupport.declaredFailure(response);
            if (failure != null) {
                return failure;
            }
            ScreenshotResult result = screenshotResult(response);
            String text = result.path() + "\n(" + number(result.width()) + "x" + number(result.height()) + " JPEG, " + fixedOne((double) result.sizeBytes() / 1024) + " KB)";
            return ToolResult.text(text);
        });
    }

    CompletionStage<ContentToolResult<Void>> recordVideo(RecordVideoArguments arguments) {
        BridgePayload payload = switch (arguments.interval()) {
            case RecordInterval.Frame ignored ->
                    new RecordVideoFramePayload(arguments.frames(), "frame", arguments.output() == RecordVideoOutput.GRID ? "grid" : "frames", arguments.gridCols(), arguments.downscale(), arguments.quality());
            case RecordInterval.Fixed fixed ->
                    new RecordVideoTimedPayload(arguments.frames(), RecordInterval.projectedMillis(fixed.intervalSeconds()), arguments.output() == RecordVideoOutput.GRID ? "grid" : "frames", arguments.gridCols(), arguments.downscale(), arguments.quality());
        };
        return runtime.request(McRecordVideoTool.ENDPOINT, payload, recordingDeadline(arguments.frames(), arguments.interval()), response -> {
            ContentToolResult<Void> failure = RuntimeToolSupport.declaredFailure(response);
            if (failure != null) {
                return failure;
            }
            RecordVideoWireResult wire = runtime.decode(McRecordVideoTool.ENDPOINT, RuntimeToolSupport.requireResult(McRecordVideoTool.ENDPOINT, response), BridgeResultTypes.RECORD_VIDEO);
            return ToolResult.text(render(project(wire)));
        });
    }

    CompletionStage<ContentToolResult<Void>> itemTexture(ItemTextureArguments arguments) {
        return texture(McGetItemTextureTool.ENDPOINT, new ItemTexturePayload(arguments.slot()));
    }

    CompletionStage<ContentToolResult<Void>> entityItemTexture(EntityItemTextureArguments arguments) {
        return texture(McGetEntityItemTextureTool.ENDPOINT, new EntityItemTexturePayload(arguments.entityId(), arguments.slot().bridgeValue()));
    }

    CompletionStage<ContentToolResult<Void>> itemTextureById(ItemTextureByIdArguments arguments) {
        return texture(McGetItemTextureByIdTool.ENDPOINT, new ItemTextureByIdPayload(arguments.itemId().wireValue()));
    }

    CompletionStage<ContentToolResult<Void>> acknowledgement(BridgeEndpoint endpoint, BridgePayload payload) {
        return runtime.request(endpoint, payload, null, response -> {
            ContentToolResult<Void> failure = RuntimeToolSupport.declaredFailure(response);
            if (failure != null) {
                return failure;
            }
            return ToolResult.text(runtime.prettyJson(RuntimeToolSupport.requireResult(endpoint, response)));
        });
    }

    private CompletionStage<ContentToolResult<Void>> texture(BridgeEndpoint endpoint, BridgePayload payload) {
        return runtime.request(endpoint, payload, null, response -> {
            ContentToolResult<Void> failure = RuntimeToolSupport.declaredFailure(response);
            if (failure != null) {
                return failure;
            }
            TextureResult result = textureResult(endpoint, response);
            checkBase64Bound(result.base64Png(), endpoint);
            return ToolResult.content(List.of(McpSchema.ImageContent.builder(result.base64Png(), "image/png").build(), McpSchema.TextContent.builder(number(result.width()) + "x" + number(result.height()) + " sprite=" + result.spriteName()).build()), false);
        });
    }

    private ScreenshotResult screenshotResult(BridgeResponse response) {
        ScreenshotWireResult wire = runtime.decode(McScreenshotTool.ENDPOINT, RuntimeToolSupport.requireResult(McScreenshotTool.ENDPOINT, response), BridgeResultTypes.SCREENSHOT);
        return project(wire);
    }

    static ScreenshotResult project(ScreenshotWireResult wire) {
        return new ScreenshotResult(nativePath(McScreenshotTool.ENDPOINT, wire.path()), wire.width(), wire.height(), wire.sizeBytes(), wire.mimeType());
    }

    private TextureResult textureResult(BridgeEndpoint endpoint, BridgeResponse response) {
        TextureWireResult wire = runtime.decode(endpoint, RuntimeToolSupport.requireResult(endpoint, response), BridgeResultTypes.TEXTURE);
        return new TextureResult(wire.base64Png(), wire.width(), wire.height(), wire.spriteName());
    }

    static RecordVideoResult project(RecordVideoWireResult wire) {
        return switch (wire) {
            case RecordVideoGridWireResult grid ->
                    new RecordVideoGridResult(nativePath(McRecordVideoTool.ENDPOINT, grid.path()), grid.width(), grid.height(), grid.sizeBytes(), grid.mimeType(), grid.frameCount(), grid.frameWidth(), grid.frameHeight(), grid.gridCols(), grid.gridRows(), Duration.ofMillis(grid.captureMs()), intervalDuration(grid.intervalMs()), grid.dropped());
            case RecordVideoFramesWireResult frames ->
                    new RecordVideoFramesResult(frames.paths().stream().map(path -> nativePath(McRecordVideoTool.ENDPOINT, path)).toList(), frames.frameWidth(), frames.frameHeight(), frames.mimeType(), frames.frameCount(), Duration.ofMillis(frames.captureMs()), intervalDuration(frames.intervalMs()), frames.sizeBytes(), frames.dropped());
        };
    }

    private static Path nativePath(BridgeEndpoint endpoint, String lexeme) {
        Path path = Path.of(lexeme);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("DebugBridge " + endpoint.wireName() + " path must be absolute on the MCP host");
        }
        return path.normalize();
    }

    static Duration intervalDuration(double intervalMs) {
        if (!Double.isFinite(intervalMs) || intervalMs < 0 || intervalMs > MAX_INTERVAL_MILLIS) {
            throw new IllegalArgumentException("DebugBridge record_video intervalMs must be finite and in range 0-" + MAX_INTERVAL_MILLIS + " milliseconds");
        }
        return Duration.ofNanos(Math.round(intervalMs * 1_000_000d));
    }

    private static String renderGrid(RecordVideoGridResult result) {
        return result.path() + "\n(" + number(result.width()) + "x" + number(result.height()) + " JPEG, " + fixedOne((double) result.sizeBytes() / 1024) + " KB; " + number(result.frameCount()) + " frames @ " + number(result.frameWidth()) + "x" + number(result.frameHeight()) + ", grid " + number(result.gridCols()) + "x" + number(result.gridRows()) + "; capture " + number(result.captureDuration().toMillis()) + "ms, avg " + fixedOne(intervalMillis(result.intervalDuration())) + "ms" + dropNote(result.dropped()) + ")";
    }

    private static String renderFrames(RecordVideoFramesResult result) {
        return number(result.frameCount()) + " frames @ " + number(result.frameWidth()) + "x" + number(result.frameHeight()) + " JPEG, " + fixedOne((double) result.sizeBytes() / 1024) + " KB total; capture " + number(result.captureDuration().toMillis()) + "ms, avg " + fixedOne(intervalMillis(result.intervalDuration())) + "ms" + dropNote(result.dropped()) + "\n" + String.join("\n", result.paths().stream().map(Path::toString).toList());
    }

    private static String render(RecordVideoResult result) {
        return switch (result) {
            case RecordVideoGridResult grid -> renderGrid(grid);
            case RecordVideoFramesResult frames -> renderFrames(frames);
        };
    }

    private static double intervalMillis(Duration duration) {
        return duration.getSeconds() * 1000.0 + duration.getNano() / 1_000_000.0;
    }

    private static String dropNote(int dropped) {
        return dropped > 0 ? ", " + number(dropped) + " dropped" : "";
    }

    private static void checkBase64Bound(String base64, BridgeEndpoint endpoint) {
        if (base64.length() <= MAX_BASE64_PNG_BYTES) {
            return;
        }
        String size = fixedOne((double) base64.length() / 1024 / 1024);
        String maximum = fixedOne((double) MAX_BASE64_PNG_BYTES / 1024 / 1024);
        throw new IllegalArgumentException("Bridge '" + endpoint.wireName() + "' returned a " + size + " MB base64 PNG, exceeding the " + maximum + " MB cap. This usually means a malformed bridge response — please report it.");
    }

    private static String number(Number value) {
        return RuntimeToolSupport.nodeNumber(value);
    }

    private static String fixedOne(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

}
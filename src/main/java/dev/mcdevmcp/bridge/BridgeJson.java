package dev.mcdevmcp.bridge;

import dev.mcdevmcp.support.JsonValues;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

public final class BridgeJson {
    private final McpJsonMapper mapper;

    public BridgeJson(McpJsonMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    private static void optionalString(Map<String, Object> envelope, String field, String id) {
        Object value = envelope.get(field);
        if (value != null && !(value instanceof String)) {
            throw new IllegalArgumentException("DebugBridge response " + BridgePayloadValidator.safeDisplay(id) + " " + field + " must be a string");
        }
    }

    McpJsonMapper mapper() {
        return mapper;
    }

    public String writeRequest(BridgeRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            return mapper.writeValueAsString(new BridgeWireRequest(request.id(), request.endpoint().wireName(), request.payload()));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to serialize DebugBridge request " + request.id(), exception);
        }
    }

    public BridgeResponse readResponse(String message) {
        if (message == null || message.length() > 8 * 1024 * 1024) {
            throw new IllegalArgumentException("DebugBridge response is missing or exceeds the wire limit");
        }
        try {
            Map<String, Object> envelope = mapper.readValue(message, new TypeRef<>() {
            });
            Object rawId = envelope.get("id");
            if (!(rawId instanceof String id) || id.isBlank()) {
                throw new IllegalArgumentException("DebugBridge response is missing required id");
            }
            if (!(envelope.get("success") instanceof Boolean)) {
                throw new IllegalArgumentException("DebugBridge response " + BridgePayloadValidator.safeDisplay(id) + " is missing required success");
            }
            optionalString(envelope, "output", id);
            optionalString(envelope, "error", id);
            BridgeWireResponse wire = mapper.convertValue(envelope, BridgeWireResponse.class);
            return new BridgeResponse(wire.id(), wire.success(), envelope.containsKey("result"), JsonValues.freeze(wire.result()), wire.output(), wire.error());
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            throw new IllegalArgumentException("Malformed DebugBridge response", exception);
        }
    }
}

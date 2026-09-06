package dev.mcdevmcp.bridge;

import dev.mcdevmcp.mcp.tool.api.JsonLogicalType;
import dev.mcdevmcp.mcp.tool.api.JsonValueSchema;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;

import java.util.Objects;

/**
 * Validates a trusted DebugBridge result and materializes its server-owned
 * target exactly once. The endpoint/result-type association is selected by
 * server code; no peer-provided type metadata is consulted here.
 */
public final class BridgeResultDecoder {
    private final McpJsonMapper mapper;
    private final JsonSchemaValidator validator;

    public BridgeResultDecoder(McpJsonMapper mapper) {
        this(mapper, McpJsonDefaults.getSchemaValidator());
    }

    public BridgeResultDecoder(McpJsonMapper mapper, JsonSchemaValidator validator) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public <T> T decode(BridgeEndpoint endpoint, Object presentResult, JsonLogicalType<T> resultType) {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(resultType, "resultType");
        JsonValueSchema schema = resultType.inputSchema().orElseThrow(() -> new IllegalArgumentException("DebugBridge " + endpoint.wireName() + " result type " + resultType.id() + " does not declare an input schema"));
        JsonSchemaValidator.ValidationResponse validation;
        try {
            validation = validator.validate(schema.value(), presentResult);
        } catch (RuntimeException exception) {
            throw invalid(endpoint, resultType, "schema validation failed", exception);
        }
        if (!validation.valid()) {
            String detail = validation.errorMessage();
            if (detail == null || detail.isBlank()) {
                detail = "result does not conform to its schema";
            }
            throw invalid(endpoint, resultType, detail, null);
        }
        try {
            return resultType.targetType().decode(mapper, presentResult);
        } catch (RuntimeException exception) {
            throw invalid(endpoint, resultType, "result could not be materialized", exception);
        }
    }

    private static IllegalArgumentException invalid(BridgeEndpoint endpoint, JsonLogicalType<?> resultType, String detail, Throwable cause) {
        String boundedDetail = BridgePayloadValidator.safeDisplay(detail == null || detail.isBlank() ? "result does not conform to its trusted schema" : detail);
        String message = "DebugBridge " + endpoint.wireName() + " response has invalid result for " + resultType.id() + ": " + boundedDetail;
        return cause == null ? new IllegalArgumentException(message) : new IllegalArgumentException(message, cause);
    }
}
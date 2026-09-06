package dev.mcdevmcp.mcp.transport;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Narrows two SDK 2.0 response differences at the typed JSON-RPC boundary.
 */
final class NodeParityJsonMapper implements McpJsonMapper {
    private static final int METHOD_NOT_FOUND = -32601;
    private static final String METHOD_NOT_FOUND_PREFIX = "Method not found:";
    private static final int RESOURCE_NOT_FOUND = -32002;
    private static final String MISSING_RESOURCE_URI_MESSAGE = "[\n  {\n    \"expected\": \"string\",\n    \"code\": \"invalid_type\",\n    \"path\": [\n      \"params\",\n      \"uri\"\n    ],\n    \"message\": \"Invalid input: expected string, received undefined\"\n  }\n]";
    private static final String UNKNOWN_TOOL_PREFIX = "Tool not found: ";

    private final McpJsonMapper delegate;
    private final Set<String> resourceRequestsWithUri = ConcurrentHashMap.newKeySet();

    NodeParityJsonMapper(McpJsonMapper delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public <T> T readValue(String content, Class<T> type) throws IOException {
        return recordRequest(delegate.readValue(content, type));
    }

    @Override
    public <T> T readValue(byte[] content, Class<T> type) throws IOException {
        return recordRequest(delegate.readValue(content, type));
    }

    @Override
    public <T> T readValue(String content, TypeRef<T> type) throws IOException {
        return recordRequest(delegate.readValue(content, type));
    }

    @Override
    public <T> T readValue(byte[] content, TypeRef<T> type) throws IOException {
        return recordRequest(delegate.readValue(content, type));
    }

    @Override
    public <T> T convertValue(Object value, Class<T> type) {
        return delegate.convertValue(value, type);
    }

    @Override
    public <T> T convertValue(Object value, TypeRef<T> type) {
        return delegate.convertValue(value, type);
    }

    @Override
    public String writeValueAsString(Object value) throws IOException {
        return delegate.writeValueAsString(adaptResponse(value));
    }

    @Override
    public byte[] writeValueAsBytes(Object value) throws IOException {
        return delegate.writeValueAsBytes(adaptResponse(value));
    }

    private Object adaptResponse(Object value) {
        if (!(value instanceof McpSchema.JSONRPCResponse(
                String jsonrpc, Object id, Object result, McpSchema.JSONRPCResponse.JSONRPCError error
        ))) {
            return value;
        }
        boolean resourceUriWasPresent = resourceRequestsWithUri.remove(requestIdKey(id));
        if (result instanceof McpSchema.InitializeResult initializeResult) {
            return new McpSchema.JSONRPCResponse(jsonrpc, id, withoutSdkLoggingCapability(initializeResult), null);
        }
        if (error != null && error.code() == METHOD_NOT_FOUND && error.message().startsWith(METHOD_NOT_FOUND_PREFIX)) {
            var nodeError = new McpSchema.JSONRPCResponse.JSONRPCError(error.code(), "Method not found", error.data());
            return new McpSchema.JSONRPCResponse(jsonrpc, id, null, nodeError);
        }
        if (error != null && error.code() == RESOURCE_NOT_FOUND && error.data() instanceof java.util.Map<?, ?> data && data.get("uri") instanceof String uri) {
            String message = uri.isEmpty() && !resourceUriWasPresent ? MISSING_RESOURCE_URI_MESSAGE : "Unknown resource URI: " + uri;
            var nodeError = new McpSchema.JSONRPCResponse.JSONRPCError(-32603, message, null);
            return new McpSchema.JSONRPCResponse(jsonrpc, id, null, nodeError);
        }
        if (error != null && error.data() instanceof String message && message.startsWith(UNKNOWN_TOOL_PREFIX)) {
            var callResult = McpSchema.CallToolResult.builder().addTextContent("Unknown tool: " + message.substring(UNKNOWN_TOOL_PREFIX.length())).isError(true).build();
            return new McpSchema.JSONRPCResponse(jsonrpc, id, callResult, null);
        }
        return value;
    }

    private <T> T recordRequest(T value) {
        Object method;
        Object id;
        Object params;
        if (value instanceof McpSchema.JSONRPCRequest request) {
            method = request.method();
            id = request.id();
            params = request.params();
        }
        else if (value instanceof Map<?, ?> request) {
            method = request.get("method");
            id = request.get("id");
            params = request.get("params");
        }
        else {
            return value;
        }
        if ("resources/read".equals(method) && params instanceof Map<?, ?> resourceParams && resourceParams.containsKey("uri")) {
            resourceRequestsWithUri.add(requestIdKey(id));
        }
        return value;
    }

    private String requestIdKey(Object id) {
        if (id instanceof Number) {
            return "number:" + id;
        }
        return "string:" + id;
    }

    private McpSchema.InitializeResult withoutSdkLoggingCapability(McpSchema.InitializeResult result) {
        McpSchema.ServerCapabilities capabilities = result.capabilities();
        var nodeCapabilities = new McpSchema.ServerCapabilities(capabilities.completions(), capabilities.experimental(), null, capabilities.prompts(), capabilities.resources(), capabilities.tools());
        return new McpSchema.InitializeResult(result.protocolVersion(), nodeCapabilities, result.serverInfo(), result.instructions(), result.meta());
    }
}
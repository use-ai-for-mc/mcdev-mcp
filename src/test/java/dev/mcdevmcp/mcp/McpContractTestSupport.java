package dev.mcdevmcp.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class McpContractTestSupport {
    private static final McpJsonMapper MAPPER = McpJsonDefaults.getMapper();
    private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() {
    };

    private McpContractTestSupport() {
    }

    public static Map<String, Object> readContract(String name) throws IOException {
        try (var input = McpContractTestSupport.class.getResourceAsStream("/contracts/mcp/" + name)) {
            if (input == null) {
                throw new IOException("Missing contract: " + name);
            }
            return MAPPER.readValue(input.readAllBytes(), MAP_TYPE);
        }
    }

    public static String normalize(Object value) throws IOException {
        return MAPPER.writeValueAsString(normalizeValue(value));
    }

    private static Object normalizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            var normalized = new TreeMap<String, Object>();
            map.forEach((key, item) -> normalized.put((String) key, normalizeValue(item)));
            return normalized;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(McpContractTestSupport::normalizeValue).toList();
        }
        return value;
    }
}

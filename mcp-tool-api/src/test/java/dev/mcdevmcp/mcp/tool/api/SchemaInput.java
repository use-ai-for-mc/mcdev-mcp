package dev.mcdevmcp.mcp.tool.api;

import java.math.BigDecimal;

record SchemaInput(@InputProperty(description = "Search text", required = true, defaultValue = "all") String query, @InputProperty(defaultValue = "true") boolean includeDetails, @InputProperty(minimum = "0.25", maximum = "4.50", defaultValue = "1.50") BigDecimal threshold, @InputProperty(defaultValue = "THOROUGH") InputMode mode, @InputProperty(defaultValue = "42") long limit, String optionalFilter) {
}
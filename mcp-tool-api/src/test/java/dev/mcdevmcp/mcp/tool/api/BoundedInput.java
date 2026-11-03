package dev.mcdevmcp.mcp.tool.api;

import java.math.BigDecimal;

value record BoundedInput(@InputProperty(required = true, minimum = "-0", maximum = "1E+1000") BigDecimal value) {
}
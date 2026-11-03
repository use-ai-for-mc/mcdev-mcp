package dev.mcdevmcp.mcp.tool.api;

import java.time.Duration;

value record DurationInput(@InputProperty(required = true) Duration timeoutSeconds) {
}

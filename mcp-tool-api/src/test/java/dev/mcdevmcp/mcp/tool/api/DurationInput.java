package dev.mcdevmcp.mcp.tool.api;

import java.time.Duration;

record DurationInput(@InputProperty(required = true) Duration timeoutSeconds) {
}

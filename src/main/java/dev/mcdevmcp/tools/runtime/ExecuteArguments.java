package dev.mcdevmcp.tools.runtime;

import dev.mcdevmcp.mcp.tool.api.InputProperty;

import java.time.Duration;

record ExecuteArguments(@InputProperty(description = "Groovy code to execute", required = true) String code, @InputProperty(description = "Optional per-call execution deadline in seconds. Range 1-300, default 10 (10s). Use a longer value for bulk reflection or heavy file I/O.", minimum = "1", maximum = "300", defaultValue = "10") Duration timeoutSeconds) {
    ExecuteArguments {
        code = RuntimeToolSupport.requiredString(code, "code");
        timeoutSeconds = timeoutSeconds == null ? Duration.ofSeconds(10) : timeoutSeconds;
    }
}

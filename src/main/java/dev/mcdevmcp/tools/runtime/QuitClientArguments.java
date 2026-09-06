package dev.mcdevmcp.tools.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mcdevmcp.mcp.tool.api.InputProperty;

import java.time.Duration;

record QuitClientArguments(@InputProperty(description = "Wait until the client is actually gone — bridge port closed, then the client process exited (when its PID could be resolved) — before returning. Default true.", defaultValue = "true") boolean waitForExit, @InputProperty(description = "How long to wait for the whole shutdown (port close + process exit). Default 30.", minimum = "0", defaultValue = "30") Duration timeoutSeconds) {
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    static QuitClientArguments fromJson(@JsonProperty("waitForExit") Boolean waitForExit, @JsonProperty("timeoutSeconds") Duration timeoutSeconds) {
        return new QuitClientArguments(waitForExit == null || waitForExit, timeoutSeconds == null ? Duration.ofSeconds(SessionControlSupport.DEFAULT_QUIT_TIMEOUT_SECONDS) : timeoutSeconds);
    }

    QuitClientArguments {
        if (timeoutSeconds == null || timeoutSeconds.isNegative()) {
            throw new IllegalArgumentException("'timeoutSeconds' must be a non-negative duration in seconds");
        }
    }
}

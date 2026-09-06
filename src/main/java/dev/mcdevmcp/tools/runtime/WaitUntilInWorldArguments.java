package dev.mcdevmcp.tools.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mcdevmcp.mcp.tool.api.InputProperty;

import java.time.Duration;

record WaitUntilInWorldArguments(@InputProperty(description = "Give up after this many seconds. Default 60.", minimum = "0", defaultValue = "60") Duration timeoutSeconds, @InputProperty(description = "Only count a player snapshot as in-world after the old session visibly dropped (one successful snapshot without a player). Use when a join was issued from inside a world. Default false.", defaultValue = "false") boolean requireAbsenceFirst) {
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    static WaitUntilInWorldArguments fromJson(@JsonProperty("timeoutSeconds") Duration timeoutSeconds, @JsonProperty("requireAbsenceFirst") Boolean requireAbsenceFirst) {
        return new WaitUntilInWorldArguments(timeoutSeconds == null ? Duration.ofSeconds(SessionControlSupport.DEFAULT_JOIN_TIMEOUT_SECONDS) : timeoutSeconds, Boolean.TRUE.equals(requireAbsenceFirst));
    }

    WaitUntilInWorldArguments {
        if (timeoutSeconds == null || timeoutSeconds.isNegative()) {
            throw new IllegalArgumentException("'timeoutSeconds' must be a non-negative duration in seconds");
        }
    }
}

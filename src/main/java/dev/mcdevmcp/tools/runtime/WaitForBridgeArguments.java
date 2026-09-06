package dev.mcdevmcp.tools.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mcdevmcp.mcp.tool.api.InputProperty;
import dev.mcdevmcp.storage.model.MinecraftVersion;

import java.time.Duration;

record WaitForBridgeArguments(@InputProperty(description = "Only accept an instance reporting this Minecraft version (e.g. \"1.21.11\"). Overrides the identity remembered from the previous connection — use when deliberately switching instances.") MinecraftVersion expectedVersion, @InputProperty(description = "Give up after this many seconds. Default 120.", minimum = "0", defaultValue = "120") Duration timeoutSeconds) {
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    static WaitForBridgeArguments fromJson(@JsonProperty("expectedVersion") MinecraftVersion expectedVersion, @JsonProperty("timeoutSeconds") Duration timeoutSeconds) {
        return new WaitForBridgeArguments(expectedVersion, timeoutSeconds == null ? Duration.ofSeconds(SessionControlSupport.DEFAULT_BRIDGE_WAIT_TIMEOUT_SECONDS) : timeoutSeconds);
    }

    WaitForBridgeArguments {
        if (timeoutSeconds == null || timeoutSeconds.isNegative()) {
            throw new IllegalArgumentException("'timeoutSeconds' must be a non-negative duration in seconds");
        }
    }
}

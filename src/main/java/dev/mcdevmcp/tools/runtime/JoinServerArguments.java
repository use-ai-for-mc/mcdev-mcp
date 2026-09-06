package dev.mcdevmcp.tools.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.mcdevmcp.mcp.tool.api.InputProperty;
import dev.mcdevmcp.minecraft.MinecraftServerAddress;

import java.time.Duration;

record JoinServerArguments(@InputProperty(description = "Server address, \"host\" or \"host:port\" (e.g. \"localhost:25565\")", required = true) MinecraftServerAddress address, @InputProperty(description = "Pre-accept the server resource pack. Default true.", defaultValue = "true") boolean acceptResourcePacks, @JsonProperty("wait") @InputProperty(description = "Poll until in-world / disconnected before returning. Default true.", defaultValue = "true") boolean waitForWorld, @InputProperty(description = "How long to wait for the join to complete. Default 60.", minimum = "0", defaultValue = "60") Duration timeoutSeconds) {
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    static JoinServerArguments fromJson(@JsonProperty("address") MinecraftServerAddress address, @JsonProperty("acceptResourcePacks") Boolean acceptResourcePacks, @JsonProperty("wait") Boolean wait, @JsonProperty("timeoutSeconds") Duration timeoutSeconds) {
        return new JoinServerArguments(address, acceptResourcePacks == null || acceptResourcePacks, wait == null || wait, timeoutSeconds == null ? Duration.ofSeconds(SessionControlSupport.DEFAULT_JOIN_TIMEOUT_SECONDS) : timeoutSeconds);
    }

    JoinServerArguments {
        if (address == null) {
            throw new IllegalArgumentException("'address' is required");
        }
        if (timeoutSeconds == null || timeoutSeconds.isNegative()) {
            throw new IllegalArgumentException("'timeoutSeconds' must be a non-negative duration in seconds");
        }
    }
}

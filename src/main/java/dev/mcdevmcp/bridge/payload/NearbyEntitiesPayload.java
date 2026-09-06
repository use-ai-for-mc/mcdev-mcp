package dev.mcdevmcp.bridge.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.mcdevmcp.bridge.BridgePayload;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NearbyEntitiesPayload(Double range, Integer limit, boolean includeIcons) implements BridgePayload {
}

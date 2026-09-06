package dev.mcdevmcp.bridge.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.mcdevmcp.bridge.BridgePayload;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NearbyBlocksPayload(Double range, Integer limit) implements BridgePayload {
}

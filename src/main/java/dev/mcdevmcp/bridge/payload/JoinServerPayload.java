package dev.mcdevmcp.bridge.payload;

import dev.mcdevmcp.bridge.BridgePayload;

import java.util.Objects;

public value record JoinServerPayload(String address, boolean acceptResourcePacks) implements BridgePayload {
    public JoinServerPayload {
        address = Objects.requireNonNull(address, "address");
    }
}

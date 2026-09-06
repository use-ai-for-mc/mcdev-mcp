package dev.mcdevmcp.bridge.payload;

import dev.mcdevmcp.bridge.BridgePayload;

import java.util.Objects;

public record RunCommandPayload(String command) implements BridgePayload {
    public RunCommandPayload {
        command = Objects.requireNonNull(command, "command");
    }
}

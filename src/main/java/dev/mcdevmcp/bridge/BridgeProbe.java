package dev.mcdevmcp.bridge;

import dev.mcdevmcp.bridge.payload.EmptyBridgePayload;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

public final class BridgeProbe {
    private static final BridgeEndpoint STATUS = new BridgeEndpoint("status");

    private final BridgeSession session;

    public BridgeProbe(BridgeSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    public BridgeResponse status() throws IOException, InterruptedException {
        return session.send(STATUS, new EmptyBridgePayload(), null);
    }

    public OptionalInt connectedPort() {
        return session.connectedPort();
    }

    public Optional<SessionInfo> sessionInfo() {
        return session.sessionInfo();
    }
}
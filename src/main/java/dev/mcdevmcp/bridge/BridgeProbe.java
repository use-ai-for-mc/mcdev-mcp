package dev.mcdevmcp.bridge;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletionStage;

import dev.mcdevmcp.bridge.payload.EmptyBridgePayload;

public final class BridgeProbe {
    private static final BridgeEndpoint STATUS = new BridgeEndpoint("status");

    private final BridgeSession session;

    public BridgeProbe(BridgeSession session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    public CompletionStage<BridgeResponse> status() {
        return session.send(STATUS, new EmptyBridgePayload(), null);
    }

    public OptionalInt connectedPort() {
        return session.connectedPort();
    }

    public Optional<SessionInfo> sessionInfo() {
        return session.sessionInfo();
    }
}

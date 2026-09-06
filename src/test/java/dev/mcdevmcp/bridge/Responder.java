package dev.mcdevmcp.bridge;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface Responder {
    CompletionStage<BridgeResponse> respond(int connection, BridgeRequest request);
}